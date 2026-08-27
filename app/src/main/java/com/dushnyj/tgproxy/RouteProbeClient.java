package com.dushnyj.tgproxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

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
        ConnectBudget budget = new ConnectBudget(Math.max(1_000L, timeoutMs));
        if (target.kind() == RoutePingTarget.Kind.VPS_RELAY) {
            RawWebSocket ws = RawWebSocket.connectRelay(target.relayConfig(),
                    target.dc(), target.media(), target.test(), budget, timeoutMs, null);
            try {
                verifyTelegramDcResponse(ws, target, budget, timeoutMs);
            } finally {
                try { ws.abort(); } catch (Exception ignored) {}
            }
            return;
        }
        if (target.kind() == RoutePingTarget.Kind.WEBSOCKET) {
            RawWebSocket ws = RawWebSocket.connect(
                    target.host(), target.sni(), target.path(), budget, timeoutMs);
            try {
                verifyTelegramDcResponse(ws, target, budget, timeoutMs);
            } finally {
                try { ws.abort(); } catch (Exception ignored) {}
            }
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), timeoutMs);
        }
    }

    private static void verifyTelegramDcResponse(RawWebSocket ws, RoutePingTarget target,
                                                 ConnectBudget budget, int timeoutMs)
            throws Exception {
        if (ws == null || target == null || target.dc() <= 0) return;
        verifyTelegramDcResponse(ws, target.dc(), target.media(), budget, timeoutMs);
    }

    static void verifyTelegramDcResponse(RawWebSocket ws, int dc, boolean media, int timeoutMs)
            throws Exception {
        verifyTelegramDcResponse(ws, dc, media,
                new ConnectBudget(Math.max(1_000L, timeoutMs)), timeoutMs);
    }

    static void verifyTelegramDcResponse(RawWebSocket ws, int dc, boolean media,
                                         ConnectBudget budget, int timeoutMs)
            throws Exception {
        if (ws == null || dc <= 0) throw new java.io.IOException("invalid Telegram probe target");
        if (budget == null) budget = new ConnectBudget(Math.max(1_000L, timeoutMs));
        ScheduledFuture<?> deadlineAbort = ws.abortAtDeadline(budget);
        int dcIdx = media ? -dc : dc;
        byte[] relayInit = MtProtoCrypto.generateRelayInit(
                MtProtoCrypto.PROTO_TAG_INTERMEDIATE, dcIdx);
        MtProtoCrypto.TelegramTransport transport = MtProtoCrypto.telegramTransport(relayInit);
        byte[] nonce = MtProtoPingProbe.randomNonce();
        try {
            ws.send(relayInit);
            ws.send(MtProtoPingProbe.encryptedReqPqMulti(
                    transport, nonce, MtProtoPingProbe.messageId(System.currentTimeMillis())));
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            while (budget.hasTime()) {
                byte[] encryptedResponse;
                try {
                    encryptedResponse = ws.recv(budget, timeoutMs);
                } catch (IOException error) {
                    throw normalizeReadFailure(error, budget.hasTime());
                }
                if (encryptedResponse == null || encryptedResponse.length == 0) break;
                plain.write(transport.decrypt(encryptedResponse));
                if (MtProtoPingProbe.isValidResPq(plain.toByteArray(), nonce)) return;
            }
        } finally {
            if (deadlineAbort != null) deadlineAbort.cancel(false);
        }
        throw invalidResponseFailure(budget.hasTime());
    }

    static IOException invalidResponseFailure(boolean budgetHasTime) {
        if (!budgetHasTime) {
            return new SocketTimeoutException("telegram probe deadline exceeded");
        }
        return new IOException("telegram dc response is invalid");
    }

    static IOException normalizeReadFailure(IOException error, boolean budgetHasTime) {
        if (error instanceof RawWebSocket.WebSocketCloseException || budgetHasTime) {
            return error;
        }
        SocketTimeoutException timeout =
                new SocketTimeoutException("telegram probe deadline exceeded");
        timeout.initCause(error);
        return timeout;
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        String value = message.trim().replace('\r', ' ');
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline).trim() : value;
    }
}
