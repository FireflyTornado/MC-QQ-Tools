package cn.nightbugclub.mcqqauthcapture;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.ref.Reference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
        var pool = Executors.newSingleThreadScheduledExecutor();
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

    @Test
    void settlesConfirmedRemovalsWithoutCancellingTheOriginalFutures() throws Exception {
        var pool = new ScheduledThreadPoolExecutor(1);
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var first = ExecutorOutput.schedule(pool, () -> fail("removed task ran"), 1, TimeUnit.HOURS);
            var second = ExecutorOutput.schedule(pool, () -> fail("removed task ran"), 1, TimeUnit.HOURS);
            var third = ExecutorOutput.schedule(pool, () -> fail("drained task ran"), 1, TimeUnit.HOURS);
            assertTrue(close.get().join());
            assertTrue(request.seal());
            assertTrue(ExecutorOutput.remove(pool.getQueue(), first));
            assertTrue(ExecutorOutput.remove(pool, (Runnable) second));
            assertFalse(request.result().isDone());
            assertEquals(java.util.List.of(third), ExecutorOutput.shutdownNow(pool));
            assertEquals("cancelled", request.result().get(1, TimeUnit.SECONDS).status());
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            assertFalse(third.isDone());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void settlesUnobservedQueueDrainWhenExecutorTerminates() throws Exception {
        var pool = new ScheduledThreadPoolExecutor(1);
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var future = ExecutorOutput.schedule(pool, () -> fail("drained task ran"), 1, TimeUnit.HOURS);
            assertTrue(close.get().join());
            assertTrue(request.seal());
            assertEquals(java.util.List.of(future), pool.shutdownNow());
            assertFalse(future.isDone());
            assertEquals("cancelled", request.result().get(5, TimeUnit.SECONDS).status());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void settlesSaturatedDiscardsWithoutChangingTheirFuturesOrPolicies() throws Exception {
        for (RejectedExecutionHandler handler : List.of(new ThreadPoolExecutor.DiscardPolicy(),
                new ThreadPoolExecutor.DiscardOldestPolicy())) {
            var pool = occupiedPool(new ArrayBlockingQueue<>(1), handler);
            try {
                if (handler instanceof ThreadPoolExecutor.DiscardPolicy) {
                    pool.execute(() -> {});
                }
                var request = new ScheduledOutput();
                var close = OutputCapture.begin(line -> fail(line), request);
                var dropped = ExecutorOutput.submit(pool, () -> fail("discarded task ran"));
                assertTrue(close.get().join());
                assertTrue(request.seal());

                if (handler instanceof ThreadPoolExecutor.DiscardOldestPolicy) {
                    // The eviction can be caused by an unrelated native submission.
                    pool.execute(() -> {});
                }
                assertSame(handler, ExecutorOutput.getRejectedExecutionHandler(pool));
                assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
                assertFalse(dropped.isDone());
                assertEquals(1, pool.getQueue().size());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void remembersQueueRemovalWhileSubmitIsStillReturningItsFuture() throws Exception {
        var afterOffer = new AtomicReference<Consumer<Runnable>>();
        var pool = occupiedPool(queueWithOfferHook(afterOffer), new ThreadPoolExecutor.AbortPolicy());
        try {
            afterOffer.set(task -> assertTrue(CompletableFuture.supplyAsync(() ->
                    ExecutorOutput.remove(pool.getQueue(), task)).join()));
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var dropped = ExecutorOutput.submit(pool, () -> fail("removed task ran"));
            assertTrue(close.get().join());
            assertTrue(request.seal());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
            assertFalse(dropped.isDone());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void noticesCallerRunsDiscardWhenShutdownRacesWithSubmission() throws Exception {
        var afterOffer = new AtomicReference<Consumer<Runnable>>();
        var pool = occupiedPool(queueWithOfferHook(afterOffer), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            afterOffer.set(task -> pool.shutdown());
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var dropped = ExecutorOutput.submit(pool, () -> fail("discarded task ran"));
            assertTrue(close.get().join());
            assertTrue(request.seal());
            assertFalse(pool.isTerminated());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
            assertFalse(dropped.isDone());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void settlesClearedTasksAndPurgedStageWorkers() throws Exception {
        var pool = occupiedPool(new LinkedBlockingQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var cleared = ExecutorOutput.submit(pool, () -> fail("cleared task ran"));
            assertTrue(close.get().join());
            assertTrue(request.seal());
            ExecutorOutput.clear(pool.getQueue());
            assertTrue(pool.getQueue().isEmpty());
            assertFalse(cleared.isDone());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());

            var stageRequest = new ScheduledOutput();
            var closeStage = OutputCapture.begin(line -> fail(line), stageRequest);
            var stage = CommandOutputContext.supplyAsync(() -> fail("purged worker ran"), pool);
            assertTrue(closeStage.get().join());
            assertTrue(stageRequest.seal());
            var worker = assertInstanceOf(Future.class, pool.getQueue().element());
            assertNotSame(stage, worker);
            assertTrue(worker.cancel(false));
            assertFalse(stageRequest.result().isDone());
            ExecutorOutput.purge(pool);
            assertTrue(pool.getQueue().isEmpty());
            assertFalse(stage.isDone());
            assertEquals("cancelled", stageRequest.result().get(2, TimeUnit.SECONDS).status());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void clearingDelayedQueueThroughQueueAliasSettlesUnexpiredWork() throws Exception {
        var pool = new ScheduledThreadPoolExecutor(1);
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var delayed = ExecutorOutput.schedule(pool, () -> fail("cleared delayed task ran"), 1, TimeUnit.HOURS);
            assertTrue(close.get().join());
            assertTrue(request.seal());
            Queue<Runnable> queue = pool.getQueue();
            assertTrue(queue.contains(delayed));
            ExecutorOutput.clear(queue);

            assertTrue(queue.isEmpty());
            assertFalse(pool.isShutdown());
            assertFalse(delayed.isDone());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void clearingPriorityAndTransferQueuesUsesActualRemovals() throws Exception {
        var compare = new AtomicReference<>(true);
        var priority = new PriorityBlockingQueue<Runnable>(1, (first, second) -> {
            assertTrue(compare.get(), "clear must not invoke the priority comparator");
            return 0;
        });
        for (BlockingQueue<Runnable> queue : List.of(priority, new LinkedTransferQueue<Runnable>())) {
            var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
            try {
                var request = new ScheduledOutput();
                var close = OutputCapture.begin(line -> fail(line), request);
                var first = ExecutorOutput.submit(pool, () -> fail("cleared work ran"));
                var second = ExecutorOutput.submit(pool, () -> fail("cleared work ran"));
                var third = ExecutorOutput.submit(pool, () -> fail("cleared work ran"));
                assertTrue(close.get().join());
                assertTrue(request.seal());
                compare.set(false);
                ExecutorOutput.clear(queue);
                assertTrue(queue.isEmpty());
                assertFalse(first.isDone());
                assertFalse(second.isDone());
                assertFalse(third.isDone());
                assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void removeIfSettlesTheSelectedTaskAndKeepsEqualButUnselectedEntries() throws Exception {
        var pool = occupiedPool(new LinkedBlockingQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        try {
            var first = new EqualRunnable();
            var second = new EqualRunnable();
            pool.execute(first);
            pool.execute(second);
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var dropped = ExecutorOutput.submit(pool, () -> fail("filtered work ran"));
            assertTrue(close.get().join());
            assertTrue(request.seal());

            assertTrue(ExecutorOutput.removeIf(pool.getQueue(), task -> task == second || task == dropped));
            assertEquals(1, pool.getQueue().size());
            assertSame(first, pool.getQueue().element());
            assertFalse(dropped.isDone());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void linkedRemoveIfKeepsThePositionOfUnselectedDuplicates() {
        for (var queue : linkedQueues()) {
            var duplicate = new EqualRunnable();
            var middle = new EqualRunnable();
            queue.addAll(List.of(duplicate, middle, duplicate));
            var visits = new AtomicInteger();

            assertTrue(ExecutorOutput.removeIf(queue, task -> task == duplicate && visits.incrementAndGet() == 2));
            assertSame(duplicate, queue.remove());
            assertSame(middle, queue.remove());
            assertTrue(queue.isEmpty());
        }
    }

    @Test
    void linkedRemoveIfCanSeeAnEntryAppendedDuringAnEarlierPredicate() {
        for (var queue : linkedQueues()) {
            var first = new EqualRunnable();
            var appended = new EqualRunnable();
            queue.add(first);
            for (int i = 0; i < 64; i++) {
                queue.add(new EqualRunnable());
            }

            assertTrue(ExecutorOutput.removeIf(queue, task -> {
                if (task == first) {
                    queue.add(appended);
                }
                return task == appended;
            }));
            assertEquals(65, queue.size());
            assertTrue(queue.stream().noneMatch(task -> task == appended));
        }
    }

    @Test
    void removingOneOccurrenceOfAStageWorkerKeepsCapturingTheOther() throws Exception {
        for (var queue : linkedQueues()) {
            var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue);
            var release = new CompletableFuture<Void>();
            try {
                pool.execute(release::join);
                var request = new ScheduledOutput();
                var close = OutputCapture.begin(line -> fail(line), request);
                var stage = CommandOutputContext.supplyAsync(() -> {
                    LogManager.getLogger("test.executor.duplicate").error("remaining stage output");
                    return 42;
                }, pool);
                var worker = queue.element();
                queue.add(new EqualRunnable());
                queue.add(worker);
                assertTrue(close.get().join());
                assertTrue(request.seal());
                var visits = new AtomicInteger();

                assertTrue(ExecutorOutput.removeIf(queue, task -> task == worker && visits.incrementAndGet() == 2));
                assertSame(worker, queue.element());
                assertFalse(request.result().isDone());
                release.complete(null);
                assertEquals(42, stage.get(2, TimeUnit.SECONDS));
                assertEquals(new SudoCommands.Result("ok", "remaining stage output\n"),
                        request.result().get(2, TimeUnit.SECONDS));
            } finally {
                release.complete(null);
                ExecutorOutput.shutdownNow(pool);
            }
        }
    }

    @Test
    void removeReportsTheActualStageWorkerWhenTheLookupKeyIsEqual() throws Exception {
        for (boolean poolRemoval : List.of(false, true)) {
            for (var queue : linkedQueues()) {
                var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
                try {
                    var request = new ScheduledOutput();
                    var close = OutputCapture.begin(line -> fail(line), request);
                    var stage = CommandOutputContext.supplyAsync(() -> fail("removed stage ran"), pool);
                    var worker = queue.element();
                    assertTrue(close.get().join());
                    assertTrue(request.seal());
                    var key = new Runnable() {
                        @Override public void run() {}
                        @Override public boolean equals(Object other) { return other == this || other == worker; }
                        @Override public int hashCode() { return worker.hashCode(); }
                    };

                    assertTrue(poolRemoval ? ExecutorOutput.remove(pool, key) : ExecutorOutput.remove(queue, key));
                    assertTrue(queue.isEmpty());
                    assertFalse(stage.isDone());
                    assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
                } finally {
                    ExecutorOutput.shutdownNow(pool);
                }
            }
        }
    }

    private static List<BlockingQueue<Runnable>> linkedQueues() {
        return List.of(new LinkedBlockingQueue<>(), new LinkedBlockingDeque<>(), new LinkedTransferQueue<>());
    }

    @Test
    void removeIfDoesNotSettleAWorkerTaskTakenDuringPredicateEvaluation() throws Exception {
        var pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        var releaseQueue = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var finishWorker = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                try {
                    releaseQueue.await();
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            });
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var future = ExecutorOutput.submit(pool, () -> {
                started.countDown();
                assertTrue(finishWorker.await(5, TimeUnit.SECONDS));
                LogManager.getLogger("test.executor.remove").error("running output");
                return 42;
            });
            assertTrue(close.get().join());
            assertTrue(request.seal());

            assertFalse(ExecutorOutput.removeIf(pool.getQueue(), task -> {
                assertSame(future, task);
                releaseQueue.countDown();
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    fail(stopped);
                }
                return true;
            }));
            assertFalse(request.result().isDone());
            finishWorker.countDown();
            assertEquals(42, future.get(2, TimeUnit.SECONDS));
            assertEquals(new SudoCommands.Result("ok", "running output\n"), request.result().get(2, TimeUnit.SECONDS));
        } finally {
            releaseQueue.countDown();
            finishWorker.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void purgeObservesAStageWorkerCancelledDuringTraversal() throws Exception {
        var beforeIterator = new AtomicReference<Runnable>();
        var queue = new LinkedBlockingQueue<Runnable>() {
            @Override
            public Iterator<Runnable> iterator() {
                var hook = beforeIterator.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
                return super.iterator();
            }
        };
        var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var stage = CommandOutputContext.supplyAsync(() -> fail("purged worker ran"), pool);
            assertTrue(close.get().join());
            assertTrue(request.seal());
            var worker = assertInstanceOf(Future.class, queue.element());
            beforeIterator.set(() -> assertTrue(worker.cancel(false)));
            ExecutorOutput.purge(pool);
            assertTrue(queue.isEmpty());
            assertFalse(stage.isDone());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void throwingBulkPredicateLeavesCurrentLinkedAndArrayBatchesQueued() throws Exception {
        for (BlockingQueue<Runnable> queue : List.of(
                new LinkedBlockingQueue<Runnable>(), new LinkedBlockingDeque<Runnable>(),
                new ArrayBlockingQueue<Runnable>(2))) {
            var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
            try {
                var request = new ScheduledOutput();
                var close = OutputCapture.begin(line -> fail(line), request);
                var first = ExecutorOutput.submit(pool, () -> 1);
                var second = ExecutorOutput.submit(pool, () -> 2);
                assertTrue(close.get().join());
                assertTrue(request.seal());
                var failure = new IllegalStateException("predicate failed");

                assertSame(failure, assertThrows(IllegalStateException.class, () ->
                        ExecutorOutput.removeIf(queue, task -> {
                            if (task == second) {
                                throw failure;
                            }
                            return task == first;
                        })));
                assertArrayEquals(new Object[]{first, second}, queue.toArray());
                assertFalse(first.isDone());
                assertFalse(request.result().isDone());
            } finally {
                ExecutorOutput.shutdownNow(pool);
            }
        }
    }

    @Test
    void throwingArrayPredicateKeepsEarlierRemovalWithAnActiveIterator() throws Exception {
        var queue = new ArrayBlockingQueue<Runnable>(2);
        var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var first = ExecutorOutput.submit(pool, () -> fail("removed task ran"));
            var second = new FutureTask<>(() -> 2);
            queue.add(second);
            assertTrue(close.get().join());
            assertTrue(request.seal());
            var active = queue.iterator();
            assertTrue(active.hasNext());
            var failure = new IllegalStateException("predicate failed");

            assertSame(failure, assertThrows(IllegalStateException.class, () -> ExecutorOutput.removeIf(queue, task -> {
                if (task == second) {
                    throw failure;
                }
                return true;
            })));
            assertArrayEquals(new Object[]{second}, queue.toArray());
            assertFalse(first.isDone());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
            Reference.reachabilityFence(active);
        } finally {
            ExecutorOutput.shutdownNow(pool);
        }
    }

    @Test
    void arrayBulkPredicateHoldsTheNativeLockUntilRemoval() throws Exception {
        var queue = new ArrayBlockingQueue<Runnable>(2);
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue);
        var releaseWorker = new CompletableFuture<Void>();
        var worker = new CompletableFuture<Thread>();
        try {
            pool.execute(() -> {
                worker.complete(Thread.currentThread());
                releaseWorker.join();
            });
            Thread workerThread = worker.get(2, TimeUnit.SECONDS);
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var task = ExecutorOutput.submit(pool, () -> fail("removed task ran"));
            assertTrue(close.get().join());
            assertTrue(request.seal());

            assertTrue(ExecutorOutput.removeIf(queue, candidate -> {
                assertSame(task, candidate);
                releaseWorker.complete(null);
                awaitQueueWait(workerThread, "take");
                return true;
            }));
            assertFalse(task.isDone());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
        } finally {
            releaseWorker.complete(null);
            ExecutorOutput.shutdownNow(pool);
        }
    }

    @Test
    void queueMaintenanceReportsWorkQueuedWhileItWaitsForTheNativeLock() throws Exception {
        for (String operation : List.of("clear", "removeIf", "purge")) {
            var queue = new ArrayBlockingQueue<Runnable>(2);
            var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
            try {
                queue.add(() -> {});
                var request = new ScheduledOutput();
                var submitted = new AtomicReference<Future<?>>();
                var removed = new AtomicReference<Object>();
                var clean = new FutureTask<Void>(() -> {
                    switch (operation) {
                        case "clear" -> ExecutorOutput.clear(queue);
                        case "removeIf" -> ExecutorOutput.removeIf(queue, task -> task == removed.get());
                        case "purge" -> ExecutorOutput.purge(pool);
                    }
                    return null;
                });
                var cleaner = new Thread(clean, "executor-test-" + operation);
                cleaner.setDaemon(true);
                queue.forEach(ignored -> {
                    if (submitted.get() != null) {
                        return;
                    }
                    cleaner.start();
                    awaitQueueWait(cleaner, "lock");
                    var close = OutputCapture.begin(line -> fail(line), request);
                    if (operation.equals("purge")) {
                        submitted.set(CommandOutputContext.supplyAsync(() -> fail("purged task ran"), pool));
                        var stageWorker = assertInstanceOf(Future.class, queue.toArray()[1]);
                        assertTrue(stageWorker.cancel(false));
                        removed.set(stageWorker);
                    } else {
                        submitted.set(ExecutorOutput.submit(pool, () -> fail("removed task ran")));
                        removed.set(submitted.get());
                    }
                    assertTrue(close.get().join());
                    assertTrue(request.seal());
                });

                clean.get(2, TimeUnit.SECONDS);
                assertFalse(queue.contains(removed.get()), operation);
                assertFalse(submitted.get().isDone(), operation);
                assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status(), operation);
            } finally {
                ExecutorOutput.shutdownNow(pool);
            }
        }
    }

    private static void awaitQueueWait(Thread thread, String method) {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            while (true) {
                if (thread.getState() == Thread.State.WAITING) {
                    for (StackTraceElement frame : thread.getStackTrace()) {
                        if (frame.getMethodName().equals(method)) {
                            return;
                        }
                    }
                }
                Thread.sleep(1);
            }
        });
    }

    @Test
    void linkedQueueRetainsTheFailingBatchAndReportsEarlierCompletedBatches() throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>();
        var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var earlier = ExecutorOutput.submit(pool, () -> 1);
            for (int index = 1; index < 64; index++) {
                pool.execute(() -> {});
            }
            var last = ExecutorOutput.submit(pool, () -> 2);
            assertTrue(close.get().join());
            assertTrue(request.seal());
            var failure = new IllegalStateException("second batch failed");

            assertSame(failure, assertThrows(IllegalStateException.class, () ->
                    ExecutorOutput.removeIf(queue, task -> {
                        if (task == last) {
                            throw failure;
                        }
                        return true;
                    })));
            assertArrayEquals(new Object[]{last}, queue.toArray());
            assertFalse(earlier.isDone());
            assertFalse(last.isDone());
            assertFalse(request.result().isDone());
            assertEquals("已取消一项后台任务。\n", request.output().takeFollowup().text());
        } finally {
            ExecutorOutput.shutdownNow(pool);
        }
    }

    @Test
    void priorityQueueReportsRemovedWorkEvenWhenHeapifyThrows() throws Exception {
        var comparatorFailure = new IllegalArgumentException("heapify failed");
        var reheap = new AtomicReference<RuntimeException>();
        var queue = new PriorityBlockingQueue<Runnable>(4, (first, second) -> {
            if (reheap.get() != null) {
                throw reheap.get();
            }
            return 0;
        });
        var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var tracked = ExecutorOutput.submit(pool, () -> 1);
            assertTrue(close.get().join());
            assertTrue(request.seal());
            pool.execute(() -> {});
            pool.execute(() -> {});
            var predicateFailure = new IllegalStateException("predicate failed");

            assertSame(predicateFailure, assertThrows(IllegalStateException.class, () ->
                    ExecutorOutput.removeIf(queue, task -> {
                        if (task != tracked) {
                            throw predicateFailure;
                        }
                        return true;
                    })));
            assertTrue(queue.contains(tracked));
            assertFalse(request.result().isDone());

            reheap.set(comparatorFailure);
            assertSame(comparatorFailure, assertThrows(IllegalArgumentException.class, () ->
                    ExecutorOutput.removeIf(queue, task -> task == tracked)));
            assertFalse(queue.contains(tracked));
            assertFalse(tracked.isDone());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
        } finally {
            reheap.set(null);
            pool.shutdownNow();
        }
    }

    @Test
    void purgePreservesAnEqualRunnableAheadOfACancelledTask() throws Exception {
        for (boolean watched : List.of(false, true)) {
            var queue = new LinkedBlockingQueue<Runnable>() {};
            var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
            try {
                var runnable = new EqualFuture();
                var cancelled = new EqualFuture();
                assertTrue(cancelled.cancel(false));
                pool.execute(runnable);
                pool.execute(cancelled);
                Future<?> tracked = null;
                if (watched) {
                    var request = new ScheduledOutput();
                    var close = OutputCapture.begin(line -> fail(line), request);
                    tracked = ExecutorOutput.submit(pool, () -> 3);
                    assertTrue(close.get().join());
                    assertTrue(request.seal());
                }

                ExecutorOutput.purge(pool);
                assertArrayEquals(watched ? new Object[]{runnable, tracked} : new Object[]{runnable}, queue.toArray());
                assertFalse(runnable.isDone());
            } finally {
                ExecutorOutput.shutdownNow(pool);
            }
        }
    }

    @Test
    void purgeSettlesTheStageWhenACustomQueueOnlySupportsIteratorRemoval() throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>() {
            @Override
            public boolean remove(Object task) {
                throw new UnsupportedOperationException("use the iterator");
            }
        };
        var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
        try {
            var cancelled = new FutureTask<>(() -> 1);
            assertTrue(cancelled.cancel(false));
            pool.execute(cancelled);
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var stage = CommandOutputContext.supplyAsync(() -> fail("purged worker ran"), pool);
            assertTrue(close.get().join());
            assertTrue(request.seal());
            var stageWorker = assertInstanceOf(Future.class, queue.toArray()[1]);
            assertTrue(stageWorker.cancel(false));

            ExecutorOutput.purge(pool);
            assertTrue(queue.isEmpty());
            assertFalse(stage.isDone());
            assertFalse(pool.isShutdown());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
        } finally {
            ExecutorOutput.shutdownNow(pool);
        }
    }

    @Test
    void purgeSettlesStageWorkersWithDelegatingQueueOverrides() throws Exception {
        var toArrayOverride = new LinkedBlockingQueue<Runnable>() {
            @Override
            public Object[] toArray() {
                return super.toArray();
            }
        };
        var iteratorOverride = new LinkedBlockingQueue<Runnable>() {
            @Override
            public Iterator<Runnable> iterator() {
                var delegate = super.iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() { return delegate.hasNext(); }
                    @Override public Runnable next() { return delegate.next(); }
                    @Override public void remove() { delegate.remove(); }
                };
            }
        };
        for (var queue : List.of(toArrayOverride, iteratorOverride)) {
            var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
            try {
                var request = new ScheduledOutput();
                var close = OutputCapture.begin(line -> fail(line), request);
                var stage = CommandOutputContext.supplyAsync(() -> fail("purged worker ran"), pool);
                var worker = assertInstanceOf(Future.class, queue.element());
                assertTrue(close.get().join());
                assertTrue(request.seal());
                assertTrue(worker.cancel(false));

                ExecutorOutput.purge(pool);
                assertTrue(queue.isEmpty());
                assertFalse(stage.isDone());
                assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
            } finally {
                ExecutorOutput.shutdownNow(pool);
            }
        }
    }

    @Test
    void clearingADelegatingLinkedQueueSettlesTrackedWork() throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>() {
            @Override
            public void clear() {
                super.clear();
            }
        };
        var pool = occupiedPool(queue, new ThreadPoolExecutor.AbortPolicy());
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var tracked = ExecutorOutput.submit(pool, () -> fail("cleared task ran"));
            assertTrue(close.get().join());
            assertTrue(request.seal());

            ExecutorOutput.clear(queue);
            assertTrue(queue.isEmpty());
            assertFalse(tracked.isDone());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());
        } finally {
            ExecutorOutput.shutdownNow(pool);
        }
    }

    private static final class EqualFuture extends FutureTask<Integer> {
        private EqualFuture() {
            super(() -> 42);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualFuture;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    private record EqualRunnable() implements Runnable {
        @Override
        public void run() {}
    }

    @Test
    void settlesStagesRejectedByAFullPoolAndStillRunsCallerRunsInline() throws Exception {
        var pool = occupiedPool(new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardPolicy());
        try {
            pool.execute(() -> {});
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var stage = CommandOutputContext.supplyAsync(() -> fail("discarded stage ran"), pool);
            assertTrue(close.get().join());
            assertTrue(request.seal());
            assertFalse(stage.isDone());
            assertEquals("cancelled", request.result().get(2, TimeUnit.SECONDS).status());

            var policy = new ThreadPoolExecutor.CallerRunsPolicy();
            ExecutorOutput.setRejectedExecutionHandler(pool, policy);
            assertSame(policy, ExecutorOutput.getRejectedExecutionHandler(pool));
            var inlineRequest = new ScheduledOutput();
            var closeInline = OutputCapture.begin(line -> fail(line), inlineRequest);
            var inline = CommandOutputContext.supplyAsync(Thread::currentThread, pool);
            assertTrue(closeInline.get().join());
            assertTrue(inlineRequest.seal());
            assertSame(Thread.currentThread(), inline.get(2, TimeUnit.SECONDS));
            assertEquals("ok", inlineRequest.result().get(2, TimeUnit.SECONDS).status());
        } finally {
            pool.shutdownNow();
        }
    }

    private static ThreadPoolExecutor occupiedPool(BlockingQueue<Runnable> queue, RejectedExecutionHandler handler)
            throws InterruptedException {
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue, handler);
        var started = new CountDownLatch(1);
        pool.execute(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        if (!started.await(5, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            fail("worker did not start");
        }
        return pool;
    }

    private static BlockingQueue<Runnable> queueWithOfferHook(AtomicReference<Consumer<Runnable>> afterOffer) {
        return new LinkedBlockingQueue<>() {
            @Override
            public boolean offer(Runnable task) {
                boolean accepted = super.offer(task);
                var hook = afterOffer.getAndSet(null);
                if (accepted && hook != null) {
                    hook.accept(task);
                }
                return accepted;
            }
        };
    }
}
