package com.dushnyj.tgproxy;

import java.util.Locale;

final class RouteCandidate {
    private final RouteType type;
    private final int dc;
    private final boolean media;
    private final boolean test;
    private final String endpoint;
    private final int port;
    private final boolean enabled;
    private final String disabledReason;

    private RouteCandidate(RouteType type, int dc, boolean media, String endpoint,
                           int port, boolean enabled, String disabledReason) {
        this(type, dc, media, false, endpoint, port, enabled, disabledReason);
    }

    private RouteCandidate(RouteType type, int dc, boolean media, boolean test,
                           String endpoint, int port, boolean enabled, String disabledReason) {
        this.type = type;
        this.dc = dc;
        this.media = media;
        this.test = test;
        this.endpoint = normalize(endpoint);
        this.port = port;
        this.enabled = enabled;
        this.disabledReason = disabledReason == null ? "" : disabledReason;
    }

    static RouteCandidate directWs(int dc, boolean media, String targetIp) {
        return directWs(dc, media, false, targetIp);
    }

    static RouteCandidate directWs(int dc, boolean media, boolean test, String targetIp) {
        return new RouteCandidate(RouteType.DIRECT_WS, dc, media, test, targetIp, 443,
                !normalize(targetIp).isEmpty(), "");
    }

    static RouteCandidate publicCloudflare(int dc, String label) {
        return publicCloudflare(dc, false, label);
    }

    static RouteCandidate publicCloudflare(int dc, boolean media, String label) {
        return new RouteCandidate(RouteType.PUBLIC_CLOUDFLARE, dc, media, label, 443,
                true, "");
    }

    static RouteCandidate customCloudflare(int dc, String label) {
        return customCloudflare(dc, false, label);
    }

    static RouteCandidate customCloudflare(int dc, boolean media, String label) {
        return new RouteCandidate(RouteType.CUSTOM_CLOUDFLARE, dc, media, label, 443,
                true, "");
    }

    static RouteCandidate worker(int dc, String domain) {
        return worker(dc, false, domain);
    }

    static RouteCandidate worker(int dc, boolean media, String domain) {
        return worker(dc, media, false, domain);
    }

    static RouteCandidate worker(int dc, boolean media, boolean test, String domain) {
        return new RouteCandidate(RouteType.WORKER, dc, media, test, domain, 443,
                !normalize(domain).isEmpty(), "");
    }

    static RouteCandidate vpsRelay(String name, String host, int port) {
        return vpsRelay(name, host, port, 0, false);
    }

    static RouteCandidate vpsRelay(String name, String host, int port, int dc, boolean media) {
        return vpsRelay(name, host, port, dc, media, false);
    }

    static RouteCandidate vpsRelay(String name, String host, int port, int dc, boolean media,
                                   boolean test) {
        boolean configured = !normalize(host).isEmpty() && port > 0 && port <= 65535;
        return new RouteCandidate(RouteType.VPS_RELAY, dc, media, test,
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

    boolean test() {
        return test;
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
        String testScope = test ? ":test" : "";
        String mediaScope = media ? ":media" : "";
        return type.id() + scope + testScope + mediaScope;
    }

    String displayName() {
        return type.displayName();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
