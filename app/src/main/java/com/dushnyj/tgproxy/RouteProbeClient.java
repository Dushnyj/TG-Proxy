package com.dushnyj.tgproxy;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

final class RouteProbeClient {
    private RouteProbeClient() {}

    static Integer measureFirst(List<RoutePingTarget> targets, int timeoutMs) {
        if (targets == null || targets.isEmpty()) return null;
        for (RoutePingTarget target : targets) {
            long start = System.currentTimeMillis();
            try {
                connect(target, timeoutMs);
                return (int) (System.currentTimeMillis() - start);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static void connect(RoutePingTarget target, int timeoutMs) throws Exception {
        if (target.kind() == RoutePingTarget.Kind.WEBSOCKET) {
            RawWebSocket ws = RawWebSocket.connect(
                    target.host(), target.sni(), timeoutMs, target.path(), true);
            try { ws.close(); } catch (Exception ignored) {}
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), timeoutMs);
        }
    }
}
