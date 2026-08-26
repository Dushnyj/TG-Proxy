package com.dushnyj.tgproxy;

final class AutoPingGate {
    private final long minIntervalMs;
    private boolean inFlight;
    private long lastStartedAtMs = Long.MIN_VALUE;
    private long nextToken = 1L;
    private long inFlightToken;

    AutoPingGate(long minIntervalMs) {
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }

    synchronized long tryStart(String routeIdentity, long nowMs) {
        if (inFlight) return 0L;
        if (lastStartedAtMs != Long.MIN_VALUE
                && nowMs - lastStartedAtMs < minIntervalMs) {
            return 0L;
        }
        inFlight = true;
        inFlightToken = nextToken++;
        if (nextToken <= 0L) nextToken = 1L;
        lastStartedAtMs = nowMs;
        return inFlightToken;
    }

    synchronized boolean finish(long token) {
        if (!inFlight || token <= 0L || token != inFlightToken) return false;
        inFlight = false;
        inFlightToken = 0L;
        return true;
    }

    synchronized void reset() {
        inFlight = false;
        inFlightToken = 0L;
        lastStartedAtMs = Long.MIN_VALUE;
    }
}
