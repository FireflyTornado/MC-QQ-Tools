package cn.nightbugclub.mcqqauth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SudoChannelTest {
    @Test
    void reconnectReleasesQueuedRequestWithoutReleasingItsReplacement() {
        try (var fixture = new ChannelFixture()) {
            var original = fixture.connect();
            original.send(1);
            original.disconnect();

            var reconnected = fixture.connect();
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
            verify(fixture.commands).execute("test2");
            verifyNoMoreInteractions(fixture.commands);
            assertTrue(original.replies.isEmpty());
        }
    }

    @Test
    void reconnectKeepsSlotWhileOldCommandIsExecuting() {
        try (var fixture = new ChannelFixture()) {
            var original = fixture.connect();
            var reconnected = new ArrayDeque<Peer>();
            when(fixture.commands.execute("test1")).thenAnswer(call -> {
                original.disconnect();
                var next = fixture.connect();
                next.send(2);
                assertEquals("busy", next.status());
                assertTrue(fixture.tasks.isEmpty());
                reconnected.add(next);
                return new SudoCommands.Result("ok");
            });
            original.send(1);
            fixture.tasks.remove().run();

            var next = reconnected.remove();
            next.send(3);
            fixture.tasks.remove().run();
            assertEquals("ok", next.status());
            verify(fixture.commands).execute("test1");
            verify(fixture.commands).execute("test3");
            verifyNoMoreInteractions(fixture.commands);
            assertTrue(original.replies.isEmpty());
        }
    }

    private static final class ChannelFixture implements AutoCloseable {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final SudoCommands commands = mock(SudoCommands.class);
        final SudoChannel channel;

        ChannelFixture() {
            when(commands.execute(anyString())).thenReturn(new SudoCommands.Result("ok"));
            channel = new SudoChannel(commands, tasks::add, mock(Logger.class));
        }

        Peer connect() {
            var peer = new Peer(channel);
            channel.attach(peer);
            return peer;
        }

        @Override
        public void close() {
            channel.close();
        }
    }

    private static final class Peer implements SudoChannel.Connection {
        final SudoChannel channel;
        final ArrayDeque<String> replies = new ArrayDeque<>();
        boolean open = true;

        Peer(SudoChannel channel) {
            this.channel = channel;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void sendSudoResult(String message) {
            replies.add(message);
        }

        void send(int id) {
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(25).toMillis();
            var message = new JsonObject();
            message.addProperty("id", "%032x".formatted(id));
            message.addProperty("command", "test%d".formatted(id));
            message.addProperty("deadline_ms", deadline);
            channel.receive(this, message);
        }

        String status() {
            return JsonParser.parseString(replies.remove()).getAsJsonObject().get("status").getAsString();
        }

        void disconnect() {
            open = false;
            channel.detach(this);
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
