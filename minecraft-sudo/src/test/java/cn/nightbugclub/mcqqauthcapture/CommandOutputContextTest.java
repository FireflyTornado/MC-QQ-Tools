package cn.nightbugclub.mcqqauthcapture;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class CommandOutputContextTest {
    @Test
    void observesAFutureViewWhenTheOriginalStageRejectsObservers() throws Exception {
        var stage = new CompletableFuture<Integer>() {
            @Override
            public CompletableFuture<Integer> whenComplete(BiConsumer<? super Integer, ? super Throwable> action) {
                throw new UnsupportedOperationException("custom observer");
            }
        };
        var request = new ScheduledOutput();
        var close = OutputCapture.begin(line -> fail(line), request);
        assertSame(stage, CommandOutputContext.track(stage));
        assertTrue(close.get().join());
        assertTrue(request.seal());
        assertFalse(request.result().isDone());
        assertTrue(stage.complete(42));
        assertEquals(new SudoCommands.Result("ok"), request.result().get(2, TimeUnit.SECONDS));
    }

    @Test
    void pollsOriginalFutureWhenBothObserverAndCopyAreUnavailable() throws Exception {
        var stage = new ObserverlessFuture<Integer>();
        var request = new ScheduledOutput();
        var close = OutputCapture.begin(line -> fail(line), request);
        assertSame(stage, CommandOutputContext.track(stage));
        assertTrue(close.get().join());
        assertTrue(request.seal());
        assertFalse(request.result().isDone());
        assertTrue(stage.complete(42));
        assertEquals(new SudoCommands.Result("ok"), request.result().get(5, TimeUnit.SECONDS));
    }

    @Test
    void capturesLateCallbackOutputWhilePollingItsDependentStage() throws Exception {
        var upstream = new CompletableFuture<String>() {
            @Override
            public <U> CompletableFuture<U> newIncompleteFuture() {
                return new ObserverlessFuture<>();
            }
        };
        var log = LogManager.getLogger("test.context.observer");
        var request = new ScheduledOutput();
        var close = OutputCapture.begin(line -> fail(line), request);
        var dependent = CommandOutputContext.whenComplete(upstream, (value, failure) -> log.error(value));
        assertTrue(close.get().join());
        assertTrue(request.seal());
        assertFalse(request.result().isDone());
        assertTrue(upstream.complete("迟到的回调输出"));
        assertEquals("迟到的回调输出", dependent.toCompletableFuture().join());
        assertEquals(new SudoCommands.Result("ok", "迟到的回调输出\n"), request.result().get(5, TimeUnit.SECONDS));
    }

    @Test
    void reportsUnobservableStagesAndKeepsTheOutputWindowOpen() throws Exception {
        var stage = (CompletionStage<?>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CompletionStage.class}, (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        var request = new ScheduledOutput();
        var close = OutputCapture.begin(line -> fail(line), request);
        assertSame(stage, CommandOutputContext.track(stage));
        assertTrue(close.get().join());
        assertTrue(request.seal());
        assertFalse(request.result().isDone());
        request.output().appendFollowupLine("迟到消息");
        request.expire();
        assertEquals(new SudoCommands.Result("expired", "无法观察后台阶段是否完成，输出采集可能不完整。\n迟到消息\n"),
                request.result().get(2, TimeUnit.SECONDS));
    }

    private static final class ObserverlessFuture<T> extends CompletableFuture<T> {
        @Override
        public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
            throw new UnsupportedOperationException("custom observer");
        }

        @Override
        public CompletableFuture<T> copy() {
            throw new UnsupportedOperationException("custom view");
        }
    }

    @Test
    void capturesRunningWorkWithoutReplacingItsCancelledOutcome() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var log = LogManager.getLogger("test.context.running");
        try {
            var request = new ScheduledOutput();
            var close = OutputCapture.begin(line -> fail(line), request);
            var stage = CommandOutputContext.supplyAsync(() -> {
                started.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
                log.error("后台输出");
                return 42;
            }, pool);
            assertTrue(close.get().join());
            assertTrue(request.seal());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(stage.cancel(false));
            assertFalse(request.result().isDone());
            release.countDown();
            assertEquals(new SudoCommands.Result("cancelled", "后台输出\n已取消一项后台任务。\n"),
                    request.result().get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void attributesWrappedWorkerAndCallbackToTheSubmittingRequest() throws Exception {
        var output = new ScheduledOutput();
        var log = LogManager.getLogger("test.context");
        var close = OutputCapture.begin(ignored -> {}, output);
        var task = CommandOutputContext.wrap(() -> log.error("自建线程"));
        var callback = CommandOutputContext.<String>wrap(value -> log.error(value));
        close.get().join();
        assertTrue(output.seal());

        CompletableFuture.runAsync(task).get(2, TimeUnit.SECONDS);
        assertFalse(output.result().isDone());
        CompletableFuture.runAsync(() -> callback.accept("插件回调")).get(2, TimeUnit.SECONDS);
        assertEquals(new SudoCommands.Result("ok", "自建线程\n插件回调\n"), output.result().join());
    }

    @Test
    void releasesRejectedWorkWithoutChangingTheOriginalAction() {
        var output = new ScheduledOutput();
        var calls = new ArrayList<String>();
        var close = OutputCapture.begin(ignored -> {}, output);
        var task = CommandOutputContext.wrap(() -> calls.add("original"));
        close.get().join();
        assertTrue(output.seal());

        task.close();
        assertEquals("cancelled", output.result().join().status());
        task.run();
        assertEquals(List.of("original"), calls);
    }

    @Test
    void keepsNestedStagesOnTheOriginalRequestAcrossTheSameWorker() throws Exception {
        var request = new ScheduledOutput();
        var otherRequest = new ScheduledOutput();
        var bukkitTask = new Object();
        var jobs = new LinkedBlockingQueue<Runnable>();
        var upload = new CompletableFuture<String>();
        var otherStage = new CompletableFuture<String>();
        var log = LogManager.getLogger("test.context.chain");

        var close = OutputCapture.begin(ignored -> {}, request);
        ScheduledOutput.enqueueTask(bukkitTask);
        close.get().join();
        assertTrue(request.seal());

        close = OutputCapture.begin(ignored -> {}, otherRequest);
        var otherCallback = CommandOutputContext.whenComplete(otherStage, (value, failure) -> log.error(value));
        close.get().join();
        assertTrue(otherRequest.seal());

        ScheduledOutput.run(bukkitTask, () -> {
            var report = CommandOutputContext.supplyAsync(() -> {
                log.error("准备报告");
                return "开始上传";
            }, jobs::add);
            CommandOutputContext.whenComplete(report, (value, failure) -> {
                log.error(value);
                CommandOutputContext.whenCompleteAsync(upload, (link, error) -> log.error(link), jobs::add);
            });
        });
        assertFalse(request.result().isDone());

        var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(jobs.remove()).get(2, TimeUnit.SECONDS);
            assertFalse(request.result().isDone());
            worker.submit(() -> otherStage.complete("另一条指令")).get(2, TimeUnit.SECONDS);
            assertEquals("另一条指令", otherCallback.toCompletableFuture().join());
            assertEquals(new SudoCommands.Result("ok", "另一条指令\n"), otherRequest.result().join());

            worker.submit(() -> {
                upload.complete("报告链接");
                log.error("未关联的日志");
            }).get(2, TimeUnit.SECONDS);
            assertFalse(request.result().isDone());
            worker.submit(jobs.remove()).get(2, TimeUnit.SECONDS);
            assertEquals(new SudoCommands.Result("ok", "准备报告\n开始上传\n报告链接\n"),
                    request.result().get(2, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
            request.abandon();
            otherRequest.abandon();
        }
    }

    @Test
    void tracksTheOriginalStageUntilItSettlesWithoutReplacingIt() {
        var request = new ScheduledOutput();
        var stage = new CompletableFuture<String>();
        var close = OutputCapture.begin(ignored -> {}, request);
        assertSame(stage, CommandOutputContext.track(stage));
        close.get().join();
        assertTrue(request.seal());
        assertFalse(request.result().isDone());
        stage.complete("done");
        assertEquals("done", stage.join());
        assertEquals(new SudoCommands.Result("ok"), request.result().join());
    }

    @Test
    void releasesBothRejectedSubmissionsAndRejectedCompletionCallbacks() {
        var request = new ScheduledOutput();
        var rejected = new RejectedExecutionException("queue full");
        var close = OutputCapture.begin(ignored -> {}, request);
        assertSame(rejected, assertThrows(RejectedExecutionException.class, () ->
                CommandOutputContext.supplyAsync(() -> "ignored", task -> { throw rejected; })));
        var dependent = CommandOutputContext.whenCompleteAsync(CompletableFuture.completedFuture("done"),
                (value, failure) -> fail("rejected callback ran"), task -> { throw rejected; }).toCompletableFuture();
        close.get().join();
        assertTrue(request.seal());
        assertSame(rejected, assertThrows(CompletionException.class, dependent::join).getCause());
        assertEquals(new SudoCommands.Result("partial"), request.result().join());
    }

    @Test
    void releasesCancelledWorkEvenWhenNeitherActionRuns() {
        var request = new ScheduledOutput();
        var upstream = new CompletableFuture<String>();
        var jobs = new LinkedBlockingQueue<Runnable>();
        var close = OutputCapture.begin(ignored -> {}, request);
        var supplier = CommandOutputContext.supplyAsync(() -> fail("cancelled supplier ran"), jobs::add);
        var dependent = CommandOutputContext.whenComplete(upstream, (value, failure) -> fail("cancelled callback ran"))
                .toCompletableFuture();
        close.get().join();
        assertTrue(request.seal());
        assertTrue(supplier.cancel(false));
        assertFalse(request.result().isDone());
        assertTrue(dependent.cancel(false));
        assertEquals(new SudoCommands.Result("cancelled", "已取消一项后台任务。\n已取消一项后台任务。\n"),
                request.result().join());
        upstream.complete("done");
        jobs.remove().run();
    }

    @Test
    void attributesFailureAndCancellationHandlersAndPreservesTheirStageOutcomes() {
        var failedRequest = new ScheduledOutput();
        var cancelledRequest = new ScheduledOutput();
        var failed = new CompletableFuture<String>();
        var cancelled = new CompletableFuture<String>();
        var cause = new IllegalStateException("upload failed");
        var log = LogManager.getLogger("test.context.failure");

        var close = OutputCapture.begin(ignored -> {}, failedRequest);
        var failureHandler = CommandOutputContext.whenComplete(failed, (value, failure) -> {
            assertSame(cause, failure);
            log.error("上传失败");
        }).toCompletableFuture();
        close.get().join();
        assertTrue(failedRequest.seal());

        close = OutputCapture.begin(ignored -> {}, cancelledRequest);
        var cancellationHandler = CommandOutputContext.whenComplete(cancelled, (value, failure) -> {
            assertInstanceOf(CancellationException.class, failure);
            log.error("清理临时文件");
        }).toCompletableFuture();
        close.get().join();
        assertTrue(cancelledRequest.seal());

        failed.completeExceptionally(cause);
        cancelled.cancel(false);
        assertSame(cause, assertThrows(CompletionException.class, failureHandler::join).getCause());
        assertInstanceOf(CancellationException.class, assertThrows(CompletionException.class, cancellationHandler::join)
                .getCause());
        assertEquals(new SudoCommands.Result("partial", "上传失败\n"), failedRequest.result().join());
        assertEquals(new SudoCommands.Result("cancelled", "已取消一项后台任务。\n清理临时文件\n"),
                cancelledRequest.result().join());
    }
}
