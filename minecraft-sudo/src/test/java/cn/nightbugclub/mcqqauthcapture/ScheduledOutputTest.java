package cn.nightbugclub.mcqqauthcapture;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledOutputTest {
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
