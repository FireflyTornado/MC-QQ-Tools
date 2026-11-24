package cn.nightbugclub.mcqqauthcapture;

import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.bukkit.Server;
import org.bukkit.command.CommandException;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SudoChannelTest {
    @Test
    void failedHandshakesBackOffAndOpeningResetsTheDelay() {
        try (var fixture = new ChannelFixture()) {
            when(fixture.builder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("handshake failed")));
            fixture.channel.start();
            fixture.runNext(0, MILLISECONDS);
            for (int delay : new int[]{1000, 2000, 4000, 8000, 16000, 30000, 30000}) {
                fixture.runNext(delay, MILLISECONDS);
            }

            Peer peer = fixture.openNext(30000);
            ScheduledTask firstPing = fixture.takeNext(20, SECONDS);
            peer.disconnect();
            verify(firstPing.future()).cancel(false);
            fixture.takeNext(1000, MILLISECONDS);
            assertTrue(fixture.scheduled.isEmpty());
        }
    }

    @Test
    void onlyMatchingPongSchedulesNextPingAndMissingPongReconnects() {
        try (var fixture = new ChannelFixture()) {
            Peer peer = fixture.connect();
            peer.listener.onPong(peer.socket, ByteBuffer.allocate(0));
            fixture.runNext(20, SECONDS);
            ScheduledTask firstTimeout = fixture.takeNext(15, SECONDS);
            long firstPing = peer.pings.remove();

            peer.pong(firstPing + 1);
            verifyNoInteractions(firstTimeout.future());
            assertTrue(fixture.scheduled.isEmpty());
            peer.pong(firstPing);
            verify(firstTimeout.future()).cancel(false);
            fixture.runNext(20, SECONDS);
            ScheduledTask secondTimeout = fixture.takeNext(15, SECONDS);
            assertEquals(firstPing + 1, peer.pings.remove());

            peer.pong(firstPing);
            firstTimeout.action().run();
            verify(peer.socket, never()).abort();
            secondTimeout.action().run();
            verify(peer.socket).abort();
            fixture.takeNext(1000, MILLISECONDS);
            assertTrue(fixture.scheduled.isEmpty());
        }
    }

    @Test
    void reconnectReleasesQueuedRequestWithoutReleasingItsReplacement() {
        try (var fixture = new ChannelFixture()) {
            var original = fixture.connect();
            original.send(1);
            original.disconnect();

            var reconnected = fixture.openNext(1000);
            reconnected.send(1);
            assertEquals("rejected", reconnected.status());
            reconnected.send(2);
            assertEquals(2, fixture.tasks.size());
            fixture.tasks.remove().run();
            verifyNoInteractions(fixture.commands);
            reconnected.send(3);
            assertEquals("busy", reconnected.status());
            fixture.tasks.remove().run();
            assertEquals("ok", reconnected.status());
            verify(fixture.commands).execute(eq("test2"), anyLong(), any(ScheduledOutput.class));
            verifyNoMoreInteractions(fixture.commands);
            assertTrue(original.replies.isEmpty());
        }
    }

    @Test
    void reconnectKeepsSlotWhileOldCommandIsExecuting() {
        try (var fixture = new ChannelFixture()) {
            var original = fixture.connect();
            var reconnected = new ArrayDeque<Peer>();
            when(fixture.commands.execute(eq("test1"), anyLong(), any(ScheduledOutput.class))).thenAnswer(call -> {
                original.disconnect();
                var next = fixture.openNext(1000);
                next.send(2);
                assertEquals("busy", next.status());
                assertTrue(fixture.tasks.isEmpty());
                reconnected.add(next);
                return CompletableFuture.completedFuture(new SudoCommands.Result("ok"));
            });
            original.send(1);
            fixture.tasks.remove().run();

            var next = reconnected.remove();
            next.send(3);
            fixture.tasks.remove().run();
            assertEquals("ok", next.status());
            verify(fixture.commands).execute(eq("test1"), anyLong(), any(ScheduledOutput.class));
            verify(fixture.commands).execute(eq("test3"), anyLong(), any(ScheduledOutput.class));
            verifyNoMoreInteractions(fixture.commands);
            assertTrue(original.replies.isEmpty());
        }
    }

    @Test
    void synchronousLinkageErrorReportsFailureAndReleasesTheSlot() {
        try (var fixture = new ChannelFixture()) {
            var peer = fixture.connect();
            when(fixture.commands.execute(eq("test1"), anyLong(), any(ScheduledOutput.class)))
                    .thenThrow(new LinkageError("Plugin dependency unavailable"));
            peer.send(1);
            fixture.tasks.remove().run();
            assertEquals("error", peer.status());

            peer.send(2);
            fixture.tasks.remove().run();
            assertEquals("ok", peer.status());
        }
    }

    @Test
    void fatalErrorDoesNotLeaveTheSlotClaimedAfterAReconnect() {
        try (var fixture = new ChannelFixture()) {
            var original = fixture.connect();
            var reconnected = new AtomicReference<Peer>();
            var fatal = new InternalError("Simulated VM failure");
            when(fixture.commands.execute(eq("test1"), anyLong(), any(ScheduledOutput.class))).thenAnswer(call -> {
                original.disconnect();
                reconnected.set(fixture.openNext(1000));
                reconnected.get().send(2);
                assertEquals("busy", reconnected.get().status());
                assertTrue(fixture.tasks.isEmpty());
                throw fatal;
            });
            original.send(1);
            assertEquals(fatal, assertThrows(InternalError.class, fixture.tasks.remove()::run));
            reconnected.get().send(3);
            fixture.tasks.remove().run();
            assertEquals("ok", reconnected.get().status());
        }
    }

    @Test
    void repliesOnlyAfterOutputCollectionCompletes() {
        try (var fixture = new ChannelFixture()) {
            var peer = fixture.connect();
            var result = new CompletableFuture<SudoCommands.Result>();
            when(fixture.commands.execute(eq("test1"), anyLong(), any(ScheduledOutput.class))).thenReturn(result);

            peer.send(1);
            fixture.tasks.remove().run();
            assertTrue(peer.replies.isEmpty());
            peer.send(2);
            assertEquals("busy", peer.status());
            result.complete(new SudoCommands.Result("partial", "some output\n"));
            assertEquals("partial", peer.status());

            peer.send(3);
            fixture.tasks.remove().run();
            assertEquals("ok", peer.status());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acknowledgesScheduleThenReleasesSlotAndReturnsCancellation(boolean streaming) throws Exception {
        try (var fixture = new ChannelFixture()) {
            var peer = fixture.connect();
            fixture.takeNext(20, SECONDS);
            var frames = new LinkedBlockingQueue<String>();
            doAnswer(call -> {
                frames.add(call.getArgument(0));
                return CompletableFuture.completedFuture(peer.socket);
            }).when(peer.socket).sendText(anyString(), eq(true));
            var event = new Object();
            when(fixture.commands.execute(eq("test1"), anyLong(), any(ScheduledOutput.class))).thenAnswer(call -> {
                var close = OutputCapture.begin(ignored -> {}, call.getArgument(2));
                ScheduledOutput.enqueue(event);
                close.get().join();
                return CompletableFuture.completedFuture(new SudoCommands.Result("ok", "queued"));
            });
            peer.send(1, streaming);
            fixture.tasks.remove().run();
            var ack = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
            assertEquals("scheduled", ack.get("status").getAsString());
            assertEquals("queued", ack.get("output").getAsString());
            var expiry = fixture.takeNext(3600, SECONDS);
            assertEquals(streaming, ack.has("wait_seconds"));
            if (streaming) {
                assertEquals(3600, ack.get("wait_seconds").getAsInt());
            }

            peer.send(2);
            fixture.tasks.remove().run();
            assertEquals("ok", JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject()
                    .get("status").getAsString());
            ScheduledOutput.cancel(event);
            assertTrue(frames.isEmpty());
            fixture.terminalReplies.remove().run();
            var followup = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
            assertEquals(ack.get("id"), followup.get("id"));
            assertEquals("cancelled", followup.get("status").getAsString());
            assertTrue(followup.get("followup").getAsBoolean());
            assertEquals(streaming, followup.has("sequence"));
            if (streaming) {
                assertEquals(1, followup.get("sequence").getAsInt());
                assertTrue(followup.get("final").getAsBoolean());
            }
            verify(expiry.future()).cancel(false);
        }
    }

    @Test
    void stopFollowupsClosesOnlyTheListedCapturesAndKeepsTheChannelOpen() throws Exception {
        try (var fixture = new ChannelFixture()) {
            var peer = fixture.connect();
            verify(fixture.builder).header("X-MCQQSudo-Capabilities", "stop_followups");
            fixture.takeNext(20, SECONDS);
            var stopped = new Object();
            var retained = new Object();
            for (int id : new int[]{1, 2}) {
                var event = id == 1 ? stopped : retained;
                when(fixture.commands.execute(eq("test" + id), anyLong(), any(ScheduledOutput.class))).thenAnswer(call -> {
                    var close = OutputCapture.begin(ignored -> {}, call.getArgument(2));
                    ScheduledOutput.enqueue(event);
                    close.get().join();
                    return CompletableFuture.completedFuture(new SudoCommands.Result("ok"));
                });
                peer.send(id, true);
                fixture.tasks.remove().run();
                assertEquals("scheduled", peer.status());
            }
            var expiry = fixture.takeNext(3600, SECONDS);
            var update = fixture.takeNext(1, SECONDS);
            String stopId = "%032x".formatted(5);
            String stoppedId = "%032x".formatted(1);
            peer.listener.onText(peer.socket, """
                    {"type":"stop_followups","id":"%s","request_ids":["%s"]}
                    """.formatted(stopId, stoppedId), true);
            var reply = JsonParser.parseString(peer.replies.remove()).getAsJsonObject();
            assertEquals("stop_followups_ack", reply.get("type").getAsString());
            assertEquals(stopId, reply.get("id").getAsString());
            verify(expiry.future()).cancel(false);
            verify(update.future()).cancel(false);
            assertFalse(ScheduledOutput.hasPendingEvent(stopped));
            assertTrue(ScheduledOutput.hasPendingEvent(retained));
            var ran = new AtomicBoolean();
            ScheduledOutput.run(stopped, () -> ran.set(true));
            assertTrue(ran.get());

            peer.send(3);
            fixture.tasks.remove().run();
            assertEquals("ok", peer.status());
            assertTrue(peer.replies.isEmpty());
        }
    }

    @Test
    void reportsInitialCommandFailureAndStillSendsTheTrackedTaskResult() throws Exception {
        try (var fixture = new ChannelFixture()) {
            var peer = fixture.connect();
            var frames = new LinkedBlockingQueue<String>();
            when(peer.socket.sendText(anyString(), eq(true))).thenAnswer(call -> {
                frames.add(call.getArgument(0));
                return CompletableFuture.completedFuture(peer.socket);
            });
            var task = new Object();
            when(fixture.commands.execute(eq("test1"), anyLong(), any(ScheduledOutput.class))).thenAnswer(call -> {
                var close = OutputCapture.begin(ignored -> {}, call.getArgument(2));
                ScheduledOutput.enqueueTask(task);
                close.get().join();
                return CompletableFuture.failedFuture(new CommandException("Command failed after registering a task"));
            });
            peer.send(1, true);
            fixture.tasks.remove().run();
            var ack = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
            assertEquals("error", ack.get("status").getAsString());
            assertEquals("", ack.get("output").getAsString());
            assertEquals(3600, ack.get("wait_seconds").getAsInt());

            ScheduledOutput.run(task, () -> LogManager.getLogger("test.channel").error("Task finished"));
            fixture.terminalReplies.remove().run();
            var followup = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
            assertEquals(ack.get("id"), followup.get("id"));
            assertEquals("ok", followup.get("status").getAsString());
            assertEquals(1, followup.get("sequence").getAsInt());
            assertTrue(followup.get("final").getAsBoolean());
            assertEquals("Task finished\n", followup.get("output").getAsString());
        }
    }

    @Test
    void streamsUnregisteredSenderOutputAndFlushesTheLastBatchAtThirtySeconds() throws Exception {
        try (var fixture = new ChannelFixture()) {
            var peer = fixture.connect();
            fixture.takeNext(20, SECONDS); // Heartbeat is independent of the reply window.
            var frames = new LinkedBlockingQueue<String>();
            var sent = new CompletableFuture<WebSocket>();
            doAnswer(call -> {
                frames.add(call.getArgument(0));
                return sent;
            }).when(peer.socket).sendText(anyString(), eq(true));
            var output = new AtomicReference<CommandOutput>();
            when(fixture.commands.execute(eq("test1"), anyLong(), any(ScheduledOutput.class))).thenAnswer(call -> {
                ScheduledOutput scheduled = call.getArgument(2);
                output.set(scheduled.output());
                output.get().finishInitial();
                return CompletableFuture.completedFuture(new SudoCommands.Result("ok"));
            });
            peer.send(1, true);
            fixture.tasks.remove().run();
            var ack = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
            assertEquals("ok", ack.get("status").getAsString());
            assertEquals(30, ack.get("wait_seconds").getAsInt());
            assertTrue(fixture.scheduled.isEmpty(), "Window starts once the first reply is sent");
            sent.complete(peer.socket);
            var expiry = fixture.takeNext(30, SECONDS);

            for (int sequence = 1; sequence <= 2; sequence++) {
                String text = "late reply " + sequence;
                output.get().appendLine(text);
                fixture.runNext(1, SECONDS);
                var frame = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
                assertEquals(ack.get("id"), frame.get("id"));
                assertEquals(sequence, frame.get("sequence").getAsInt());
                assertEquals(false, frame.get("final").getAsBoolean());
                assertEquals(text + "\n", frame.get("output").getAsString());
            }

            output.get().appendLine("final batch");
            expiry.action().run();
            fixture.terminalReplies.remove().run();
            var last = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
            assertEquals("expired", last.get("status").getAsString());
            assertEquals(3, last.get("sequence").getAsInt());
            assertTrue(last.get("final").getAsBoolean());
            assertEquals("final batch\n", last.get("output").getAsString());
            output.get().appendLine("ignored after expiry");
            assertEquals("", output.get().takeFollowup().text());
        }
    }

    @Test
    void streamsLateSenderOutputAfterDispatchThrowsButDoesNotWaitForCommandsThatNeverRan() throws Exception {
        try (var fixture = new ChannelFixture()) {
            var peer = fixture.connect();
            fixture.takeNext(20, SECONDS);
            var frames = new LinkedBlockingQueue<String>();
            when(peer.socket.sendText(anyString(), eq(true))).thenAnswer(call -> {
                frames.add(call.getArgument(0));
                return CompletableFuture.completedFuture(peer.socket);
            });

            var server = mock(Server.class);
            var lateReply = new AtomicReference<Runnable>();
            var feedbackAvailable = new AtomicBoolean(true);
            when(server.dispatchCommand(any(RemoteConsoleCommandSender.class), eq("test1"))).thenAnswer(call -> {
                RemoteConsoleCommandSender sender = call.getArgument(0);
                lateReply.set(() -> sender.sendMessage("Late report"));
                throw new CommandException("Command failed after submitting work to another executor");
            });
            var commands = new SudoCommands(server, Set.of("test1", "test2", "test3"), output -> {
                if (!feedbackAvailable.get()) {
                    throw new IllegalStateException("Feedback unavailable");
                }
                var sender = mock(RemoteConsoleCommandSender.class);
                doAnswer(call -> {
                    output.appendLine(call.getArgument(0, String.class));
                    return null;
                }).when(sender).sendMessage(anyString());
                return new MohistCommandFeedback.Capture(sender, ignored -> () -> CompletableFuture.completedFuture(true));
            });
            when(fixture.commands.execute(anyString(), anyLong(), any(ScheduledOutput.class))).thenAnswer(call ->
                    commands.execute(call.getArgument(0), call.getArgument(1), call.getArgument(2)));

            peer.send(1, true);
            fixture.tasks.remove().run();
            var ack = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
            assertEquals("error", ack.get("status").getAsString());
            assertEquals(30, ack.get("wait_seconds").getAsInt());
            long initialElapsed = ack.get("elapsed_ms").getAsLong();
            assertTrue(initialElapsed >= 0);
            var expiry = fixture.takeNext(30, SECONDS);
            MILLISECONDS.sleep(20);
            CompletableFuture.runAsync(lateReply.get()).get(2, SECONDS);
            fixture.runNext(1, SECONDS);
            var update = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
            assertEquals(ack.get("id"), update.get("id"));
            assertEquals(1, update.get("sequence").getAsInt());
            assertEquals("Late report\n", update.get("output").getAsString());
            assertTrue(update.get("elapsed_ms").getAsLong() >= initialElapsed + 10);
            expiry.action().run();
            fixture.terminalReplies.remove().run();
            var terminal = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
            assertTrue(terminal.get("final").getAsBoolean());
            assertTrue(terminal.get("elapsed_ms").getAsLong() >= update.get("elapsed_ms").getAsLong());

            peer.send(2, true);
            fixture.tasks.remove().run();
            var missing = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
            assertEquals("error", missing.get("status").getAsString());
            assertNull(missing.get("wait_seconds"));

            feedbackAvailable.set(false);
            peer.send(3, true);
            fixture.tasks.remove().run();
            var unavailable = JsonParser.parseString(frames.poll(2, SECONDS)).getAsJsonObject();
            assertEquals("error", unavailable.get("status").getAsString());
            assertNull(unavailable.get("wait_seconds"));
            assertTrue(unavailable.get("elapsed_ms").isJsonNull());
            verify(server, never()).dispatchCommand(any(), eq("test3"));
        }
    }

    private static final class ChannelFixture implements AutoCloseable {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final ArrayDeque<Runnable> terminalReplies = new ArrayDeque<>();
        final ArrayDeque<ScheduledTask> scheduled = new ArrayDeque<>();
        final SudoCommands commands = mock(SudoCommands.class);
        final WebSocket.Builder builder = mock(WebSocket.Builder.class, RETURNS_SELF);
        final SudoChannel channel;

        ChannelFixture() {
            var http = mock(HttpClient.class);
            var timer = mock(ScheduledExecutorService.class);
            doAnswer(call -> {
                terminalReplies.add(call.getArgument(0));
                return null;
            }).when(timer).execute(any(Runnable.class));
            when(timer.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
                var future = mock(ScheduledFuture.class);
                var task = new ScheduledTask(call.getArgument(0), call.getArgument(1), call.getArgument(2), future);
                when(future.cancel(false)).thenAnswer(cancel -> scheduled.remove(task));
                scheduled.add(task);
                return future;
            });
            when(http.newWebSocketBuilder()).thenReturn(builder);
            when(commands.execute(anyString(), anyLong(), any(ScheduledOutput.class)))
                    .thenReturn(CompletableFuture.completedFuture(new SudoCommands.Result("ok")));
            var settings = new SudoChannel.Settings(URI.create("wss://localhost/sudo"), "test-token");
            channel = new SudoChannel(settings, commands, tasks::add, mock(Logger.class), 1000, 30000, http, timer);
        }

        Peer connect() {
            channel.start();
            return openNext(0);
        }

        Peer openNext(int delayMs) {
            var opened = new ArrayDeque<Peer>();
            doAnswer(call -> {
                var peer = new Peer(call.getArgument(1));
                peer.listener.onOpen(peer.socket);
                opened.add(peer);
                return CompletableFuture.completedFuture(peer.socket);
            }).when(builder).buildAsync(any(URI.class), any(WebSocket.Listener.class));
            runNext(delayMs, MILLISECONDS);
            return opened.remove();
        }

        ScheduledTask takeNext(long delay, TimeUnit unit) {
            ScheduledTask next = scheduled.remove();
            assertEquals(delay, next.delay());
            assertEquals(unit, next.unit());
            return next;
        }

        void runNext(long delay, TimeUnit unit) {
            takeNext(delay, unit).action().run();
        }

        @Override
        public void close() {
            channel.close();
        }
    }

    private record ScheduledTask(Runnable action, long delay, TimeUnit unit, ScheduledFuture<?> future) {}

    private static final class Peer {
        final WebSocket.Listener listener;
        final WebSocket socket = mock(WebSocket.class);
        final ArrayDeque<String> replies = new ArrayDeque<>();
        final ArrayDeque<Long> pings = new ArrayDeque<>();

        Peer(WebSocket.Listener listener) {
            this.listener = listener;
            when(socket.sendPing(any(ByteBuffer.class))).thenAnswer(call -> {
                pings.add(call.<ByteBuffer>getArgument(0).getLong());
                return CompletableFuture.completedFuture(socket);
            });
            when(socket.sendText(anyString(), eq(true))).thenAnswer(call -> {
                replies.add(call.getArgument(0));
                return CompletableFuture.completedFuture(socket);
            });
        }

        void send(int id) {
            send(id, false);
        }

        void send(int id, boolean streamFollowup) {
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(25).toMillis();
            String message = """
                    {"id":"%032x","command":"test%d","deadline_ms":%d,"stream_followup":%b}
                    """.formatted(id, id, deadline, streamFollowup);
            listener.onText(socket, message, true);
        }

        String status() {
            return JsonParser.parseString(replies.remove()).getAsJsonObject().get("status").getAsString();
        }

        void disconnect() {
            listener.onClose(socket, 1000, "");
        }

        void pong(long ping) {
            listener.onPong(socket, ByteBuffer.allocate(Long.BYTES).putLong(ping).flip());
        }
    }

    @Test
    void requestRejectedWhileBusyCannotRunOnReplay() {
        var history = new SudoChannel.RequestHistory();
        var request = new SudoChannel.Request("original", "list", 20_000);
        var refused = new SudoChannel.Request("refused", "say hello", 20_000);
        var next = new SudoChannel.Request("new", "say hello", 20_000);

        assertNull(history.admit(request, 1_000, false));
        assertEquals("busy", history.admit(refused, 2_000, true));
        assertEquals("rejected", history.admit(refused, 3_000, false));
        assertNull(history.admit(next, 3_000, false));
    }

    @Test
    void fullHistoryKeepsRecordsAndBlocksUntrackedReplaysUntilTheirDeadline() {
        var history = new SudoChannel.RequestHistory();
        var original = new SudoChannel.Request("0", "list", 10_000);
        assertNull(history.admit(original, 1_000, false));
        for (int id = 1; id < 1024; id++) {
            var request = new SudoChannel.Request(Integer.toString(id), "list", 10_000);
            assertEquals("busy", history.admit(request, 1_000, true));
        }

        var refused = new SudoChannel.Request("overflow", "list", 20_000);
        assertEquals("busy", history.admit(refused, 2_000, false));
        assertEquals("rejected", history.admit(original, 3_000, false));
        assertEquals("busy", history.admit(refused, 11_000, false));

        var next = new SudoChannel.Request("fresh", "list", 30_000);
        assertNull(history.admit(next, 20_000, false));
    }
}
