package cn.nightbugclub.mcqqauthcapture;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
}
