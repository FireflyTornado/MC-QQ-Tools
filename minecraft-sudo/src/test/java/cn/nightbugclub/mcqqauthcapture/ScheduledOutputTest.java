package cn.nightbugclub.mcqqauthcapture;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledOutputTest {
    @Test
    void initialSnapshotDoesNotSplitAComponentBeingWrittenOnAnotherThread() throws Exception {
        var output = new CommandOutput();
        var firstPartWritten = new CountDownLatch(1);
        var snapshotStarted = new CountDownLatch(1);
        var writeRest = new CompletableFuture<Void>();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var writer = workers.submit(() -> output.append(buffer -> {
                buffer.append("第一段");
                firstPartWritten.countDown();
                writeRest.join();
                buffer.append("第二段\n");
            }));
            assertTrue(firstPartWritten.await(2, TimeUnit.SECONDS));
            var initial = workers.submit(() -> {
                snapshotStarted.countDown();
                return output.finishInitial();
            });
            assertTrue(snapshotStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> initial.get(100, TimeUnit.MILLISECONDS));
            writeRest.complete(null);
            writer.get(2, TimeUnit.SECONDS);

            assertEquals(new BoundedCommandOutput.Snapshot("第一段第二段\n", false), initial.get(2, TimeUnit.SECONDS));
            output.appendLine("下一条消息");
            assertEquals(new BoundedCommandOutput.Snapshot("下一条消息\n", false), output.finishFollowup());
            output.append(ignored -> fail("A retained sender cannot write to a closed session"));
        } finally {
            writeRest.complete(null);
            workers.shutdownNow();
        }
    }

    @Test
    void capturesActualEventsAndTheirNestedSchedulesWithoutMixingRequests() throws Exception {
        var scheduled = new ScheduledOutput();
        var initial = new ArrayList<String>();
        var log = LogManager.getLogger("test.schedule");
        Object event = new Object();
        Object nested = new Object();
        var close = OutputCapture.begin(initial::add, scheduled);
        ScheduledOutput.enqueue(event);
        log.error("预约成功");
        close.get().join();
        assertTrue(scheduled.seal());
        var result = scheduled.result();
        assertFalse(result.isDone());
        assertEquals(List.of("预约成功"), initial);

        var unrelated = new ArrayList<String>();
        var closeUnrelated = OutputCapture.begin(unrelated::add);
        ScheduledOutput.run(new Object(), () -> log.error("无关函数"));
        ScheduledOutput.run(event, () -> {
            log.error("后续输出");
            ScheduledOutput.enqueue(nested);
        });
        assertFalse(result.isDone());
        ScheduledOutput.run(nested, () -> log.error("嵌套函数"));
        log.error("其他指令");
        closeUnrelated.get().join();
        assertEquals(List.of("其他指令"), unrelated);
        assertEquals(new SudoCommands.Result("ok", "后续输出\n嵌套函数\n"),
                result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void completesCancelledEventsAndPreservesVanillaExecutionWhenAbandoned() {
        var scheduled = new ScheduledOutput();
        Object cancelled = new Object();
        var close = OutputCapture.begin(ignored -> {}, scheduled);
        ScheduledOutput.enqueue(cancelled);
        close.get().join();
        assertTrue(scheduled.seal());
        ScheduledOutput.cancel(cancelled);
        assertEquals("cancelled", scheduled.result().join().status());

        var disconnected = new ScheduledOutput();
        Object stillScheduled = new Object();
        close = OutputCapture.begin(ignored -> {}, disconnected);
        ScheduledOutput.enqueue(stillScheduled);
        close.get().join();
        assertTrue(disconnected.seal());
        disconnected.abandon();
        var calls = new ArrayList<String>();
        ScheduledOutput.run(stillScheduled, () -> calls.add("vanilla callback"));
        assertEquals(List.of("vanilla callback"), calls);
        assertTrue(disconnected.result().isCancelled());
    }
}
