package cn.nightbugclub.mcqqauthcapture;

import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
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
        var queue = queue(executor);
        if (queue != null && JdkQueueAccess.canTryTerminate()) {
            boolean removed = remove(queue, task);
            // tryTerminate may take the pool lock. Run it after releasing the queue locks.
            JdkQueueAccess.tryTerminate(executor);
            return removed;
        }
        boolean removed = executor.remove(task);
        if (removed && task != null) {
            ScheduledOutput.discardQueuedWork(executor, queue(executor), List.of(task));
        }
        return removed;
    }

    public static boolean remove(Collection<?> collection, Object task) {
        if (collection instanceof BlockingQueue<?> queue) {
            var locks = JdkQueueAccess.locks(queue);
            if (locks != null) {
                return mutateQueue(queue, locks, () -> collection.remove(task));
            }
            if (task != null && queue.getClass() == LinkedTransferQueue.class) {
                var match = new RemovalMatch(task);
                boolean removed = collection.remove(match);
                if (removed) {
                    Set<Object> discarded = Collections.newSetFromMap(new IdentityHashMap<>());
                    discarded.add(match.entry);
                    retainDiscarded(queue, discarded, queue::toArray);
                    ScheduledOutput.discardQueuedWork(null, queue, discarded);
                }
                return removed;
            }
        }
        boolean removed = collection.remove(task);
        if (removed && task != null && collection instanceof BlockingQueue<?> queue) {
            ScheduledOutput.discardQueuedWork(null, queue, List.of(task));
        }
        return removed;
    }

    public static void clear(Collection<?> collection) {
        if (!(collection instanceof BlockingQueue<?> queue)) {
            collection.clear();
            return;
        }
        var locks = JdkQueueAccess.locks(queue);
        if (locks != null) {
            mutateQueue(queue, locks, () -> {
                collection.clear();
                return null;
            });
        } else if (queue.getClass() == LinkedTransferQueue.class) {
            LinkedQueueRemoval.removeIf(queue, task -> true);
        } else {
            collection.clear();
        }
    }

    public static <T> boolean removeIf(Collection<T> collection, Predicate<? super T> filter) {
        if (!(collection instanceof BlockingQueue<?> queue) || !standardQueue(queue.getClass())) {
            return collection.removeIf(filter);
        }
        Objects.requireNonNull(filter);
        Class<?> type = queue.getClass();
        if (type == ArrayBlockingQueue.class || type == PriorityBlockingQueue.class || JdkQueueAccess.delayedQueue(type)) {
            var locks = JdkQueueAccess.locks(queue);
            if (locks == null) {
                return collection.removeIf(filter);
            }
            if (JdkQueueAccess.delayedQueue(type)) {
                return removeWithIterator(collection, queue, filter, locks);
            }
            // ArrayBlockingQueue releases its bulk lock and uses an iterator when one is active.
            Boolean bulkResult = mutateQueue(queue, locks, () -> type == ArrayBlockingQueue.class
                    && locks.hasArrayIterators(queue) ? null : collection.removeIf(filter));
            return bulkResult != null ? bulkResult : removeWithIterator(collection, queue, filter, locks);
        }
        @SuppressWarnings("unchecked") BlockingQueue<T> linked = (BlockingQueue<T>) collection;
        return LinkedQueueRemoval.removeIf(linked, filter);
    }

    private static <T> boolean removeWithIterator(Collection<T> collection, BlockingQueue<?> queue,
                                                  Predicate<? super T> filter, JdkQueueAccess.Locks locks) {
        boolean removed = false;
        for (var iterator = collection.iterator(); iterator.hasNext();) {
            if (filter.test(iterator.next())) {
                removeIteratorEntry(queue, locks, iterator);
                removed = true;
            }
        }
        return removed;
    }

    private static <T> T mutateQueue(BlockingQueue<?> queue, JdkQueueAccess.Locks locks, Supplier<T> operation) {
        Set<Object> removed = Set.of();
        locks.lock();
        try {
            // Check after acquiring the queue lock. A tracked submit may have queued work while we waited.
            if (!ScheduledOutput.watchesQueue(queue)) {
                return operation.get();
            }
            removed = Collections.newSetFromMap(new IdentityHashMap<>());
            Collections.addAll(removed, locks.snapshot(queue));
            try {
                return operation.get();
            } finally {
                retainDiscarded(queue, removed, () -> locks.snapshot(queue));
            }
        } finally {
            locks.unlock();
            ScheduledOutput.discardQueuedWork(null, queue, removed);
        }
    }

    static void retainDiscarded(BlockingQueue<?> queue, Set<Object> removed, Supplier<Object[]> snapshot) {
        if (!removed.isEmpty()) {
            ScheduledOutput.retainTrackedQueueWork(queue, removed);
            if (!removed.isEmpty()) {
                for (Object survivor : snapshot.get()) {
                    removed.remove(survivor);
                }
            }
        }
    }

    public static void purge(ThreadPoolExecutor pool) {
        if (!standardPool(pool)) {
            pool.purge();
            return;
        }
        var queue = pool.getQueue();
        var locks = JdkQueueAccess.locks(queue);
        boolean transferQueue = queue.getClass() == LinkedTransferQueue.class;
        if (locks == null && !transferQueue) {
            pool.purge();
            return;
        }
        try {
            var iterator = queue.iterator();
            while (iterator.hasNext()) {
                Runnable task = iterator.next();
                if (task instanceof Future<?> future && future.isCancelled()) {
                    if (transferQueue) {
                        remove(queue, new RemovalMatch(task, true));
                    } else {
                        removeIteratorEntry(queue, locks, iterator);
                    }
                }
            }
        } catch (ConcurrentModificationException concurrent) {
            // Match the JDK purge fallback for queues with fail-fast iterators.
            for (Object task : queue.toArray()) {
                if (task instanceof Future<?> future && future.isCancelled()) {
                    remove(queue, task);
                }
            }
        }
        if (JdkQueueAccess.canTryTerminate()) {
            JdkQueueAccess.tryTerminate(pool);
        } else if (pool.isShutdown() && queue.isEmpty()) {
            // Let the JDK recheck termination even when there was nothing to remove.
            if (standardQueue(queue.getClass())) {
                pool.remove(TERMINATION_CHECK);
            } else {
                pool.purge();
            }
        }
    }

    private static void removeIteratorEntry(BlockingQueue<?> queue, JdkQueueAccess.Locks locks, Iterator<?> iterator) {
        mutateQueue(queue, locks, () -> {
            iterator.remove();
            return null;
        });
    }

    private static boolean standardQueue(Class<?> type) {
        return type == ArrayBlockingQueue.class || type == LinkedBlockingQueue.class || type == LinkedBlockingDeque.class
                || type == LinkedTransferQueue.class || type == PriorityBlockingQueue.class || JdkQueueAccess.delayedQueue(type);
    }

    /** LinkedTransferQueue compares the supplied key before atomically removing that entry. */
    private static final class RemovalMatch {
        private final Object key;
        private final boolean identityOnly;
        private Object entry;

        RemovalMatch(Object key) {
            this(key, false);
        }

        RemovalMatch(Object key, boolean identityOnly) {
            this.key = key;
            this.identityOnly = identityOnly;
        }

        @Override
        public boolean equals(Object candidate) {
            if (candidate == this) {
                return true;
            }
            if (identityOnly ? key == candidate : key.equals(candidate)) {
                entry = candidate;
                return true;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return key.hashCode();
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
