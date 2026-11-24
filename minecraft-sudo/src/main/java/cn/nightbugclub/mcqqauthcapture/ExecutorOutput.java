package cn.nightbugclub.mcqqauthcapture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
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
        if (!drained.isEmpty()) {
            ScheduledOutput.discardQueuedWork(executor, queue(executor), drained);
        }
        return drained;
    }

    public static boolean remove(ThreadPoolExecutor executor, Runnable task) {
        boolean removed = executor.remove(task);
        if (removed && task != null) {
            ScheduledOutput.discardQueuedWork(executor, queue(executor), List.of(task));
        }
        return removed;
    }

    public static boolean remove(BlockingQueue<?> queue, Object task) {
        boolean removed = queue.remove(task);
        if (removed && task != null) {
            ScheduledOutput.discardQueuedWork(null, queue, List.of(task));
        }
        return removed;
    }

    public static void clear(BlockingQueue<?> queue) {
        Class<?> type = queue.getClass();
        // These queues drain all elements under the same lock used by their normal removal operations.
        if ((type == ArrayBlockingQueue.class || type == LinkedBlockingQueue.class || type == LinkedBlockingDeque.class)
                && ScheduledOutput.watchesQueue(queue)) {
            var removed = new ArrayList<>();
            queue.drainTo(removed);
            ScheduledOutput.discardQueuedWork(null, queue, removed);
        } else {
            queue.clear();
        }
    }

    public static void purge(ThreadPoolExecutor pool) {
        if (standardPool(pool)) {
            for (Future<?> future : ScheduledOutput.queuedFutures(pool)) {
                if (future instanceof Runnable task && future.isCancelled()) {
                    remove(pool, task);
                }
            }
        }
        pool.purge();
    }

    public static RejectedExecutionHandler getRejectedExecutionHandler(ThreadPoolExecutor pool) {
        return ExecutorRejections.original(pool.getRejectedExecutionHandler());
    }

    public static void setRejectedExecutionHandler(ThreadPoolExecutor pool, RejectedExecutionHandler handler) {
        if (standardPool(pool)) {
            ExecutorRejections.set(pool, handler);
        } else {
            pool.setRejectedExecutionHandler(handler);
        }
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
                observeRejections(pool);
                observeQueuedWork(event, pool, task, null);
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
        observeRejections(executor);
        ScheduledOutput.beginQueueSubmission(event, executor, queue(executor));
        try {
            F future = submit.get();
            observeQueuedWork(event, executor, future, future);
            return future;
        } finally {
            ScheduledOutput.endQueueSubmission(event);
        }
    }

    private static void observeRejections(ExecutorService executor) {
        if (executor instanceof ThreadPoolExecutor pool) {
            ExecutorRejections.observe(pool);
        }
    }

    private static void observeQueuedWork(Object event, ExecutorService executor, Object task, Future<?> future) {
        ScheduledOutput.observeQueuedWork(event, new ScheduledOutput.QueuedWork(executor, queue(executor), task, future));
    }

    private static BlockingQueue<?> queue(ExecutorService executor) {
        return executor instanceof ThreadPoolExecutor pool && standardPool(pool) ? pool.getQueue() : null;
    }

    static boolean standardRejection(ExecutorService executor) {
        if (!(executor instanceof ThreadPoolExecutor pool)) {
            return standardPool(executor);
        }
        Class<?> handler = getRejectedExecutionHandler(pool).getClass();
        return handler == ThreadPoolExecutor.AbortPolicy.class || handler == ThreadPoolExecutor.CallerRunsPolicy.class
                || handler == ThreadPoolExecutor.DiscardPolicy.class || handler == ThreadPoolExecutor.DiscardOldestPolicy.class;
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
