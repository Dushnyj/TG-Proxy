package com.dushnyj.tgproxy;

import java.util.Locale;

final class RoutePingTarget {
    enum Kind {
        TCP,
        WEBSOCKET,
        VPS_RELAY
    }

    private final Kind kind;
    private final String host;
    private final String sni;
    private final int port;
    private final String path;
    private final int dc;
    private final boolean media;
    private final boolean test;
    private final VpsRelayConfig relayConfig;
    private final boolean updatesRouteState;

    private RoutePingTarget(Kind kind, String host, String sni, int port,
                            String path, int dc, boolean media, VpsRelayConfig relayConfig,
                            boolean test, boolean updatesRouteState) {
        this.kind = kind;
        this.host = host == null ? "" : host;
        this.sni = sni == null ? "" : sni;
        this.port = port;
        this.path = path == null ? "" : path;
        this.dc = dc;
        this.media = media;
        this.test = test;
        this.relayConfig = relayConfig;
        this.updatesRouteState = updatesRouteState;
    }

    static RoutePingTarget tcp(String host, int port) {
        return new RoutePingTarget(Kind.TCP, host, host, port, "",
                0, false, null, false, false);
    }

    static RoutePingTarget websocket(String host, String sni, String path) {
        return websocket(host, sni, path, 0, false);
    }

    static RoutePingTarget websocket(String host, String sni, String path,
                                     int dc, boolean media) {
        return websocket(host, sni, path, dc, media, false);
    }

    static RoutePingTarget websocket(String host, String sni, String path,
                                     int dc, boolean media, boolean test) {
        return new RoutePingTarget(Kind.WEBSOCKET, host, sni, 443, path,
                dc, media, null, test, false);
    }

    static RoutePingTarget relay(VpsRelayConfig config, int dc, boolean media) {
        return relay(config, dc, media, false);
    }

    static RoutePingTarget relay(VpsRelayConfig config, int dc, boolean media, boolean test) {
        VpsRelayConfig safeConfig = config == null ? VpsRelayConfig.disabled() : config;
        return new RoutePingTarget(Kind.VPS_RELAY, safeConfig.host(), safeConfig.host(),
                safeConfig.port(), safeConfig.path(), dc, media, safeConfig, test, false);
    }

    Kind kind() {
        return kind;
    }

    String host() {
        return host;
    }

    String sni() {
        return sni;
    }

    int port() {
        return port;
    }

    String path() {
        return path;
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

    VpsRelayConfig relayConfig() {
        return relayConfig;
    }

    boolean updatesRouteState() {
        return updatesRouteState;
    }

    String safeLabel() {
        String route = kind.name().toLowerCase(Locale.ROOT);
        String target = host.isEmpty() ? "-" : host;
        String scope = dc > 0 ? " dc=" + dc + (test ? ":test" : "")
                + (media ? ":media" : ":main") : "";
        return route + " " + target + path + scope;
    }
}
