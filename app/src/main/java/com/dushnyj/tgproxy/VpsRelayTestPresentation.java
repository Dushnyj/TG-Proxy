package com.dushnyj.tgproxy;

import java.util.Locale;

/** Converts low-level Relay probe failures into stable, user-facing result categories. */
final class VpsRelayTestPresentation {
    enum Kind {
        SUCCESS,
        SUCCESS_WITH_TEST_DC_WARNING,
        INVALID_SETTINGS,
        WRONG_TOKEN,
        TLS,
        OUTDATED,
        DNS,
        TIMEOUT,
        TELEGRAM_DC,
        SERVER_UNAVAILABLE
    }

    private final Kind kind;

    private VpsRelayTestPresentation(Kind kind) {
        this.kind = kind == null ? Kind.SERVER_UNAVAILABLE : kind;
    }

    static VpsRelayTestPresentation from(VpsRelayCheckResult result) {
        if (result == null) return new VpsRelayTestPresentation(Kind.SERVER_UNAVAILABLE);
        if (result.status() == VpsRelayCheckResult.Status.OK) {
            return new VpsRelayTestPresentation(result.warning().isEmpty()
                    ? Kind.SUCCESS : Kind.SUCCESS_WITH_TEST_DC_WARNING);
        }
        if (result.status() == VpsRelayCheckResult.Status.BAD_CONFIG) {
            return new VpsRelayTestPresentation(Kind.INVALID_SETTINGS);
        }
        if (result.status() == VpsRelayCheckResult.Status.WRONG_TOKEN) {
            return new VpsRelayTestPresentation(Kind.WRONG_TOKEN);
        }
        if (result.status() == VpsRelayCheckResult.Status.TLS_ERROR) {
            return new VpsRelayTestPresentation(Kind.TLS);
        }
        if (result.status() == VpsRelayCheckResult.Status.OUTDATED_VERSION) {
            return new VpsRelayTestPresentation(Kind.OUTDATED);
        }
        String message = result.message() == null
                ? "" : result.message().toLowerCase(Locale.ROOT);
        if (message.contains("unknownhost") || message.contains("dns")
                || message.contains("resolve") || message.contains("no address")) {
            return new VpsRelayTestPresentation(Kind.DNS);
        }
        if (message.contains("telegram") || message.contains("respq")
                || message.contains("req_pq") || message.contains("dc")
                || message.contains("test-routes") || message.contains("route report")) {
            return new VpsRelayTestPresentation(Kind.TELEGRAM_DC);
        }
        if (message.contains("deadline") || message.contains("timeout")
                || message.contains("timed out")) {
            return new VpsRelayTestPresentation(Kind.TIMEOUT);
        }
        return new VpsRelayTestPresentation(Kind.SERVER_UNAVAILABLE);
    }

    Kind kind() {
        return kind;
    }

    boolean successful() {
        return kind == Kind.SUCCESS || kind == Kind.SUCCESS_WITH_TEST_DC_WARNING;
    }
}
