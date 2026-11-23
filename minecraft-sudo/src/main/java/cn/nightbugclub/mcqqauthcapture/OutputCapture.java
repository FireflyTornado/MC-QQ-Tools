package cn.nightbugclub.mcqqauthcapture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class OutputCapture {
    private static final ThreadLocal<Frame> ACTIVE = new ThreadLocal<>();
    private static final CommandLog LOG = new CommandLog();
    private static final StackWalker STACK = StackWalker.getInstance();

    private OutputCapture() {}

    public static Supplier<CompletableFuture<Boolean>> begin(Consumer<String> output) {
        return begin(output, null);
    }

    static Supplier<CompletableFuture<Boolean>> begin(Consumer<String> output, ScheduledOutput scheduled) {
        attachLogCollector();
        var session = new Session(output, scheduled);
        Frame previous = ACTIVE.get();
        var frame = new Frame(session);
        ACTIVE.set(frame);
        return () -> {
            finishFrame(frame, previous, true);
            return session.completed;
        };
    }

    static ScheduledOutput scheduledOutput() {
        Frame frame = ACTIVE.get();
        return frame == null ? null : frame.session.scheduled;
    }

    static void consoleBytes(boolean error, byte[] bytes, int offset, int length, Charset charset) {
        Frame frame = ACTIVE.get();
        if (frame == null || frame.session.completed.isDone() || length == 0) {
            return;
        }
        // Console appenders may write back through System.out with follow=true.
        boolean fromLog4j = STACK.walk(stack -> stack.anyMatch(call ->
                call.getClassName().startsWith("org.apache.logging.log4j.core.appender.")));
        if (!fromLog4j) {
            frame.console(error, charset).append(frame.session, bytes, offset, length);
        }
    }

    public static <T> Consumer<T> track(Consumer<T> callback) {
        Frame frame = ACTIVE.get();
        if (frame == null || !frame.session.register()) {
            return callback;
        }
        return tracked(frame.session, (T value) -> {
            callback.accept(value);
            return null;
        })::apply;
    }

    public static <T> CompletableFuture<T> trackReload(CompletableFuture<T> reload,
                                                        Function<Throwable, ? extends T> recovery) {
        Frame frame = ACTIVE.get();
        if (frame == null || !frame.session.register()) {
            return reload.exceptionally(recovery);
        }
        var callback = tracked(frame.session, (Throwable failure) -> failure == null ? null : recovery.apply(failure));
        return reload.handle((value, failure) -> {
            T recovered = callback.apply(failure);
            return failure == null ? value : recovered;
        });
    }

    private static <T, R> Function<T, R> tracked(Session session, Function<T, R> callback) {
        var finished = new AtomicBoolean();
        return value -> {
            if (!finished.compareAndSet(false, true) || session.completed.isDone()) {
                return callback.apply(value);
            }
            Frame previous = ACTIVE.get();
            var frame = new Frame(session);
            ACTIVE.set(frame);
            boolean success = false;
            try {
                R result = callback.apply(value);
                success = true;
                return result;
            } finally {
                finishFrame(frame, previous, success);
            }
        };
    }

    public static void runQueuedTask(Runnable task) {
        Frame previous = ACTIVE.get();
        if (previous == null) {
            task.run();
            return;
        }
        // Commands such as reload can pump the game queue while dispatch is still on the stack.
        ACTIVE.remove();
        try {
            task.run();
        } finally {
            ACTIVE.set(previous);
        }
    }

    public static void broadcast(Object message, Object chatType) {
        Frame frame = ACTIVE.get();
        if (frame == null || frame.session.completed.isDone()) {
            return;
        }
        try {
            String body = VanillaOutput.broadcast(message, chatType);
            frame.broadcast = body;
            frame.session.append(body);
        } catch (ReflectiveOperationException | RuntimeException e) {
            frame.session.incomplete();
        }
    }

    public static void packet(Object connection, Object packet) {
        Frame frame = ACTIVE.get();
        if (frame == null || frame.session.completed.isDone()) {
            return;
        }
        try {
            String output = VanillaOutput.packet(connection, packet, frame.broadcast);
            if (output != null) {
                frame.session.append(output);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            frame.session.incomplete();
        }
    }

    private static void restore(Frame previous) {
        if (previous == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(previous);
        }
    }

    private static void finishFrame(Frame frame, Frame previous, boolean success) {
        try {
            frame.flushConsole();
        } finally {
            restore(previous);
            frame.session.finish(success);
        }
    }

    private static synchronized void attachLogCollector() {
        var context = (LoggerContext) LogManager.getContext(false);
        var root = context.getRootLogger();
        if (root.getAppenders().get(LOG.getName()) != LOG) {
            LOG.start();
            root.addAppender(LOG);
        }
    }

    private static final class Frame {
        final Session session;
        String broadcast;
        private ConsoleLine stdout;
        private ConsoleLine stderr;

        Frame(Session session) {
            this.session = session;
        }

        ConsoleLine console(boolean error, Charset charset) {
            if (error) {
                if (stderr == null) {
                    stderr = new ConsoleLine(charset);
                }
                return stderr;
            }
            if (stdout == null) {
                stdout = new ConsoleLine(charset);
            }
            return stdout;
        }

        void flushConsole() {
            if (stdout != null) {
                stdout.flush(session, false);
            }
            if (stderr != null) {
                stderr.flush(session, false);
            }
        }
    }

    private static final class ConsoleLine {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Charset charset;

        ConsoleLine(Charset charset) {
            this.charset = charset;
        }

        void append(Session session, byte[] input, int offset, int length) {
            for (int index = offset; index < offset + length; index++) {
                int value = input[index];
                if (value == '\n') {
                    flush(session, true);
                } else if (bytes.size() < BoundedCommandOutput.MAX_BYTES) {
                    bytes.write(value);
                } else {
                    session.incomplete();
                }
            }
        }

        void flush(Session session, boolean newline) {
            if (bytes.size() != 0 || newline) {
                String text = bytes.toString(charset);
                bytes.reset();
                session.append(newline && text.endsWith("\r") ? text.substring(0, text.length() - 1) : text);
            }
        }
    }

    private static final class Session {
        final CompletableFuture<Boolean> completed = new CompletableFuture<>();
        private final Consumer<String> output;
        private int pending = 1; // Dispatch itself owns the first slot.
        private boolean complete = true;

        private final ScheduledOutput scheduled;

        Session(Consumer<String> output, ScheduledOutput scheduled) {
            this.output = output;
            this.scheduled = scheduled;
        }

        synchronized boolean register() {
            if (completed.isDone()) {
                return false;
            }
            pending++;
            return true;
        }

        synchronized void append(String text) {
            if (!completed.isDone()) {
                output.accept(text);
            }
        }

        synchronized void incomplete() {
            complete = false;
        }

        synchronized void finish(boolean success) {
            complete &= success;
            if (--pending == 0) {
                completed.complete(complete);
            }
        }
    }

    private static final class CommandLog extends AbstractAppender {
        CommandLog() {
            super("MCQQAuthCommandOutput", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            Frame frame = ACTIVE.get();
            if (frame == null || frame.session.completed.isDone()) {
                return;
            }
            String message = event.getMessage().getFormattedMessage();
            if (frame.broadcast != null && event.getThrown() == null
                    && "net.minecraft.server.MinecraftServer".equals(event.getLoggerName())
                    && (message.equals(frame.broadcast) || message.equals("[Not Secure] " + frame.broadcast))) {
                return;
            }
            frame.session.append(message);
            if (event.getThrown() != null) {
                var stack = new StringWriter();
                event.getThrown().printStackTrace(new PrintWriter(stack));
                frame.session.append(stack.toString());
            }
        }
    }
}
