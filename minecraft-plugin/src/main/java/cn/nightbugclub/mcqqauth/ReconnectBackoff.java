package cn.nightbugclub.mcqqauth;

final class ReconnectBackoff {
    private final int minMs;
    private final int maxMs;
    private int nextMs;

    ReconnectBackoff(int minMs, int maxMs) {
        this.minMs = minMs;
        this.maxMs = maxMs;
        reset();
    }

    int nextDelayMs() {
        int delay = nextMs;
        nextMs = Math.min(nextMs * 2, maxMs);
        return delay;
    }

    void reset() {
        nextMs = minMs;
    }
}
