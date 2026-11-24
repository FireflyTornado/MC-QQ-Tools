package cn.nightbugclub.mcqqauthcapture;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class StageOutputTest {
    @Test
    void attributesAComposedStageToItsRegistrationAndWaitsForItsResult() throws Throwable {
        var compose = link("thenCompose", CompletableFuture.class, Function.class);
        var input = new CompletableFuture<String>();
        var nested = new CompletableFuture<Integer>();
        var first = new ScheduledOutput();
        var second = new ScheduledOutput();
        var secondImmediate = new ArrayList<String>();
        var log = LogManager.getLogger("test.stage.compose");

        var finish = OutputCapture.begin(line -> fail(line), first);
        Function<String, CompletableFuture<Integer>> action = value -> {
            log.error(value);
            return nested;
        };
        var result = (CompletableFuture<?>) compose.invoke(input, action);
        assertTrue(finish.get().join());
        assertTrue(first.seal());

        var closeSecond = OutputCapture.begin(secondImmediate::add, second);
        assertTrue(input.complete("first request"));
        log.error("second request");
        assertTrue(closeSecond.get().join());
        assertFalse(second.seal());
        assertEquals(List.of("second request"), secondImmediate);
        assertFalse(first.result().isDone());
        assertFalse(result.isDone());

        assertTrue(nested.complete(42));
        assertEquals(42, result.join());
        assertEquals(new SudoCommands.Result("ok", "first request\n"), first.result().join());
    }

    @Test
    void capturesDefaultAsyncWorkAndCallbacksOnTheSuppliedExecutor() throws Throwable {
        var supplier = link("supplyAsync", null, Supplier.class);
        var whenComplete = link("whenCompleteAsync", CompletionStage.class, BiConsumer.class, Executor.class);
        var jobs = new LinkedBlockingQueue<Runnable>();
        var request = new ScheduledOutput();
        var log = LogManager.getLogger("test.stage.async");
        var finish = OutputCapture.begin(line -> fail(line), request);
        Supplier<String> work = () -> {
            log.error("supplier output");
            return "value";
        };
        var upstream = (CompletableFuture<?>) supplier.invoke(work);
        BiConsumer<Object, Throwable> action = (value, failure) -> {
            assertNull(failure);
            log.error("callback {}", value);
        };
        Executor executor = jobs::add;
        var result = (CompletionStage<?>) whenComplete.invoke(upstream.minimalCompletionStage(), action, executor);
        assertTrue(finish.get().join());
        assertTrue(request.seal());
        assertEquals("value", upstream.get(5, TimeUnit.SECONDS));
        var job = jobs.poll(5, TimeUnit.SECONDS);
        assertNotNull(job);
        assertFalse(request.result().isDone());
        CompletableFuture.runAsync(job).get(5, TimeUnit.SECONDS);
        assertEquals("value", result.toCompletableFuture().join());
        assertEquals(new SudoCommands.Result("ok", "supplier output\ncallback value\n"),
                request.result().get(5, TimeUnit.SECONDS));
    }

    @Test
    void settlesSkippedRejectedAndCancelledStagesWithOriginalJavaOutcomes() throws Throwable {
        var apply = link("thenApply", CompletableFuture.class, Function.class);
        var applyAsync = link("thenApplyAsync", CompletableFuture.class, Function.class, Executor.class);
        var upstream = new CompletableFuture<String>();
        var skips = new CompletableFuture<String>();
        var cancelledBeforeRun = new CompletableFuture<String>();
        var request = new ScheduledOutput();
        var cancellation = new ScheduledOutput();
        var failure = new IllegalStateException("upstream failure");
        var rejection = new RejectedExecutionException("queue full");
        Function<String, Integer> shouldNotRun = value -> fail("callback ran");
        Executor rejecting = task -> { throw rejection; };

        var finish = OutputCapture.begin(line -> fail(line), request);
        var skipped = (CompletableFuture<?>) apply.invoke(skips, shouldNotRun);
        var rejected = (CompletableFuture<?>) applyAsync.invoke(upstream, shouldNotRun, rejecting);
        assertTrue(finish.get().join());
        assertTrue(request.seal());
        assertTrue(skips.completeExceptionally(failure));
        assertSame(failure, assertThrows(CompletionException.class, skipped::join).getCause());
        assertFalse(request.result().isDone());
        upstream.complete("ready");
        assertSame(rejection, assertThrows(CompletionException.class, rejected::join).getCause());
        assertEquals(new SudoCommands.Result("partial"), request.result().join());

        var close = OutputCapture.begin(line -> fail(line), cancellation);
        var dependent = (CompletableFuture<?>) apply.invoke(cancelledBeforeRun, shouldNotRun);
        assertTrue(close.get().join());
        assertTrue(cancellation.seal());
        assertTrue(dependent.cancel(false));
        assertTrue(cancelledBeforeRun.complete("ignored"));
        assertEquals(new SudoCommands.Result("cancelled", "已取消一项后台任务。\n"), cancellation.result().join());
    }

    @Test
    void handlesRecoveryAndCancellationCallbacksWithoutChangingTheirResults() throws Throwable {
        var recover = link("exceptionally", CompletableFuture.class, Function.class);
        var whenComplete = link("whenComplete", CompletableFuture.class, BiConsumer.class);
        var upstream = new CompletableFuture<Integer>();
        var cancelledUpstream = new CompletableFuture<Integer>();
        var recovery = new ScheduledOutput();
        var cancellation = new ScheduledOutput();
        var log = LogManager.getLogger("test.stage.recover");

        var closeRecovery = OutputCapture.begin(line -> fail(line), recovery);
        Function<Throwable, Integer> fallback = failure -> {
            log.error("recovered");
            return 7;
        };
        var recovered = (CompletableFuture<?>) recover.invoke(upstream, fallback);
        assertTrue(closeRecovery.get().join());
        assertTrue(recovery.seal());
        assertTrue(upstream.completeExceptionally(new IllegalStateException()));
        assertEquals(7, recovered.join());
        assertEquals(new SudoCommands.Result("ok", "recovered\n"), recovery.result().join());

        var closeCancellation = OutputCapture.begin(line -> fail(line), cancellation);
        BiConsumer<Integer, Throwable> cleanup = (value, failure) -> log.error("cleanup");
        var dependent = (CompletableFuture<?>) whenComplete.invoke(cancelledUpstream, cleanup);
        assertTrue(closeCancellation.get().join());
        assertTrue(cancellation.seal());
        assertTrue(cancelledUpstream.cancel(false));
        assertTrue(dependent.isCompletedExceptionally());
        assertEquals(new SudoCommands.Result("cancelled", "cleanup\n已取消一项后台任务。\n"),
                cancellation.result().join());
    }

    @Test
    void forwardsOriginalCallbacksToCustomStages() throws Throwable {
        var apply = link("thenApply", CompletableFuture.class, Function.class);
        Function<Integer, Integer> expected = value -> value + 1;
        var custom = new CompletableFuture<Integer>() {
            @Override
            public <U> CompletableFuture<U> thenApply(Function<? super Integer, ? extends U> actual) {
                assertSame(expected, actual);
                return super.thenApply(actual);
            }
        };
        var request = new ScheduledOutput();
        var finish = OutputCapture.begin(line -> fail(line), request);
        var result = (CompletableFuture<?>) apply.invoke(custom, expected);
        assertTrue(finish.get().join());
        assertFalse(request.seal());
        assertTrue(custom.complete(7));
        assertEquals(8, result.join());
    }

    @Test
    void silentRejectionFromAStoppedExecutorUsesTheRequestDeadline() throws Throwable {
        var async = link("runAsync", null, Runnable.class, Executor.class);
        var pool = new ScheduledThreadPoolExecutor(1, new ThreadPoolExecutor.DiscardPolicy());
        pool.shutdown();
        var request = new ScheduledOutput();
        var close = OutputCapture.begin(line -> fail(line), request);
        Runnable task = () -> fail("discarded task ran");
        var stage = (CompletableFuture<?>) async.invoke(task, pool);
        assertTrue(close.get().join());
        assertTrue(request.seal());
        assertFalse(stage.isDone());
        assertFalse(request.result().isDone());
        request.expire();
        assertEquals("expired", request.result().join().status());
    }

    private static MethodHandle link(String method, Class<?> receiver, Class<?>... parameters)
            throws ReflectiveOperationException {
        var lookup = MethodHandles.lookup();
        var result = receiver == null ? CompletableFuture.class : receiver;
        var signature = MethodType.methodType(result, parameters);
        var original = receiver == null ? lookup.findStatic(result, method, signature)
                : lookup.findVirtual(receiver, method, signature);
        return StageOutput.bootstrap(lookup, method, original.type(), original).dynamicInvoker();
    }
}
