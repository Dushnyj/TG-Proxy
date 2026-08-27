package com.dushnyj.tgproxy;

final class RouteState {
    private final boolean active;
    private final RouteCandidate candidate;
    private final String activeEndpoint;
    private final String activeSni;
    private final int pingMs;
    private final String quality;
    private final String reason;
    private final long verifiedAtMs;
    private final long routeGeneration;

    private RouteState(boolean active, RouteCandidate candidate, String activeEndpoint,
                       String activeSni, int pingMs, String quality, String reason,
                       long verifiedAtMs, long routeGeneration) {
        this.active = active;
        this.candidate = candidate;
        this.activeEndpoint = activeEndpoint == null ? "" : activeEndpoint;
        this.activeSni = activeSni == null ? "" : activeSni;
        this.pingMs = pingMs;
        this.quality = quality == null ? "" : quality;
        this.reason = reason == null ? "" : reason;
        this.verifiedAtMs = Math.max(0L, verifiedAtMs);
        this.routeGeneration = Math.max(0L, routeGeneration);
    }

    static RouteState active(RouteCandidate candidate, String activeEndpoint,
                             int pingMs, String quality) {
        return active(candidate, activeEndpoint, pingMs, quality, System.currentTimeMillis());
    }

    static RouteState active(RouteCandidate candidate, String activeEndpoint,
                             int pingMs, String quality, long verifiedAtMs) {
        return active(candidate, activeEndpoint, "", pingMs, quality, verifiedAtMs);
    }

    static RouteState active(RouteCandidate candidate, String activeEndpoint, String activeSni,
                             int pingMs, String quality, long verifiedAtMs) {
        return active(candidate, activeEndpoint, activeSni, pingMs, quality, verifiedAtMs, 0L);
    }

    static RouteState active(RouteCandidate candidate, String activeEndpoint, String activeSni,
                             int pingMs, String quality, long verifiedAtMs,
                             long routeGeneration) {
        return new RouteState(candidate != null, candidate, activeEndpoint, activeSni, pingMs,
                quality, "", verifiedAtMs, routeGeneration);
    }

    static RouteState inactive(String reason) {
        return new RouteState(false, null, "", "", -1, "", reason, 0L, 0L);
    }

    boolean active() {
        return active;
    }

    RouteCandidate candidate() {
        return candidate;
    }

    RouteType type() {
        return candidate == null ? null : candidate.type();
    }

    String key() {
        return candidate == null ? "" : candidate.key();
    }

    String activeEndpoint() {
        return activeEndpoint;
    }

    String activeSni() {
        return activeSni;
    }

    int pingMs() {
        return pingMs;
    }

    String quality() {
        return quality;
    }

    String reason() {
        return reason;
    }

    long verifiedAtMs() {
        return verifiedAtMs;
    }

    long routeGeneration() {
        return routeGeneration;
    }

    boolean isFresh(long nowMs, long maxAgeMs) {
        if (!active) return false;
        if (verifiedAtMs <= 0L) return false;
        long now = Math.max(0L, nowMs);
        if (now < verifiedAtMs) return true;
        return now - verifiedAtMs <= Math.max(0L, maxAgeMs);
    }

    String displayName() {
        return candidate == null ? "-" : candidate.displayName();
    }
}
