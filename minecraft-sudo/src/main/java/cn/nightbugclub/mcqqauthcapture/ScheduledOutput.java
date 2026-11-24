package cn.nightbugclub.mcqqauthcapture;

import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class ScheduledOutput {
    static final int WAIT_SECONDS = 3600;
    static final int DIRECT_REPLY_WAIT_SECONDS = 30;
    private static final int MAX_EVENTS = 512;
    private static final Map<Object, Event> EVENTS = new IdentityHashMap<>();
    private static boolean pollingFutures;

    private record Event(ScheduledOutput owner, String cancellation, Future<?> future) {}

    private final CommandOutput output = new CommandOutput();
    private final CompletableFuture<SudoCommands.Result> result = new CompletableFuture<>();
    private int pending;
    private boolean scheduled;
    private boolean sealed;
    private boolean ran;
    private boolean complete = true;
    private volatile boolean failedDuringDispatch;

    CommandOutput output() {
        return output;
    }

    void markDispatchFailure() {
        failedDuringDispatch = true;
    }

    boolean failedDuringDispatch() {
        return failedDuringDispatch;
    }

    public static void enqueue(Object event) {
        enqueue(event, "已取消一项预约函数。");
    }

    public static void enqueueTask(Object task) {
        registerTask(task);
    }

    static boolean registerTask(Object task) {
        return enqueue(task, "已取消一项后台任务。");
    }

    static boolean hasPendingEvent(Object event) {
        synchronized (EVENTS) {
            return EVENTS.containsKey(event);
        }
    }

    private static boolean enqueue(Object event, String cancellation) {
        ScheduledOutput owner = OutputCapture.scheduledOutput();
        if (owner == null) {
            return false;
        }
        synchronized (EVENTS) {
            if (owner.result.isDone()) {
                return false;
            }
            owner.scheduled = true;
            if (EVENTS.size() >= MAX_EVENTS) {
                owner.complete = false;
                return false;
            }
            EVENTS.put(event, new Event(owner, cancellation, null));
            owner.pending++;
            return true;
        }
    }

    static void observeFuture(Object eventId, Future<?> future) {
        synchronized (EVENTS) {
            Event event = EVENTS.get(eventId);
            if (event != null && future != null) {
                EVENTS.put(eventId, new Event(event.owner(), event.cancellation(), future));
                if (!pollingFutures) {
                    pollingFutures = true;
                    scheduleFuturePoll();
                }
            }
        }
    }

    private static void scheduleFuturePoll() {
        CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(ScheduledOutput::pollFutures);
    }

    private static void pollFutures() {
        synchronized (EVENTS) {
            var settled = EVENTS.entrySet().stream()
                    .filter(entry -> {
                        var future = entry.getValue().future();
                        return future != null && future.isDone();
                    })
                    .map(Map.Entry::getKey).toList();
            for (Object task : settled) {
                Event event = EVENTS.get(task);
                if (event != null) {
                    if (event.future().isCancelled()) {
                        cancel(task);
                    } else {
                        completeTask(task, new IllegalStateException("Executor completed without running the task"));
                    }
                }
            }
            pollingFutures = EVENTS.values().stream().anyMatch(event -> event.future() != null);
            if (pollingFutures) {
                scheduleFuturePoll();
            }
        }
    }

    public static void cancel(Object event) {
        synchronized (EVENTS) {
            Event registered = EVENTS.remove(event);
            if (registered != null) {
                ScheduledOutput owner = registered.owner();
                owner.pending--;
                owner.output.appendFollowupLine(registered.cancellation());
                owner.finishIfReady();
            }
        }
    }

    public static void run(Object event, Runnable callback) {
        call(event, () -> {
            callback.run();
            return null;
        }, null);
    }

    static <T> T callStage(Object event, Supplier<T> callback, CompletableFuture<Throwable> settled) {
        return callStage(event, callback, settled, null);
    }

    static void runStageCompletion(Object event, Runnable callback, CompletableFuture<Throwable> settled,
                                   Throwable upstreamFailure) {
        callStage(event, () -> {
            callback.run();
            return null;
        }, settled, upstreamFailure);
    }

    private static <T> T callStage(Object event, Supplier<T> callback, CompletableFuture<Throwable> settled,
                                   Throwable upstreamFailure) {
        // In thenCompose the callback can return before its dependent stage finishes.
        return call(event, callback::get, upstreamFailure, (registered, captured) -> {
            // A pending mod stage must be able to outlive an expired request without retaining it.
            var request = new WeakReference<>(registered.owner());
            String cancellation = registered.cancellation();
            settled.thenAccept(failure -> {
                var owner = request.get();
                if (owner != null) {
                    boolean cancelled = isCancellation(failure);
                    if (cancelled && !isCancellation(upstreamFailure)) {
                        owner.output.appendFollowupLine(cancellation);
                    }
                    owner.finishEvent(captured && (failure == null || cancelled), !cancelled);
                }
            });
        });
    }

    public static <T> T callFuture(Object event, Callable<T> callback) throws Exception {
        return call(event, callback::call, null);
    }

    private static <T, E extends Exception> T call(Object event, OutputCapture.QueuedAction<T, E> callback,
                                                    Throwable upstreamFailure) throws E {
        return call(event, callback, upstreamFailure, (registered, captured) ->
                registered.owner().finishEvent(captured, !isCancellation(upstreamFailure)));
    }

    private static <T, E extends Exception> T call(Object event, OutputCapture.QueuedAction<T, E> callback,
                                                    Throwable upstreamFailure,
                                                    BiConsumer<Event, Boolean> finish) throws E {
        boolean cancelled = isCancellation(upstreamFailure);
        Event registered;
        synchronized (EVENTS) {
            registered = EVENTS.remove(event);
            if (registered != null && cancelled) {
                registered.owner().output.appendFollowupLine(registered.cancellation());
            }
        }
        ScheduledOutput owner = registered == null ? null : registered.owner();
        if (owner == null || owner.result.isDone()) {
            return OutputCapture.callQueuedTask(callback);
        }

        Supplier<CompletableFuture<Boolean>> closeCapture;
        try {
            closeCapture = OutputCapture.begin(owner.output::appendFollowupLine, owner);
        } catch (RuntimeException | LinkageError failure) {
            try {
                return OutputCapture.callQueuedTask(callback);
            } finally {
                finish.accept(registered, false);
            }
        }
        boolean succeeded = false;
        try {
            T value = callback.run();
            succeeded = true;
            return value;
        } finally {
            boolean ranSuccessfully = succeeded && (upstreamFailure == null || cancelled);
            closeCapture.get().completeOnTimeout(false, 30, TimeUnit.SECONDS)
                    .whenComplete((captured, failure) ->
                            finish.accept(registered, ranSuccessfully && Boolean.TRUE.equals(captured)));
        }
    }

    // A stage may settle without ever running its callback, for example if submission was rejected.
    static void completeTask(Object event, Throwable failure) {
        if (isCancellation(failure)) {
            cancel(event);
            return;
        }
        synchronized (EVENTS) {
            Event registered = EVENTS.remove(event);
            if (registered != null) {
                ScheduledOutput owner = registered.owner();
                owner.finishEvent(failure == null, failure == null);
            }
        }
    }

    private static boolean isCancellation(Throwable failure) {
        return failure instanceof CancellationException
                || (failure instanceof CompletionException && failure.getCause() instanceof CancellationException);
    }

    private void finishEvent(boolean captured, boolean ran) {
        synchronized (EVENTS) {
            pending--;
            complete &= captured;
            this.ran |= ran;
            finishIfReady();
        }
    }

    boolean seal() {
        synchronized (EVENTS) {
            sealed = true;
            finishIfReady();
            return scheduled;
        }
    }

    CompletableFuture<SudoCommands.Result> result() {
        return result;
    }

    void expire() {
        synchronized (EVENTS) {
            if (!result.isDone()) {
                finish("expired");
            }
        }
    }

    void abandon() {
        output.close();
        result.cancel(false);
        synchronized (EVENTS) {
            if (scheduled) {
                removeOwnedEvents();
            }
        }
    }

    private void finishIfReady() {
        if (sealed && pending == 0 && scheduled && !result.isDone()) {
            finish(!complete ? "partial" : ran ? "ok" : "cancelled");
        }
    }

    private void finish(String status) {
        if (pending != 0) {
            removeOwnedEvents();
        }
        var snapshot = output.finishFollowup();
        result.complete(new SudoCommands.Result(status, snapshot.text(), snapshot.truncated()));
    }

    private void removeOwnedEvents() {
        EVENTS.values().removeIf(event -> event.owner() == this);
    }
}
