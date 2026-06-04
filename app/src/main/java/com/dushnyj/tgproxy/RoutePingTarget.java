package com.dushnyj.tgproxy;

final class RoutePingTarget {
    enum Kind {
        TCP,
        WEBSOCKET
    }

    private final Kind kind;
    private final String host;
    private final String sni;
    private final int port;
    private final String path;
    private final boolean updatesRouteState;

    private RoutePingTarget(Kind kind, String host, String sni, int port,
                            String path, boolean updatesRouteState) {
        this.kind = kind;
        this.host = host == null ? "" : host;
        this.sni = sni == null ? "" : sni;
        this.port = port;
        this.path = path == null ? "" : path;
        this.updatesRouteState = updatesRouteState;
    }

    static RoutePingTarget tcp(String host, int port) {
        return new RoutePingTarget(Kind.TCP, host, host, port, "", false);
    }

    static RoutePingTarget websocket(String host, String sni, String path) {
        return new RoutePingTarget(Kind.WEBSOCKET, host, sni, 443, path, false);
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

    boolean updatesRouteState() {
        return updatesRouteState;
    }
}
