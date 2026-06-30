package com.dushnyj.tgproxy;

final class ServiceState {
    static final long ROUTE_EVIDENCE_MAX_AGE_MS = 60_000L;

    enum Status {
        STOPPED,
        STARTING,
        ACTIVE,
        DEGRADED,
        RETRYING,
        DEAD,
        SLEEP,
        RECONNECTING
    }

    private final Status status;
    private final boolean serviceStarted;
    private final boolean engineRunning;
    private final boolean localPortListening;
    private final boolean paused;
    private final RouteState routeState;

    private ServiceState(Status status, boolean serviceStarted, boolean engineRunning,
                         boolean localPortListening, boolean paused, RouteState routeState) {
        this.status = status;
        this.serviceStarted = serviceStarted;
        this.engineRunning = engineRunning;
        this.localPortListening = localPortListening;
        this.paused = paused;
        this.routeState = routeState == null ? RouteState.inactive("no route") : routeState;
    }

    static ServiceState stopped() {
        return new ServiceState(Status.STOPPED, false, false, false, false,
                RouteState.inactive("service stopped"));
    }

    static ServiceState from(boolean serviceStarted, boolean engineRunning,
                             boolean localPortListening, boolean paused,
                             RouteState routeState) {
        return from(serviceStarted, engineRunning, localPortListening, paused, routeState,
                !engineRunning, false, System.currentTimeMillis());
    }

    static ServiceState from(boolean serviceStarted, boolean engineRunning,
                             boolean localPortListening, boolean paused,
                             RouteState routeState, boolean retrying, long nowMs) {
        return from(serviceStarted, engineRunning, localPortListening, paused, routeState,
                false, retrying, nowMs);
    }

    static ServiceState from(boolean serviceStarted, boolean engineRunning,
                             boolean localPortListening, boolean paused,
                             RouteState routeState, boolean starting, boolean retrying,
                             long nowMs) {
        Status status;
        if (!serviceStarted) {
            status = Status.STOPPED;
        } else if (paused) {
            status = Status.SLEEP;
        } else if (!engineRunning) {
            status = retrying ? Status.RETRYING : (starting ? Status.STARTING : Status.DEAD);
        } else if (!localPortListening || routeState == null || !routeState.active()
                || !routeState.isFresh(nowMs, ROUTE_EVIDENCE_MAX_AGE_MS)) {
            status = Status.DEGRADED;
        } else {
            status = Status.ACTIVE;
        }
        return new ServiceState(status, serviceStarted, engineRunning,
                localPortListening, paused, routeState);
    }

    Status status() {
        return status;
    }

    boolean isFullyActive() {
        return status == Status.ACTIVE;
    }

    boolean serviceStarted() {
        return serviceStarted;
    }

    boolean engineRunning() {
        return engineRunning;
    }

    boolean localPortListening() {
        return localPortListening;
    }

    boolean paused() {
        return paused;
    }

    RouteState routeState() {
        return routeState;
    }
}
