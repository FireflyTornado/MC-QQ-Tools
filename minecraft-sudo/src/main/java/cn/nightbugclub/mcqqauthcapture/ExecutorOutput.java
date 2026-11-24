package cn.nightbugclub.mcqqauthcapture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Entry points used by the Forge transformer. */
public final class ExecutorOutput {
    private static final Runnable TERMINATION_CHECK = () -> {};

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

    public static boolean remove(Collection<?> collection, Object task) {
        boolean removed = collection.remove(task);
        if (removed && task != null && collection instanceof BlockingQueue<?> queue) {
            ScheduledOutput.discardQueuedWork(null, queue, List.of(task));
        }
        return removed;
    }

    public static void clear(Collection<?> collection) {
        if (!(collection instanceof BlockingQueue<?> queue) || !standardQueue(queue)) {
            collection.clear();
            return;
        }
        Class<?> type = queue.getClass();
        if (type == PriorityBlockingQueue.class || delayedWorkQueue(type)) {
            // drainTo cannot remove unexpired work. Priority queues can clear atomically
            // through their native bulk removal without reordering the remaining tasks.
            removeIf(queue, task -> true);
            return;
        }
        var removed = new ArrayList<>();
        queue.drainTo(removed);
        ScheduledOutput.discardQueuedWork(null, queue, removed);
    }

    public static <T> boolean removeIf(Collection<T> collection, Predicate<? super T> filter) {
        if (!(collection instanceof BlockingQueue<?> queue) || !standardQueue(queue)) {
            return collection.removeIf(filter);
        }
        Objects.requireNonNull(filter);
        var removed = new ArrayList<T>();
        if (queue.getClass() == PriorityBlockingQueue.class) {
            // PriorityBlockingQueue selects and removes under one lock.
            boolean changed = collection.removeIf(task -> {
                if (!filter.test(task)) {
                    return false;
                }
                removed.add(task);
                return true;
            });
            if (changed) {
                ScheduledOutput.discardQueuedWork(null, queue, removed);
            }
            return changed;
        }
        try {
            // The supported queues use the lookup object's equals method. Select the
            // exact entry observed by the predicate and report only successful removals.
            for (T task : new ArrayList<>(collection)) {
                if (filter.test(task) && queue.remove(removalKey(task))) {
                    removed.add(task);
                }
            }
        } finally {
            ScheduledOutput.discardQueuedWork(null, queue, removed);
        }
        return !removed.isEmpty();
    }

    public static void purge(ThreadPoolExecutor pool) {
        if (!standardPool(pool)) {
            pool.purge();
            return;
        }
        var queue = pool.getQueue();
        try {
            for (Runnable task : queue) {
                purgeCancelled(pool, queue, task);
            }
        } catch (ConcurrentModificationException concurrent) {
            // Match the JDK purge fallback for queues with fail-fast iterators.
            for (Runnable task : queue.toArray(Runnable[]::new)) {
                purgeCancelled(pool, queue, task);
            }
        }
        if (pool.isShutdown() && queue.isEmpty()) {
            // Let the JDK recheck termination even when there was nothing to remove.
            pool.remove(TERMINATION_CHECK);
        }
    }

    private static void purgeCancelled(ThreadPoolExecutor pool, BlockingQueue<Runnable> queue, Runnable task) {
        if (task instanceof Future<?> future && future.isCancelled()) {
            var key = standardQueue(queue) ? removalKey(task) : task;
            if (pool.remove(key)) {
                ScheduledOutput.discardQueuedWork(pool, queue, List.of(task));
            }
        }
    }

    private static boolean standardQueue(BlockingQueue<?> queue) {
        Class<?> type = queue.getClass();
        return type == ArrayBlockingQueue.class || type == LinkedBlockingQueue.class || type == LinkedBlockingDeque.class
                || type == LinkedTransferQueue.class || type == PriorityBlockingQueue.class || delayedWorkQueue(type);
    }

    private static boolean delayedWorkQueue(Class<?> type) {
        return type.getEnclosingClass() == ScheduledThreadPoolExecutor.class
                && type.getSimpleName().equals("DelayedWorkQueue");
    }

    private static Runnable removalKey(Object task) {
        Class<?> type = task.getClass();
        // Native future tasks use identity equality, and the delayed queue can find
        // its own tasks by heap index when given the original instance.
        if (type == FutureTask.class || type.getEnclosingClass() == ScheduledThreadPoolExecutor.class
                && type.getSimpleName().equals("ScheduledFutureTask")) {
            return (Runnable) task;
        }
        return new QueueEntry(task);
    }

    private record QueueEntry(Object task) implements Runnable {
        @Override
        public void run() {
            throw new AssertionError("Queue lookup key must never be executed");
        }

        @Override
        public boolean equals(Object entry) {
            return entry == this || entry == task;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(task);
        }
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
