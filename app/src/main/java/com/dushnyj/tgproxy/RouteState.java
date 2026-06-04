package com.dushnyj.tgproxy;

final class RouteState {
    private final boolean active;
    private final RouteCandidate candidate;
    private final String activeEndpoint;
    private final int pingMs;
    private final String quality;
    private final String reason;

    private RouteState(boolean active, RouteCandidate candidate, String activeEndpoint,
                       int pingMs, String quality, String reason) {
        this.active = active;
        this.candidate = candidate;
        this.activeEndpoint = activeEndpoint == null ? "" : activeEndpoint;
        this.pingMs = pingMs;
        this.quality = quality == null ? "" : quality;
        this.reason = reason == null ? "" : reason;
    }

    static RouteState active(RouteCandidate candidate, String activeEndpoint,
                             int pingMs, String quality) {
        return new RouteState(candidate != null, candidate, activeEndpoint, pingMs, quality, "");
    }

    static RouteState inactive(String reason) {
        return new RouteState(false, null, "", -1, "", reason);
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

    int pingMs() {
        return pingMs;
    }

    String quality() {
        return quality;
    }

    String reason() {
        return reason;
    }

    String displayName() {
        return candidate == null ? "-" : candidate.displayName();
    }
}
