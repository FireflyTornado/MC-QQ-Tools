package cn.nightbugclub.mcqqauthcapture;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Opt-in output attribution for work submitted while a command or its tracked task is running. */
public final class CommandOutputContext {
    private CommandOutputContext() {}

    public static Task wrap(Runnable action) {
        return new Task(Objects.requireNonNull(action));
    }

    public static <T> Callback<T> wrap(Consumer<T> action) {
        return new Callback<>(Objects.requireNonNull(action));
    }

    /** Submits a supplier with the current request and releases it on rejection or cancellation. */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> action, Executor executor) {
        Objects.requireNonNull(action);
        Objects.requireNonNull(executor);
        return registerStage((event, settled) -> CompletableFuture.supplyAsync(
                () -> ScheduledOutput.callStage(event, action, settled),
                ExecutorOutput.observeExecutor(event, executor)));
    }

    /** Runs the completion callback with the request that registered it. */
    public static <T> CompletionStage<T> whenComplete(CompletionStage<T> stage,
                                                      BiConsumer<? super T, ? super Throwable> callback) {
        Objects.requireNonNull(stage);
        Objects.requireNonNull(callback);
        return registerStage((event, settled) -> stage.whenComplete((value, failure) ->
                ScheduledOutput.runStageCompletion(event, () -> callback.accept(value, failure), settled, failure)));
    }

    /** Runs the completion callback on the supplied executor with the request that registered it. */
    public static <T> CompletionStage<T> whenCompleteAsync(CompletionStage<T> stage,
                                                           BiConsumer<? super T, ? super Throwable> callback,
                                                           Executor executor) {
        Objects.requireNonNull(stage);
        Objects.requireNonNull(callback);
        Objects.requireNonNull(executor);
        return registerStage((event, settled) -> stage.whenCompleteAsync((value, failure) ->
                ScheduledOutput.runStageCompletion(event, () -> callback.accept(value, failure), settled, failure),
                ExecutorOutput.observeExecutor(event, executor)));
    }

    /** Keeps the current request open until the stage settles. Returns the original stage. */
    public static <S extends CompletionStage<?>> S track(S stage) {
        Objects.requireNonNull(stage);
        return registerStage((event, settled) -> stage);
    }

    @FunctionalInterface
    interface StageRegistration<S, E extends Throwable> {
        S start(Object event, CompletableFuture<Throwable> settled) throws E;
    }

    static <S extends CompletionStage<?>, E extends Throwable> S registerStage(StageRegistration<S, E> start) throws E {
        var owner = OutputCapture.scheduledOutput();
        var event = new Object();
        var settled = new CompletableFuture<Throwable>();
        boolean tracked = ScheduledOutput.registerTask(event);
        S stage;
        try {
            stage = start.start(event, settled);
        } catch (Throwable failure) {
            if (tracked) {
                completeStage(event, settled, failure);
            }
            throw failure;
        }
        if (tracked) {
            observeStage(stage, owner, event, settled);
        }
        return stage;
    }

    private static void observeStage(CompletionStage<?> stage, ScheduledOutput owner, Object event,
                                     CompletableFuture<Throwable> settled) {
        BiConsumer<Object, Throwable> observer = (value, failure) -> completeStage(event, settled, failure);
        try {
            stage.whenComplete(observer);
        } catch (RuntimeException | LinkageError failure) {
            try {
                CompletableFuture<?> future = stage.toCompletableFuture();
                // If the stage rejects whenComplete itself, try a standard view of its result.
                (future == stage ? future.copy() : future).whenComplete(observer);
            } catch (RuntimeException | LinkageError unsupported) {
                if (!settled.isDone()) {
                    owner.output().appendFollowupLine("无法观察后台阶段是否完成，输出采集可能不完整。");
                    completeStage(event, settled, failure);
                }
            }
        }
    }

    private static void completeStage(Object event, CompletableFuture<Throwable> settled, Throwable failure) {
        ScheduledOutput.completeTask(event, failure);
        settled.complete(failure);
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
