package com.dushnyj.tgproxy;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;

enum RouteError {
    NONE,
    TOO_MANY_REQUESTS,
    TIMEOUT,
    RESET,
    DNS,
    TLS,
    IO,
    UNKNOWN;

    static RouteError classify(Exception error) {
        if (error == null) return UNKNOWN;
        if (error instanceof UnknownHostException) return DNS;
        if (error instanceof SocketTimeoutException) return TIMEOUT;
        if (error instanceof SSLException) return TLS;

        String message = error.getMessage();
        String normalized = message == null ? "" : message.toLowerCase(Locale.US);
        if (normalized.contains("429")) return TOO_MANY_REQUESTS;
        if (normalized.contains("timed out") || normalized.contains("timeout")) return TIMEOUT;
        if (normalized.contains("reset")) return RESET;
        if (normalized.contains("dns") || normalized.contains("unable to resolve")
                || normalized.contains("unknown host")) return DNS;
        if (normalized.contains("ssl") || normalized.contains("tls")
                || normalized.contains("handshake")) return TLS;
        if (error instanceof IOException) return IO;
        return UNKNOWN;
    }
}
