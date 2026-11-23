package cn.nightbugclub.mcqqauthcapture;

import static java.nio.charset.StandardCharsets.UTF_8;

final class BoundedCommandOutput {
    static final int MAX_BYTES = 64 * 1024;
    static final String TRUNCATION_NOTICE = "\n[输出超过 64 KiB，已截断。]\n";
    private static final int NOTICE_BYTES = TRUNCATION_NOTICE.getBytes(UTF_8).length;

    record Snapshot(String text, boolean truncated) {}

    private final StringBuilder text = new StringBuilder();
    private final int contentLimit;
    private int bytes;
    private boolean truncated;

    BoundedCommandOutput() {
        this(false);
    }

    BoundedCommandOutput(boolean streaming) {
        // A streamed prefix cannot be withdrawn to make room for the truncation notice.
        contentLimit = streaming ? MAX_BYTES - NOTICE_BYTES : MAX_BYTES;
    }

    synchronized boolean append(String part) {
        for (int offset = 0; !truncated && offset < part.length();) {
            int codePoint = part.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                codePoint = '?';
            }
            int size = utf8Bytes(codePoint);
            if (bytes + size <= contentLimit) {
                text.appendCodePoint(codePoint);
                bytes += size;
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
        return truncated;
    }

    synchronized Snapshot snapshot() {
        return snapshotFrom(0);
    }

    synchronized Snapshot snapshotFrom(int offset) {
        return new Snapshot(text.substring(offset), truncated && offset < text.length());
    }

    synchronized void appendLine(String line) {
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
            append("\n");
        }
        append(line);
        if (!line.endsWith("\n")) {
            append("\n");
        }
    }

    synchronized boolean truncated() {
        return truncated;
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
