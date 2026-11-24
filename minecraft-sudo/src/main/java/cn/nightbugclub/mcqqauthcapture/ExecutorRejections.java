package cn.nightbugclub.mcqqauthcapture;

import java.util.List;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

final class ExecutorRejections {
    private static final Object HANDLER_LOCK = new Object();

    private ExecutorRejections() {}

    static void observe(ThreadPoolExecutor pool) {
        synchronized (HANDLER_LOCK) {
            var original = pool.getRejectedExecutionHandler();
            var observed = observe(original);
            if (observed != original) {
                pool.setRejectedExecutionHandler(observed);
            }
        }
    }

    static void set(ThreadPoolExecutor pool, RejectedExecutionHandler handler) {
        synchronized (HANDLER_LOCK) {
            pool.setRejectedExecutionHandler(observe(handler));
        }
    }

    private static RejectedExecutionHandler observe(RejectedExecutionHandler handler) {
        Class<?> type = handler == null ? null : handler.getClass();
        if (type == ThreadPoolExecutor.DiscardPolicy.class) {
            return new Discard(handler);
        }
        if (type == ThreadPoolExecutor.DiscardOldestPolicy.class) {
            return new DiscardOldest(handler);
        }
        if (type == ThreadPoolExecutor.CallerRunsPolicy.class) {
            return new CallerRuns(handler);
        }
        return handler;
    }

    static RejectedExecutionHandler original(RejectedExecutionHandler handler) {
        return handler instanceof Observed observed ? observed.original() : handler;
    }

    private interface Observed {
        RejectedExecutionHandler original();
    }

    private static final class Discard extends ThreadPoolExecutor.DiscardPolicy implements Observed {
        private final RejectedExecutionHandler original;

        private Discard(RejectedExecutionHandler original) {
            this.original = original;
        }

        @Override
        public RejectedExecutionHandler original() {
            return original;
        }

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
            discarded(task, pool);
        }
    }

    private static final class DiscardOldest extends ThreadPoolExecutor.DiscardOldestPolicy implements Observed {
        private final RejectedExecutionHandler original;

        private DiscardOldest(RejectedExecutionHandler original) {
            this.original = original;
        }

        @Override
        public RejectedExecutionHandler original() {
            return original;
        }

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
            if (pool.isShutdown()) {
                discarded(task, pool);
                return;
            }
            discarded(pool.getQueue().poll(), pool);
            pool.execute(task);
        }
    }

    private static final class CallerRuns extends ThreadPoolExecutor.CallerRunsPolicy implements Observed {
        private final RejectedExecutionHandler original;

        private CallerRuns(RejectedExecutionHandler original) {
            this.original = original;
        }

        @Override
        public RejectedExecutionHandler original() {
            return original;
        }

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
            if (pool.isShutdown()) {
                discarded(task, pool);
            } else {
                task.run();
            }
        }
    }

    private static void discarded(Runnable task, ThreadPoolExecutor pool) {
        if (task != null) {
            ScheduledOutput.discardQueuedWork(pool, pool.getQueue(), List.of(task));
        }
    }
}
