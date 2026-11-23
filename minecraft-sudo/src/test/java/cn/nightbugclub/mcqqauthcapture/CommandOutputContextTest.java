package cn.nightbugclub.mcqqauthcapture;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CommandOutputContextTest {
    @Test
    void attributesWrappedWorkerAndCallbackToTheSubmittingRequest() throws Exception {
        var output = new ScheduledOutput();
        var log = LogManager.getLogger("test.context");
        var close = OutputCapture.begin(ignored -> {}, output);
        var task = CommandOutputContext.wrap(() -> log.error("自建线程"));
        var callback = CommandOutputContext.<String>wrap(value -> log.error(value));
        close.get().join();
        assertTrue(output.seal());

        CompletableFuture.runAsync(task).get(2, TimeUnit.SECONDS);
        assertFalse(output.result().isDone());
        CompletableFuture.runAsync(() -> callback.accept("插件回调")).get(2, TimeUnit.SECONDS);
        assertEquals(new SudoCommands.Result("ok", "自建线程\n插件回调\n"), output.result().join());
    }

    @Test
    void releasesRejectedWorkWithoutChangingTheOriginalAction() {
        var output = new ScheduledOutput();
        var calls = new ArrayList<String>();
        var close = OutputCapture.begin(ignored -> {}, output);
        var task = CommandOutputContext.wrap(() -> calls.add("original"));
        close.get().join();
        assertTrue(output.seal());

        task.close();
        assertEquals("cancelled", output.result().join().status());
        task.run();
        assertEquals(List.of("original"), calls);
    }
}
