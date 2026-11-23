package cn.nightbugclub.mcqqauthcapture;

import java.util.function.Consumer;

final class CommandOutput {
    private static final BoundedCommandOutput.Snapshot EMPTY = new BoundedCommandOutput.Snapshot("", false);

    private BoundedCommandOutput initial = new BoundedCommandOutput();
    private BoundedCommandOutput followup;
    private boolean closed;

    // Keep a whole message in one buffer, even if a component is visited in several pieces.
    synchronized void append(Consumer<BoundedCommandOutput> message) {
        if (!closed) {
            message.accept(initial != null ? initial : followup());
        }
    }

    void appendLine(String line) {
        append(buffer -> buffer.appendLine(line));
    }

    synchronized void appendFollowupLine(String line) {
        if (!closed) {
            followup().appendLine(line);
        }
    }

    synchronized BoundedCommandOutput.Snapshot finishInitial() {
        var snapshot = initial == null ? EMPTY : initial.snapshot();
        initial = null;
        return snapshot;
    }

    synchronized BoundedCommandOutput.Snapshot finishFollowup() {
        var snapshot = followup == null ? EMPTY : followup.snapshot();
        close();
        return snapshot;
    }

    synchronized void close() {
        closed = true;
        initial = null;
        followup = null;
    }

    private BoundedCommandOutput followup() {
        if (followup == null) {
            followup = new BoundedCommandOutput();
        }
        return followup;
    }
}
