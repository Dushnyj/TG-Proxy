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
    private final String relayVersion;
    private final String warning;
    private final VpsRelayCapabilities capabilities;

    private VpsRelayCheckResult(Status status, String message, String routeReport,
                                String relayVersion, String warning) {
        this(status, message, routeReport, relayVersion, warning,
                VpsRelayCapabilities.unknown());
    }

    private VpsRelayCheckResult(Status status, String message, String routeReport,
                                String relayVersion, String warning,
                                VpsRelayCapabilities capabilities) {
        this.status = status == null ? Status.UNAVAILABLE : status;
        this.message = message == null ? "" : message;
        this.routeReport = routeReport == null ? "" : routeReport;
        this.relayVersion = relayVersion == null ? "" : relayVersion.trim();
        this.warning = warning == null ? "" : warning.trim();
        this.capabilities = capabilities == null
                ? VpsRelayCapabilities.unknown() : capabilities;
    }

    static VpsRelayCheckResult of(Status status, String message) {
        return new VpsRelayCheckResult(status, message, "", "", "");
    }

    static VpsRelayCheckResult ok(String routeReport) {
        return ok(routeReport, "");
    }

    static VpsRelayCheckResult ok(String routeReport, String relayVersion) {
        return ok(routeReport, relayVersion, "");
    }

    static VpsRelayCheckResult ok(String routeReport, String relayVersion, String warning) {
        return new VpsRelayCheckResult(Status.OK, "relay is available", routeReport,
                relayVersion, warning);
    }

    static VpsRelayCheckResult ok(String routeReport, String relayVersion, String warning,
                                  VpsRelayCapabilities capabilities) {
        return new VpsRelayCheckResult(Status.OK, "relay is available", routeReport,
                relayVersion, warning, capabilities);
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

    String relayVersion() {
        return relayVersion;
    }

    String warning() {
        return warning;
    }

    VpsRelayCapabilities capabilities() { return capabilities; }
}
