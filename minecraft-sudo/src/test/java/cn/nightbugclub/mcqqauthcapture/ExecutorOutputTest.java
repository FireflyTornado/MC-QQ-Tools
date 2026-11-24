package cn.nightbugclub.mcqqauthcapture;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorOutputTest {
    @Test
    void capturesScheduledExecuteAndLeavesUnrelatedWorkOut() throws Exception {
        var pool = new ScheduledThreadPoolExecutor(1);
        var log = LogManager.getLogger("test.executor");
        var release = new CountDownLatch(1);
        try {
            var request = new ScheduledOutput();
            var finish = OutputCapture.begin(line -> fail("unexpected initial log: " + line), request);
            ExecutorOutput.execute(pool, () -> {
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    log.error("requested worker output");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    fail(failure);
                }
            });
            assertTrue(finish.get().join());
            assertTrue(request.seal());
            log.error("unrelated main log");
            release.countDown();
            pool.submit(() -> log.error("unrelated worker log")).get(5, TimeUnit.SECONDS);
            assertEquals(new SudoCommands.Result("ok", "requested worker output\n"),
                    request.result().get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void submitPreservesResultsAndExceptionAndSettlesTheRequest() throws Exception {
        var pool = Executors.newFixedThreadPool(1);
        var log = LogManager.getLogger("test.executor");
        try {
            var request = new ScheduledOutput();
            var finish = OutputCapture.begin(new ArrayList<String>()::add, request);
            var success = ExecutorOutput.submit(pool, () -> log.error("submit output"), 42);
            var failure = new IOException("task failed");
            var broken = ExecutorOutput.submit(pool, (Callable<Integer>) () -> { throw failure; });
            assertTrue(finish.get().join());
            assertTrue(request.seal());
            assertEquals(42, success.get(5, TimeUnit.SECONDS));
            assertSame(failure, assertThrows(ExecutionException.class, () -> broken.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(new SudoCommands.Result("partial", "submit output\n"),
                    request.result().get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancellationAndRejectionReleaseTrackedWork() throws Exception {
        var pool = new ScheduledThreadPoolExecutor(1);
        try {
            var cancelled = new ScheduledOutput();
            var finish = OutputCapture.begin(line -> fail(line), cancelled);
            var future = ExecutorOutput.schedule(pool, () -> fail("cancelled task ran"), 1, TimeUnit.HOURS);
            assertTrue(finish.get().join());
            assertTrue(cancelled.seal());
            assertTrue(future.cancel(false));
            assertEquals("cancelled", cancelled.result().get(5, TimeUnit.SECONDS).status());

            var rejected = new ScheduledOutput();
            pool.shutdown();
            var close = OutputCapture.begin(line -> fail(line), rejected);
            assertThrows(RejectedExecutionException.class, () -> ExecutorOutput.execute(pool, () -> fail("rejected task ran")));
            assertTrue(close.get().join());
            assertTrue(rejected.seal());
            assertEquals("partial", rejected.result().get(5, TimeUnit.SECONDS).status());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void preservesSubmissionMethodsAndTaskIdentityThroughCustomSchedulerWrapper() throws Exception {
        Runnable original = () -> {};
        var pool = new ScheduledThreadPoolExecutor(1) {
            @Override
            public void execute(Runnable task) {
                // CompletableFuture also uses this entry point for explicit executors.
                task.run();
            }

            @Override
            public Future<?> submit(Runnable task) {
                assertSame(original, task);
                return super.schedule(task, 0, TimeUnit.NANOSECONDS);
            }

            @Override
            public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
                assertSame(original, task);
                return super.schedule(task, delay, unit);
            }
        };
        try {
            var executor = Executors.unconfigurableScheduledExecutorService(pool);
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            try {
                ExecutorOutput.execute(executor, original);
                ExecutorOutput.submit(executor, original).get(5, TimeUnit.SECONDS);
                ExecutorOutput.schedule(executor, original, 0, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
                assertEquals(42, CommandOutputContext.supplyAsync(() -> 42, executor).join());
            } finally {
                assertTrue(close.get().join());
            }
            assertTrue(request.seal());
            assertEquals("ok", request.result().get(5, TimeUnit.SECONDS).status());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oneUnobservableFutureDoesNotStopCancellationPolling() throws Exception {
        var broken = new ScheduledOutput();
        var probed = new CountDownLatch(1);
        var brokenEvent = new Object();
        var close = OutputCapture.begin(line -> fail(line), broken);
        assertTrue(ScheduledOutput.registerTask(brokenEvent));
        ScheduledOutput.observeFuture(brokenEvent, new FutureTask<>(() -> null) {
            @Override
            public boolean isDone() {
                probed.countDown();
                throw new UnsupportedOperationException("cannot observe this Future");
            }
        });
        assertTrue(close.get().join());
        assertTrue(broken.seal());

        var pool = new ScheduledThreadPoolExecutor(1);
        try {
            assertTrue(probed.await(5, TimeUnit.SECONDS));
            var healthy = new ScheduledOutput();
            var finish = OutputCapture.begin(line -> fail(line), healthy);
            var future = ExecutorOutput.schedule(pool, () -> fail("cancelled task ran"), 1, TimeUnit.HOURS);
            assertTrue(finish.get().join());
            assertTrue(healthy.seal());
            assertTrue(future.cancel(false));
            assertEquals("cancelled", healthy.result().get(5, TimeUnit.SECONDS).status());
            assertFalse(broken.result().isDone());
            ScheduledOutput.run(brokenEvent, () -> {});
            assertEquals(new SudoCommands.Result("partial", "无法观察后台任务是否完成，输出采集可能不完整。\n"),
                    broken.result().get(5, TimeUnit.SECONDS));
        } finally {
            broken.abandon();
            pool.shutdownNow();
        }
    }

    @Test
    void plainPoolExecutePreservesQueuedRunnableIdentity() throws Exception {
        var pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        var busy = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                try {
                    busy.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
            var request = new ScheduledOutput();
            var finish = OutputCapture.begin(line -> fail(line), request);
            Runnable removable = () -> fail("removed task ran");
            ExecutorOutput.execute(pool, removable);
            assertTrue(pool.remove(removable));
            assertTrue(finish.get().join());
            assertFalse(request.seal());
        } finally {
            busy.countDown();
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturesWorkTransferredAfterTheOriginalExecutorTerminates(boolean stage) throws Exception {
        var original = occupiedPool(new LinkedBlockingQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        var backup = Executors.newSingleThreadExecutor();
        var request = new ScheduledOutput();
        var cancellation = new ScheduledOutput();
        try {
            var close = OutputCapture.begin(line -> fail(line), request);
            Supplier<Integer> work = () -> {
                LogManager.getLogger("test.executor").error("backup executor output");
                return 42;
            };
            Future<Integer> task = stage ? CommandOutputContext.supplyAsync(work, original)
                    : ExecutorOutput.submit(original, (Callable<Integer>) work::get);
            Runnable transferred = original.getQueue().element();
            assertTrue(close.get().join());
            assertTrue(request.seal());

            var closeCancellation = OutputCapture.begin(line -> fail(line), cancellation);
            var cancelled = ExecutorOutput.submit(original, () -> fail("explicitly cancelled task ran"));
            assertTrue(closeCancellation.get().join());
            assertTrue(cancellation.seal());

            var drained = original.shutdownNow();
            assertEquals(List.of(transferred, cancelled), drained);
            assertTrue(original.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel(false));
            // Wait for a real cancellation to be observed before the backup starts.
            assertEquals("cancelled", cancellation.result().get(3, TimeUnit.SECONDS).status());
            assertFalse(request.result().isDone());

            backup.execute(drained.get(0));
            assertEquals(42, task.get(3, TimeUnit.SECONDS));
            assertEquals(new SudoCommands.Result("ok", "backup executor output\n"),
                    request.result().get(3, TimeUnit.SECONDS));
        } finally {
            request.abandon();
            cancellation.abandon();
            original.shutdownNow();
            backup.shutdownNow();
        }
    }

    @Test
    void waitsForACopyAlreadyTakenWhenShutdownDrainsADuplicate() throws Exception {
        var dequeued = new CountDownLatch(1);
        var releaseFirst = new CompletableFuture<Void>();
        var releaseDequeued = new CompletableFuture<Void>();
        var queue = new LinkedBlockingQueue<Runnable>() {
            @Override
            public Runnable take() throws InterruptedException {
                Runnable task = super.take();
                dequeued.countDown();
                releaseDequeued.join();
                return task;
            }
        };
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue);
        try {
            pool.execute(releaseFirst::join);
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var stage = CommandOutputContext.supplyAsync(() -> {
                LogManager.getLogger("test.executor").error("dequeued duplicate output");
                return 42;
            }, pool);
            Runnable worker = queue.element();
            queue.add(worker);
            assertTrue(close.get().join());
            assertTrue(request.seal());
            releaseFirst.complete(null);
            assertTrue(dequeued.await(3, TimeUnit.SECONDS));

            assertEquals(List.of(worker), pool.shutdownNow());
            assertThrows(TimeoutException.class, () -> request.result().get(1200, TimeUnit.MILLISECONDS));
            releaseDequeued.complete(null);
            assertEquals(42, stage.get(3, TimeUnit.SECONDS));
            assertEquals(new SudoCommands.Result("ok", "dequeued duplicate output\n"),
                    request.result().get(3, TimeUnit.SECONDS));
        } finally {
            releaseFirst.complete(null);
            releaseDequeued.complete(null);
            pool.shutdownNow();
        }
    }

    @Test
    void shutdownDrainDoesNotSettleWorkSubmittedToAnotherExecutorWithTheSameQueue() throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>();
        var owner = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
        var other = new ThreadPoolExecutor(0, 1, 0, TimeUnit.SECONDS, queue);
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var future = ExecutorOutput.submit(owner, () -> 42);
            assertTrue(close.get().join());
            assertTrue(request.seal());

            var drained = other.shutdownNow();
            assertEquals(List.of(future), drained);
            assertTrue(other.isTerminated());
            assertThrows(TimeoutException.class, () -> request.result().get(1200, TimeUnit.MILLISECONDS));
            drained.get(0).run();
            assertEquals(42, future.get(2, TimeUnit.SECONDS));
            assertEquals("ok", request.result().get(2, TimeUnit.SECONDS).status());
        } finally {
            owner.shutdownNow();
            other.shutdownNow();
        }
    }

    @Test
    void nativeClearCanTransferTheTaskEvenAfterItsOriginalExecutorTerminates() throws Exception {
        var transferred = new AtomicReference<Runnable>();
        var queue = new LinkedBlockingQueue<Runnable>() {
            @Override
            public void clear() {
                transferred.set(poll());
                super.clear();
            }
        };
        var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var task = ExecutorOutput.submit(pool, () -> {
                LogManager.getLogger("test.executor").error("transferred output");
                return 42;
            });
            assertTrue(close.get().join());
            assertTrue(request.seal());

            queue.clear();
            assertSame(task, transferred.get());
            assertTrue(pool.shutdownNow().isEmpty());
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> request.result().get(1200, TimeUnit.MILLISECONDS));
            transferred.get().run();
            assertEquals(42, task.get(2, TimeUnit.SECONDS));
            assertEquals(new SudoCommands.Result("ok", "transferred output\n"),
                    request.result().get(3, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void silentDiscardKeepsOriginalFutureAndUsesTheRequestDeadline() throws Exception {
        var policy = new ThreadPoolExecutor.DiscardPolicy();
        var pool = occupiedPool(new ArrayBlockingQueue<>(1), policy);
        try {
            pool.execute(() -> {});
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var task = ExecutorOutput.submit(pool, () -> fail("discarded task ran"));
            assertTrue(close.get().join());
            assertTrue(request.seal());

            assertSame(policy, pool.getRejectedExecutionHandler());
            assertFalse(task.isDone());
            assertFalse(request.result().isDone());
            request.expire();
            assertEquals("expired", request.result().join().status());
            assertFalse(task.isDone());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void discardOldestLeavesASecondCopyAndItsCaptureIntact() throws Exception {
        var policy = new ThreadPoolExecutor.DiscardOldestPolicy();
        var queue = new ArrayBlockingQueue<Runnable>(3);
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue, policy);
        var release = new CompletableFuture<Void>();
        try {
            pool.execute(release::join);
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var stage = CommandOutputContext.supplyAsync(() -> {
                LogManager.getLogger("test.executor").error("remaining stage output");
                return 42;
            }, pool);
            var worker = queue.element();
            Runnable spacer = () -> {};
            queue.add(spacer);
            queue.add(worker);
            assertTrue(close.get().join());
            assertTrue(request.seal());

            pool.execute(() -> {});
            assertSame(policy, pool.getRejectedExecutionHandler());
            assertSame(spacer, queue.element());
            assertFalse(request.result().isDone());
            release.complete(null);
            assertEquals(42, stage.get(3, TimeUnit.SECONDS));
            assertEquals(new SudoCommands.Result("ok", "remaining stage output\n"),
                    request.result().get(3, TimeUnit.SECONDS));
        } finally {
            release.complete(null);
            pool.shutdownNow();
        }
    }

    @Test
    void cancelledInternalStageWorkerCanStillRunItsCallback() throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>();
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue);
        var release = new CompletableFuture<Void>();
        try {
            pool.execute(release::join);
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var stage = CommandOutputContext.supplyAsync(() -> 42, pool);
            var worker = assertInstanceOf(Future.class, queue.element());
            assertTrue(close.get().join());
            assertTrue(request.seal());

            assertTrue(worker.cancel(false));
            assertThrows(TimeoutException.class, () -> request.result().get(1200, TimeUnit.MILLISECONDS));
            release.complete(null);
            assertEquals(42, stage.get(3, TimeUnit.SECONDS));
            assertEquals("ok", request.result().get(3, TimeUnit.SECONDS).status());
        } finally {
            release.complete(null);
            pool.shutdownNow();
        }
    }

    private static ThreadPoolExecutor occupiedPool(BlockingQueue<Runnable> queue, RejectedExecutionHandler handler)
            throws InterruptedException {
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue, handler);
        var occupied = new CountDownLatch(1);
        pool.execute(() -> {
            occupied.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        if (!occupied.await(5, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            fail("worker did not start");
        }
        return pool;
    }
}
