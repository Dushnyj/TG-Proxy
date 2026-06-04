package com.dushnyj.tgproxy;

final class VpsRelayCheckResult {
    enum Status {
        OK,
        BAD_CONFIG,
        WRONG_TOKEN,
        TLS_ERROR,
        OUTDATED_VERSION,
        UNAVAILABLE
    }

    private final Status status;
    private final String message;
    private final String routeReport;

    private VpsRelayCheckResult(Status status, String message, String routeReport) {
        this.status = status == null ? Status.UNAVAILABLE : status;
        this.message = message == null ? "" : message;
        this.routeReport = routeReport == null ? "" : routeReport;
    }

    static VpsRelayCheckResult of(Status status, String message) {
        return new VpsRelayCheckResult(status, message, "");
    }

    static VpsRelayCheckResult ok(String routeReport) {
        return new VpsRelayCheckResult(Status.OK, "relay is available", routeReport);
    }

    Status status() {
        return status;
    }

    String message() {
        return message;
    }

    String routeReport() {
        return routeReport;
    }
}
