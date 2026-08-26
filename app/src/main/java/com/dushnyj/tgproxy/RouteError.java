package com.dushnyj.tgproxy;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;

enum RouteError {
    NONE,
    TOO_MANY_REQUESTS,
    HTTP_FORBIDDEN,
    HTTP_UNAVAILABLE,
    TIMEOUT,
    RESET,
    DNS,
    TLS,
    WS_PROTOCOL,
    RELAY_AUTH,
    RELAY_INIT,
    FIRST_BYTE_TIMEOUT,
    REMOTE_EOF,
    CANCELLED,
    IO,
    UNKNOWN;

    static RouteError classify(Exception error) {
        if (error == null) return UNKNOWN;
        if (error instanceof UnknownHostException) return DNS;
        if (error instanceof SocketTimeoutException) return TIMEOUT;
        if (error instanceof SSLException) return TLS;
        if (error instanceof RawWebSocket.WebSocketCloseException) {
            int code = ((RawWebSocket.WebSocketCloseException) error).code;
            return code == 1000 || code == 1001 || code == 1005
                    ? REMOTE_EOF : WS_PROTOCOL;
        }
        if (error instanceof RawWebSocket.WsProtocolException) return WS_PROTOCOL;
        if (error instanceof RawWebSocket.WsHandshakeException) {
            int status = ((RawWebSocket.WsHandshakeException) error).statusCode;
            if (status == 401) return RELAY_AUTH;
            if (status == 403) return HTTP_FORBIDDEN;
            if (status == 429) return TOO_MANY_REQUESTS;
            if (status == 502 || status == 503 || status == 504) return HTTP_UNAVAILABLE;
            return WS_PROTOCOL;
        }

        String message = error.getMessage();
        String normalized = message == null ? "" : message.toLowerCase(Locale.US);
        if (normalized.contains("429")) return TOO_MANY_REQUESTS;
        if (normalized.contains("401") || normalized.contains("relay token")
                || normalized.contains("unauthorized")) return RELAY_AUTH;
        if (normalized.contains("403")) return HTTP_FORBIDDEN;
        if (normalized.contains("http 502") || normalized.contains("http 503")
                || normalized.contains("http 504") || normalized.contains("handshake failed: 502")
                || normalized.contains("handshake failed: 503")
                || normalized.contains("handshake failed: 504")) return HTTP_UNAVAILABLE;
        if (normalized.contains("first byte")) return FIRST_BYTE_TIMEOUT;
        if (normalized.contains("remote eof")) return REMOTE_EOF;
        if (normalized.contains("cancelled") || normalized.contains("canceled")) return CANCELLED;
        if (normalized.contains("timed out") || normalized.contains("timeout")) return TIMEOUT;
        if (normalized.contains("reset")) return RESET;
        if (normalized.contains("dns") || normalized.contains("unable to resolve")
                || normalized.contains("unknown host")) return DNS;
        if (normalized.contains("ssl") || normalized.contains("tls")) return TLS;
        if (normalized.contains("websocket") || normalized.contains("ws handshake")) return WS_PROTOCOL;
        if (error instanceof IOException) return IO;
        return UNKNOWN;
    }

    static boolean isCleanSessionClose(Exception error) {
        if (!(error instanceof RawWebSocket.WebSocketCloseException)) return false;
        int code = ((RawWebSocket.WebSocketCloseException) error).code;
        return code == 1000 || code == 1001 || code == 1005;
    }
}
