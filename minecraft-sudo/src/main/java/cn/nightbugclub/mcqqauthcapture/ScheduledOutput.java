package cn.nightbugclub.mcqqauthcapture;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class ScheduledOutput {
    static final int WAIT_SECONDS = 3600;
    private static final int MAX_EVENTS = 512;
    private static final Map<Object, ScheduledOutput> EVENTS = new IdentityHashMap<>();

    private final BoundedCommandOutput output = new BoundedCommandOutput();
    private final CompletableFuture<SudoCommands.Result> result = new CompletableFuture<>();
    private int pending;
    private boolean scheduled;
    private boolean sealed;
    private boolean ran;
    private boolean complete = true;

    public static void enqueue(Object event) {
        ScheduledOutput owner = OutputCapture.scheduledOutput();
        if (owner == null) {
            return;
        }
        synchronized (EVENTS) {
            if (owner.result.isDone()) {
                return;
            }
            owner.scheduled = true;
            if (EVENTS.size() >= MAX_EVENTS) {
                owner.complete = false;
                return;
            }
            EVENTS.put(event, owner);
            owner.pending++;
        }
    }

    public static void cancel(Object event) {
        synchronized (EVENTS) {
            ScheduledOutput owner = EVENTS.remove(event);
            if (owner != null) {
                owner.pending--;
                owner.output.appendLine("已取消一项预约函数。");
                owner.finishIfReady();
            }
        }
    }

    public static void run(Object event, Runnable callback) {
        ScheduledOutput owner;
        synchronized (EVENTS) {
            owner = EVENTS.remove(event);
            if (owner != null) {
                owner.ran = true;
            }
        }
        if (owner == null || owner.result.isDone()) {
            OutputCapture.runQueuedTask(callback);
            return;
        }

        Supplier<CompletableFuture<Boolean>> closeCapture;
        try {
            closeCapture = OutputCapture.begin(owner.output::appendLine, owner);
        } catch (RuntimeException | LinkageError failure) {
            try {
                OutputCapture.runQueuedTask(callback);
            } finally {
                owner.finishEvent(false);
            }
            return;
        }
        boolean succeeded = false;
        try {
            callback.run();
            succeeded = true;
        } finally {
            boolean ranSuccessfully = succeeded;
            closeCapture.get().completeOnTimeout(false, 30, TimeUnit.SECONDS)
                    .whenComplete((captured, failure) ->
                            owner.finishEvent(ranSuccessfully && Boolean.TRUE.equals(captured)));
        }
    }

    private void finishEvent(boolean captured) {
        synchronized (EVENTS) {
            pending--;
            complete &= captured;
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
        return result.completeOnTimeout(new SudoCommands.Result("expired"), WAIT_SECONDS, TimeUnit.SECONDS);
    }

    void abandon() {
        result.cancel(false);
        synchronized (EVENTS) {
            EVENTS.values().removeIf(owner -> owner == this);
        }
    }

    private void finishIfReady() {
        if (sealed && pending == 0 && scheduled) {
            var snapshot = output.snapshot();
            String status = !complete ? "partial" : ran ? "ok" : "cancelled";
            result.complete(new SudoCommands.Result(status, snapshot.text(), snapshot.truncated()));
        }
    }
}
