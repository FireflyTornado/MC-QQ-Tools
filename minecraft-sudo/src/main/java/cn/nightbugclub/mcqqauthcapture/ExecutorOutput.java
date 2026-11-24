package cn.nightbugclub.mcqqauthcapture;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** Entry points used by the Forge transformer. */
public final class ExecutorOutput {
    private ExecutorOutput() {}

    public static void execute(Executor executor, Runnable task) {
        // A plain ThreadPoolExecutor accepts the original Runnable in remove().
        // ScheduledThreadPoolExecutor already queues a ScheduledFutureTask, even for execute().
        if (executor instanceof ScheduledExecutorService scheduler && standardScheduler(scheduler)
                && OutputCapture.scheduledOutput() != null && task != null
                && !(task instanceof CommandOutputContext.Task)) {
            track(scheduler, task, wrapped -> scheduler.schedule(wrapped, 0, TimeUnit.NANOSECONDS));
        } else {
            executor.execute(task);
        }
    }

    public static Future<?> submit(ExecutorService executor, Runnable task) {
        return trackRunnable(executor, task, executor::submit);
    }

    public static <T> Future<T> submit(ExecutorService executor, Runnable task, T result) {
        return trackRunnable(executor, task, wrapped -> executor.submit(wrapped, result));
    }

    public static <T> Future<T> submit(ExecutorService executor, Callable<T> task) {
        return trackCallable(executor, task, executor::submit);
    }

    public static ScheduledFuture<?> schedule(ScheduledExecutorService executor, Runnable task, long delay, TimeUnit unit) {
        return trackRunnable(executor, task, wrapped -> executor.schedule(wrapped, delay, unit));
    }

    public static <T> ScheduledFuture<T> schedule(ScheduledExecutorService executor, Callable<T> task,
                                                   long delay, TimeUnit unit) {
        return trackCallable(executor, task, wrapped -> executor.schedule(wrapped, delay, unit));
    }

    public static List<Runnable> shutdownNow(ExecutorService executor) {
        List<Runnable> drained = executor.shutdownNow();
        if (standardPool(executor) && !drained.isEmpty()) {
            ScheduledOutput.observeShutdownDrain(executor, drained);
        }
        return drained;
    }

    static Executor observeExecutor(Object event, Executor executor) {
        if (!(executor instanceof ExecutorService pool) || !standardPool(pool) || !ScheduledOutput.hasPendingEvent(event)) {
            return executor;
        }
        return task -> {
            if (!ScheduledOutput.hasPendingEvent(event)) {
                pool.execute(task);
            } else if (pool instanceof ScheduledExecutorService scheduler) {
                observeSubmission(event, pool, () -> scheduler.schedule(task, 0, TimeUnit.NANOSECONDS));
            } else {
                // A CompletableFuture worker's Future state need not describe its Runnable callback.
                ScheduledOutput.observeQueuedWork(event, pool, task, null);
                pool.execute(task);
            }
        };
    }

    private static <F extends Future<?>> F trackRunnable(ExecutorService executor, Runnable task,
                                                         Function<Runnable, F> submit) {
        if (OutputCapture.scheduledOutput() == null || task == null
                || task instanceof CommandOutputContext.Task || !standardPool(executor)) {
            return submit.apply(task);
        }
        return track(executor, task, submit);
    }

    private static <F extends Future<?>> F track(ExecutorService executor, Runnable task, Function<Runnable, F> submit) {
        var event = new Object();
        return observe(event, executor, () -> submit.apply(() -> ScheduledOutput.run(event, task)));
    }

    private static <T, F extends Future<T>> F trackCallable(ExecutorService executor, Callable<T> task,
                                                            Function<Callable<T>, F> submit) {
        if (OutputCapture.scheduledOutput() == null || task == null || !standardPool(executor)) {
            return submit.apply(task);
        }
        var event = new Object();
        return observe(event, executor, () -> submit.apply(() -> ScheduledOutput.callFuture(event, task)));
    }

    private static <F extends Future<?>> F observe(Object event, ExecutorService executor, Supplier<F> submit) {
        boolean tracked = ScheduledOutput.registerTask(event);
        try {
            return tracked ? observeSubmission(event, executor, submit) : submit.get();
        } catch (RuntimeException | Error failure) {
            if (tracked) {
                ScheduledOutput.completeTask(event, failure);
            }
            throw failure;
        }
    }

    private static <F extends Future<?>> F observeSubmission(Object event, ExecutorService executor, Supplier<F> submit) {
        ScheduledOutput.beginQueueSubmission(event, executor);
        try {
            F future = submit.get();
            ScheduledOutput.observeQueuedWork(event, executor, future, future);
            return future;
        } finally {
            ScheduledOutput.endQueueSubmission(event);
        }
    }

    private static boolean standardPool(ExecutorService executor) {
        return executor.getClass() == ThreadPoolExecutor.class || standardScheduler(executor)
                || jdkPool(executor, "java.util.concurrent.Executors$FinalizableDelegatedExecutorService");
    }

    private static boolean standardScheduler(ExecutorService executor) {
        return executor.getClass() == ScheduledThreadPoolExecutor.class
                || jdkPool(executor, "java.util.concurrent.Executors$DelegatedScheduledExecutorService");
    }

    private static boolean jdkPool(ExecutorService executor, String expectedClass) {
        var type = executor.getClass();
        return type.getModule() == Object.class.getModule() && type.getName().equals(expectedClass);
    }
}
