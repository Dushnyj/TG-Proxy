package com.dushnyj.tgproxy;

import java.io.ByteArrayOutputStream;
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
                verifyTelegramDcResponse(ws, target, timeoutMs);
            } finally {
                try { ws.close(); } catch (Exception ignored) {}
            }
            return;
        }
        if (target.kind() == RoutePingTarget.Kind.WEBSOCKET) {
            RawWebSocket ws = RawWebSocket.connect(
                    target.host(), target.sni(), timeoutMs, target.path(), true);
            try {
                verifyTelegramDcResponse(ws, target, timeoutMs);
            } finally {
                try { ws.close(); } catch (Exception ignored) {}
            }
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), timeoutMs);
        }
    }

    private static void verifyTelegramDcResponse(RawWebSocket ws, RoutePingTarget target, int timeoutMs)
            throws Exception {
        if (ws == null || target == null || target.dc() <= 0) return;
        ws.setReadTimeout(timeoutMs);
        int dcIdx = target.media() ? -target.dc() : target.dc();
        byte[] relayInit = MtProtoCrypto.generateRelayInit(
                MtProtoCrypto.PROTO_TAG_INTERMEDIATE, dcIdx);
        MtProtoCrypto.TelegramTransport transport = MtProtoCrypto.telegramTransport(relayInit);
        byte[] nonce = MtProtoPingProbe.randomNonce();
        ws.send(relayInit);
        ws.send(MtProtoPingProbe.encryptedReqPqMulti(
                transport, nonce, MtProtoPingProbe.messageId(System.currentTimeMillis())));
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + Math.max(1_000L, timeoutMs);
        while (System.currentTimeMillis() <= deadline) {
            byte[] encryptedResponse = ws.recv();
            if (encryptedResponse == null || encryptedResponse.length == 0) break;
            plain.write(transport.decrypt(encryptedResponse));
            if (MtProtoPingProbe.isValidResPq(plain.toByteArray(), nonce)) return;
        }
        throw new java.io.IOException("telegram dc response is invalid");
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        String value = message.trim().replace('\r', ' ');
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline).trim() : value;
    }
}
