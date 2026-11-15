package cn.nightbugclub.mcqqauth;

import java.util.function.LongSupplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

final class BoundedCommandOutput {
    static final int MAX_BYTES = 64 * 1024;
    static final long MAX_WAIT_MS = 3_000;
    private static final long QUIET_NANOS = MILLISECONDS.toNanos(500);
    static final String TRUNCATION_NOTICE = "\n[输出超过 64 KiB，已截断。]\n";
    private static final int NOTICE_BYTES = TRUNCATION_NOTICE.getBytes(UTF_8).length;

    record Snapshot(String text, boolean truncated) {}

    private final StringBuilder text = new StringBuilder();
    private final LongSupplier clock;
    private int bytes;
    private boolean truncated;
    private boolean closed;
    private Long deadline;
    private Long lastVisibleOutput;

    BoundedCommandOutput() {
        this(System::nanoTime);
    }

    BoundedCommandOutput(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized boolean append(String part) {
        if (!acceptingOutput()) {
            return true;
        }
        boolean visible = false;
        for (int offset = 0; !truncated && offset < part.length();) {
            int codePoint = part.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                codePoint = '?';
            }
            int size = utf8Bytes(codePoint);
            if (bytes + size <= MAX_BYTES) {
                text.appendCodePoint(codePoint);
                bytes += size;
                visible = visible || !Character.isISOControl(codePoint)
                        && !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint);
            } else {
                while (bytes + NOTICE_BYTES > MAX_BYTES) {
                    int last = text.codePointBefore(text.length());
                    text.setLength(text.length() - Character.charCount(last));
                    bytes -= utf8Bytes(last);
                }
                text.append(TRUNCATION_NOTICE);
                truncated = true;
            }
        }
        if (visible) {
            lastVisibleOutput = clock.getAsLong();
        }
        return truncated;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(text.toString(), truncated);
    }

    synchronized void appendLine(String line) {
        append(line);
        if (!line.endsWith("\n")) {
            append("\n");
        }
    }

    synchronized boolean acceptingOutput() {
        if (closed || truncated) {
            return false;
        }
        if (deadline == null) {
            return true;
        }
        long now = clock.getAsLong();
        return now - deadline < 0 && (lastVisibleOutput == null || now - lastVisibleOutput < QUIET_NANOS);
    }

    synchronized void collectFor(long millis) {
        long now = clock.getAsLong();
        deadline = now + MILLISECONDS.toNanos(millis);
        if (lastVisibleOutput != null) {
            lastVisibleOutput = now;
        }
    }

    synchronized Snapshot finishIfReady() {
        return acceptingOutput() ? null : finish();
    }

    synchronized Snapshot finish() {
        close();
        return snapshot();
    }

    synchronized void close() {
        closed = true;
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        return codePoint <= 0xffff ? 3 : 4;
    }
}
