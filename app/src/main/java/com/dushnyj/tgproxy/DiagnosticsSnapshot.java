package com.dushnyj.tgproxy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class DiagnosticsSnapshot {
    private final ServiceState serviceState;
    private final NetworkProfile networkProfile;
    private final Map<String, RouteStats> routeStats;
    private final long activeConnections;
    private final long totalConnections;
    private final long bytesUp;
    private final long bytesDown;
    private final long engineErrors;
    private final long routeFailures;
    private final long uptimeMs;

    DiagnosticsSnapshot(ServiceState serviceState, NetworkProfile networkProfile,
                        Map<String, RouteStats> routeStats) {
        this(serviceState, networkProfile, routeStats, 0L, 0L, 0L, 0L, 0L,
                totalRouteFailures(routeStats), 0L);
    }

    DiagnosticsSnapshot(ServiceState serviceState, NetworkProfile networkProfile,
                        Map<String, RouteStats> routeStats, long activeConnections,
                        long totalConnections, long bytesUp, long bytesDown,
                        long engineErrors, long routeFailures, long uptimeMs) {
        this.serviceState = serviceState == null ? ServiceState.stopped() : serviceState;
        this.networkProfile = networkProfile == null
                ? NetworkProfile.defaultProfile() : networkProfile;
        this.routeStats = Collections.unmodifiableMap(copyStats(routeStats));
        this.activeConnections = Math.max(0L, activeConnections);
        this.totalConnections = Math.max(0L, totalConnections);
        this.bytesUp = Math.max(0L, bytesUp);
        this.bytesDown = Math.max(0L, bytesDown);
        this.engineErrors = Math.max(0L, engineErrors);
        this.routeFailures = Math.max(0L, routeFailures);
        this.uptimeMs = Math.max(0L, uptimeMs);
    }

    ServiceState serviceState() {
        return serviceState;
    }

    NetworkProfile networkProfile() {
        return networkProfile;
    }

    Map<String, RouteStats> routeStats() {
        return routeStats;
    }

    long activeConnections() {
        return activeConnections;
    }

    long totalConnections() {
        return totalConnections;
    }

    long bytesUp() {
        return bytesUp;
    }

    long bytesDown() {
        return bytesDown;
    }

    long engineErrors() {
        return engineErrors;
    }

    long routeFailures() {
        return routeFailures;
    }

    long uptimeMs() {
        return uptimeMs;
    }

    private static Map<String, RouteStats> copyStats(Map<String, RouteStats> source) {
        LinkedHashMap<String, RouteStats> copy = new LinkedHashMap<>();
        if (source == null) return copy;
        for (Map.Entry<String, RouteStats> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    static long totalRouteFailures(Map<String, RouteStats> source) {
        long total = 0L;
        if (source == null) return total;
        for (RouteStats stats : source.values()) {
            if (stats != null) total += stats.totalFailures();
        }
        return total;
    }
}
