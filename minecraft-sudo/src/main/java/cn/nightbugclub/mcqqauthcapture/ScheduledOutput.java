package cn.nightbugclub.mcqqauthcapture;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class ScheduledOutput {
    static final int WAIT_SECONDS = 3600;
    static final int DIRECT_REPLY_WAIT_SECONDS = 30;
    private static final int MAX_EVENTS = 512;
    private static final Map<Object, Event> EVENTS = new IdentityHashMap<>();

    private record Event(ScheduledOutput owner, String cancellation) {}

    private final CommandOutput output = new CommandOutput();
    private final CompletableFuture<SudoCommands.Result> result = new CompletableFuture<>();
    private int pending;
    private boolean scheduled;
    private boolean sealed;
    private boolean ran;
    private boolean complete = true;

    CommandOutput output() {
        return output;
    }

    public static void enqueue(Object event) {
        enqueue(event, "已取消一项预约函数。");
    }

    public static void enqueueTask(Object task) {
        enqueue(task, "已取消一项后台任务。");
    }

    private static void enqueue(Object event, String cancellation) {
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
            EVENTS.put(event, new Event(owner, cancellation));
            owner.pending++;
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
        ScheduledOutput owner;
        synchronized (EVENTS) {
            Event registered = EVENTS.remove(event);
            owner = registered == null ? null : registered.owner();
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
            closeCapture = OutputCapture.begin(owner.output::appendFollowupLine, owner);
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
                EVENTS.values().removeIf(event -> event.owner() == this);
            }
        }
    }

    private void finishIfReady() {
        if (sealed && pending == 0 && scheduled && !result.isDone()) {
            finish(!complete ? "partial" : ran ? "ok" : "cancelled");
        }
    }

    private void finish(String status) {
        var snapshot = output.finishFollowup();
        result.complete(new SudoCommands.Result(status, snapshot.text(), snapshot.truncated()));
    }
}
