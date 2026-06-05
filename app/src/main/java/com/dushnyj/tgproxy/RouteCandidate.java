package com.dushnyj.tgproxy;

import java.util.Locale;

final class RouteCandidate {
    private final RouteType type;
    private final int dc;
    private final boolean media;
    private final String endpoint;
    private final int port;
    private final boolean enabled;
    private final String disabledReason;

    private RouteCandidate(RouteType type, int dc, boolean media, String endpoint,
                           int port, boolean enabled, String disabledReason) {
        this.type = type;
        this.dc = dc;
        this.media = media;
        this.endpoint = normalize(endpoint);
        this.port = port;
        this.enabled = enabled;
        this.disabledReason = disabledReason == null ? "" : disabledReason;
    }

    static RouteCandidate directWs(int dc, boolean media, String targetIp) {
        return new RouteCandidate(RouteType.DIRECT_WS, dc, media, targetIp, 443,
                !normalize(targetIp).isEmpty(), "");
    }

    static RouteCandidate publicCloudflare(int dc, String label) {
        return new RouteCandidate(RouteType.PUBLIC_CLOUDFLARE, dc, false, label, 443,
                true, "");
    }

    static RouteCandidate customCloudflare(int dc, String label) {
        return new RouteCandidate(RouteType.CUSTOM_CLOUDFLARE, dc, false, label, 443,
                true, "");
    }

    static RouteCandidate worker(int dc, String domain) {
        return new RouteCandidate(RouteType.WORKER, dc, false, domain, 443,
                !normalize(domain).isEmpty(), "");
    }

    static RouteCandidate vpsRelay(String name, String host, int port) {
        return vpsRelay(name, host, port, 0, false);
    }

    static RouteCandidate vpsRelay(String name, String host, int port, int dc, boolean media) {
        boolean configured = !normalize(host).isEmpty() && port > 0 && port <= 65535;
        return new RouteCandidate(RouteType.VPS_RELAY, dc, media,
                configured ? host : name, port, configured,
                configured ? "" : "vps relay is not configured");
    }

    static RouteCandidate disabled(RouteType type, String reason) {
        return new RouteCandidate(type, 0, false, "", 0, false, reason);
    }

    RouteType type() {
        return type;
    }

    int dc() {
        return dc;
    }

    boolean media() {
        return media;
    }

    String endpoint() {
        return endpoint;
    }

    int port() {
        return port;
    }

    boolean enabled() {
        return enabled;
    }

    String disabledReason() {
        return disabledReason;
    }

    boolean requiresWarmup() {
        return type == RouteType.VPS_RELAY
                || type == RouteType.WORKER
                || type == RouteType.CUSTOM_CLOUDFLARE
                || type == RouteType.PUBLIC_CLOUDFLARE;
    }

    String key() {
        String scope = dc > 0 ? ":dc" + dc : "";
        String mediaScope = media ? ":media" : "";
        return type.id() + scope + mediaScope;
    }

    String displayName() {
        return type.displayName();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
