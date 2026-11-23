package cn.nightbugclub.mcqqauthcapture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class ConsoleOutputTest {
    @Test
    void keepsTheRealStreamsAndCapturesLinesWithinTheRequest() throws Exception {
        var destination = new ByteArrayOutputStream();
        var original = new PrintStream(destination, true, UTF_8);
        var stdout = ConsoleOutput.wrap(original, false, UTF_8, false);
        var stderr = ConsoleOutput.wrap(original, true, UTF_8, false);
        var captured = new ArrayList<String>();
        var close = OutputCapture.begin(captured::add);
        stdout.print("前缀");
        byte[] suffix = "正文\r\n".getBytes(UTF_8);
        stdout.write(suffix, 0, 1);
        stdout.write(suffix, 1, suffix.length - 1);
        CompletableFuture.runAsync(() -> stdout.println("无关")).get(2, TimeUnit.SECONDS);
        stderr.print("尾部");
        assertTrue(close.get().join());
        stdout.println("请求已结束");

        assertEquals(List.of("前缀正文", "尾部"), captured);
        assertEquals("前缀正文\r\n无关" + System.lineSeparator() + "尾部请求已结束" + System.lineSeparator(),
                destination.toString(UTF_8));
    }

    @Test
    void log4jFollowingTheConsoleDoesNotDuplicateTheLine() {
        PrintStream stdout = System.out;
        var buffer = new ByteArrayOutputStream();
        System.setOut(ConsoleOutput.wrap(new PrintStream(buffer, true, UTF_8), false, UTF_8, false));
        var context = (LoggerContext) LogManager.getContext(false);
        var appender = ConsoleAppender.newBuilder().setName("sudo-test-console").setFollow(true).build();
        appender.start();
        context.getRootLogger().addAppender(appender);
        try {
            var captured = new ArrayList<String>();
            var close = OutputCapture.begin(captured::add);
            LogManager.getLogger("test.console").error("one log line");
            assertTrue(close.get().join());
            assertTrue(buffer.toString(UTF_8).contains("one log line"));
            assertEquals(List.of("one log line"), captured);
        } finally {
            context.getRootLogger().removeAppender(appender);
            appender.stop();
            System.setOut(stdout);
        }
    }

    @Test
    void preservesNativePrintlnForwardingAndMirrorsTheRemainingWrites() {
        var destination = new ByteArrayOutputStream();
        var original = new PrintStream(destination, true, UTF_8) {
            @Override
            public void println(String text) {
                LogManager.getLogger("test.forwarding").error(text);
            }

            @Override
            public void println(Object value) {
                println(String.valueOf(value));
            }
        };
        var stdout = ConsoleOutput.wrap(original, false, UTF_8, true);
        var captured = new ArrayList<String>();
        var close = OutputCapture.begin(captured::add);
        stdout.println("转发日志");
        stdout.println((Object) "转发对象");
        stdout.print("直接写入");
        assertTrue(close.get().join());
        assertEquals(List.of("转发日志", "转发对象", "直接写入"), captured);
        assertEquals("直接写入", destination.toString(UTF_8));
    }
}
