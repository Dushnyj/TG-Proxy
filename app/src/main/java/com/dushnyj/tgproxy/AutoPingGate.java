package com.dushnyj.tgproxy;

final class AutoPingGate {
    private final long minIntervalMs;
    private boolean inFlight;
    private long lastStartedAtMs = Long.MIN_VALUE;
    private String inFlightRouteKey = "";

    AutoPingGate(long minIntervalMs) {
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }

    synchronized boolean tryStart(String routeKey, long nowMs) {
        if (inFlight) return false;
        if (lastStartedAtMs != Long.MIN_VALUE
                && nowMs - lastStartedAtMs < minIntervalMs) {
            return false;
        }
        inFlight = true;
        inFlightRouteKey = routeKey == null ? "" : routeKey;
        lastStartedAtMs = nowMs;
        return true;
    }

    synchronized void finish(String routeKey) {
        String key = routeKey == null ? "" : routeKey;
        if (key.equals(inFlightRouteKey)) {
            inFlight = false;
            inFlightRouteKey = "";
        }
    }

    synchronized void reset() {
        inFlight = false;
        inFlightRouteKey = "";
        lastStartedAtMs = Long.MIN_VALUE;
    }
}
