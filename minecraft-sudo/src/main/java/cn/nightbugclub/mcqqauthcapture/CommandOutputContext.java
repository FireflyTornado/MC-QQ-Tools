package cn.nightbugclub.mcqqauthcapture;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** Keeps the current request open until the stage settles or the output window expires. */
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
        if (attachObserver(stage, observer) || settled.isDone()) {
            return;
        }

        Future<?> pollable = stage instanceof Future<?> future ? future : null;
        try {
            CompletableFuture<?> view = stage.toCompletableFuture();
            if (view != null) {
                if (pollable == null) {
                    pollable = view;
                }
                // If the stage rejects whenComplete itself, try a standard view of its result.
                if (attachObserver(view == stage ? view.copy() : view, observer) || settled.isDone()) {
                    return;
                }
            }
        } catch (RuntimeException | LinkageError | AssertionError unsupported) {
            // The original Future, if available, can still report its outcome.
        }
        if (pollable != null) {
            pollStage(pollable, new WeakReference<>(owner), event, settled);
        } else {
            reportUnobservableStage(owner, settled);
        }
    }

    private static boolean attachObserver(CompletionStage<?> stage, BiConsumer<Object, Throwable> observer) {
        try {
            stage.whenComplete(observer);
            return true;
        } catch (RuntimeException | LinkageError | AssertionError unsupported) {
            return false;
        }
    }

    private static void pollStage(Future<?> future, WeakReference<ScheduledOutput> request, Object event,
                                  CompletableFuture<Throwable> settled) {
        ScheduledOutput.schedulePoll(() -> {
            var owner = request.get();
            if (owner == null || owner.result().isDone() || settled.isDone()) {
                return;
            }
            Throwable outcome;
            try {
                future.get(0, TimeUnit.NANOSECONDS);
                outcome = null;
            } catch (TimeoutException waiting) {
                pollStage(future, request, event, settled);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                pollStage(future, request, event, settled);
                return;
            } catch (ExecutionException failed) {
                outcome = failed.getCause() == null ? failed : failed.getCause();
            } catch (CancellationException cancelled) {
                outcome = cancelled;
            } catch (RuntimeException | LinkageError | AssertionError unsupported) {
                reportUnobservableStage(owner, settled);
                return;
            }
            completeStage(event, settled, outcome);
        });
    }

    private static void reportUnobservableStage(ScheduledOutput owner, CompletableFuture<Throwable> settled) {
        if (!settled.isDone() && !owner.result().isDone()) {
            owner.output().appendFollowupLine("无法观察后台阶段是否完成，输出采集可能不完整。");
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
