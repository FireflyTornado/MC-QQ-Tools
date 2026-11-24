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
        return registerStage(event -> CompletableFuture.supplyAsync(() -> ScheduledOutput.call(event, action),
                ExecutorOutput.observeExecutor(event, executor)));
    }

    /** Runs the completion callback with the request that registered it. */
    public static <T> CompletionStage<T> whenComplete(CompletionStage<T> stage,
                                                      BiConsumer<? super T, ? super Throwable> callback) {
        Objects.requireNonNull(stage);
        Objects.requireNonNull(callback);
        return registerStage(event -> stage.whenComplete((value, failure) ->
                ScheduledOutput.runCompletion(event, () -> callback.accept(value, failure), failure)));
    }

    /** Runs the completion callback on the supplied executor with the request that registered it. */
    public static <T> CompletionStage<T> whenCompleteAsync(CompletionStage<T> stage,
                                                           BiConsumer<? super T, ? super Throwable> callback,
                                                           Executor executor) {
        Objects.requireNonNull(stage);
        Objects.requireNonNull(callback);
        Objects.requireNonNull(executor);
        return registerStage(event -> stage.whenCompleteAsync((value, failure) ->
                ScheduledOutput.runCompletion(event, () -> callback.accept(value, failure), failure),
                ExecutorOutput.observeExecutor(event, executor)));
    }

    /** Keeps the current request open until the stage settles. Returns the original stage. */
    public static <S extends CompletionStage<?>> S track(S stage) {
        Objects.requireNonNull(stage);
        return registerStage(ignored -> stage);
    }

    @FunctionalInterface
    interface StageRegistration<S, E extends Throwable> {
        S start(Object event) throws E;
    }

    private static <S extends CompletionStage<?>> S registerStage(StageRegistration<S, RuntimeException> start) {
        return registerStage(start, null);
    }

    static <S extends CompletionStage<?>, E extends Throwable> S registerStage(StageRegistration<S, E> start,
                                                                               CompletableFuture<Throwable> settled) throws E {
        var event = new Object();
        boolean tracked = ScheduledOutput.registerTask(event);
        try {
            S stage = start.start(event);
            if (tracked) {
                stage.whenComplete((value, failure) -> completeStage(event, settled, failure));
            }
            return stage;
        } catch (Throwable failure) {
            if (tracked) {
                completeStage(event, settled, failure);
            }
            throw failure;
        }
    }

    private static void completeStage(Object event, CompletableFuture<Throwable> settled, Throwable failure) {
        ScheduledOutput.completeTask(event, failure);
        if (settled != null) {
            settled.complete(failure);
        }
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
