package cn.nightbugclub.mcqqauthcapture;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
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

/** Entry points used by the optional Forge transformer. */
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
            var tasks = new IdentityHashMap<Runnable, Boolean>();
            drained.forEach(task -> tasks.put(task, true));
            ScheduledOutput.discardQueuedWork(work -> work.executor() == executor && tasks.containsKey(work.task()));
        }
        return drained;
    }

    public static boolean remove(ThreadPoolExecutor executor, Runnable task) {
        boolean removed = executor.remove(task);
        if (removed) {
            ScheduledOutput.discardQueuedWork(work -> work.executor() == executor && work.task() == task);
        }
        return removed;
    }

    public static boolean remove(BlockingQueue<?> queue, Object task) {
        boolean removed = queue.remove(task);
        if (removed) {
            ScheduledOutput.discardQueuedWork(work -> work.queue() == queue && work.task() == task);
        }
        return removed;
    }

    static Executor observeExecutor(Object event, Executor executor) {
        if (!(executor instanceof ExecutorService pool) || !standardPool(pool) || !standardRejection(pool)) {
            return executor;
        }
        return task -> {
            boolean shutDown = standardRejection(pool) && pool.isShutdown();
            if (pool instanceof ScheduledExecutorService scheduler) {
                var future = scheduler.schedule(task, 0, TimeUnit.NANOSECONDS);
                observeQueuedWork(event, pool, future, future);
            } else {
                observeQueuedWork(event, pool, task, null);
                pool.execute(task);
            }
            if (shutDown) {
                ScheduledOutput.cancel(event);
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
            boolean shutDown = tracked && standardRejection(executor) && executor.isShutdown();
            F future = submit.get();
            if (tracked) {
                if (shutDown) {
                    ScheduledOutput.cancel(event);
                } else {
                    observeQueuedWork(event, executor, future, future);
                }
            }
            return future;
        } catch (RuntimeException | Error failure) {
            if (tracked) {
                ScheduledOutput.completeTask(event, failure);
            }
            throw failure;
        }
    }

    private static void observeQueuedWork(Object event, ExecutorService executor, Object task, Future<?> future) {
        BlockingQueue<?> queue = executor instanceof ThreadPoolExecutor pool ? pool.getQueue() : null;
        ScheduledOutput.observeQueuedWork(event, new ScheduledOutput.QueuedWork(executor, queue, task, future));
    }

    static boolean standardRejection(ExecutorService executor) {
        if (!(executor instanceof ThreadPoolExecutor pool)) {
            return standardPool(executor);
        }
        Class<?> handler = pool.getRejectedExecutionHandler().getClass();
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
