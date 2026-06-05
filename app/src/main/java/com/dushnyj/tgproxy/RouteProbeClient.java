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
            } catch (Exception error) {
                DiagnosticsLog.record("route ping target failed "
                        + target.safeLabel() + " " + RouteError.classify(error).name()
                        + " " + firstLine(error.getMessage()));
            }
        }
        return null;
    }

    static void connect(RoutePingTarget target, int timeoutMs) throws Exception {
        if (target.kind() == RoutePingTarget.Kind.VPS_RELAY) {
            RawWebSocket ws = RawWebSocket.connectRelay(target.relayConfig(),
                    target.dc(), target.media(), timeoutMs);
            try {
                sendSyntheticMtProtoInit(ws, target);
            } finally {
                try { ws.close(); } catch (Exception ignored) {}
            }
            return;
        }
        if (target.kind() == RoutePingTarget.Kind.WEBSOCKET) {
            RawWebSocket ws = RawWebSocket.connect(
                    target.host(), target.sni(), timeoutMs, target.path(), true);
            try {
                sendSyntheticMtProtoInit(ws, target);
            } finally {
                try { ws.close(); } catch (Exception ignored) {}
            }
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), timeoutMs);
        }
    }

    private static void sendSyntheticMtProtoInit(RawWebSocket ws, RoutePingTarget target)
            throws Exception {
        if (ws == null || target == null || target.dc() <= 0) return;
        int dcIdx = target.media() ? -target.dc() : target.dc();
        ws.send(MtProtoCrypto.generateRelayInit(
                MtProtoCrypto.PROTO_TAG_INTERMEDIATE, dcIdx));
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        String value = message.trim().replace('\r', ' ');
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline).trim() : value;
    }
}
