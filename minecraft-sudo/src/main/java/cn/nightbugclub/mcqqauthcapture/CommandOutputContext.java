package cn.nightbugclub.mcqqauthcapture;

import java.util.Objects;
import java.util.function.Consumer;

/** Opt-in output attribution for one invocation submitted to a plugin's own worker or executor. */
public final class CommandOutputContext {
    private CommandOutputContext() {}

    public static Task wrap(Runnable action) {
        return new Task(Objects.requireNonNull(action));
    }

    public static <T> Callback<T> wrap(Consumer<T> action) {
        return new Callback<>(Objects.requireNonNull(action));
    }

    public static final class Task implements Runnable, AutoCloseable {
        private final Runnable action;

        private Task(Runnable action) {
            this.action = action;
            ScheduledOutput.enqueueTask(this);
        }

        @Override
        public void run() {
            ScheduledOutput.run(this, action);
        }

        @Override
        public void close() {
            ScheduledOutput.cancel(this);
        }
    }

    public static final class Callback<T> implements Consumer<T>, AutoCloseable {
        private final Consumer<T> action;

        private Callback(Consumer<T> action) {
            this.action = action;
            ScheduledOutput.enqueueTask(this);
        }

        @Override
        public void accept(T value) {
            ScheduledOutput.run(this, () -> action.accept(value));
        }

        @Override
        public void close() {
            ScheduledOutput.cancel(this);
        }
    }
}
