package com.dushnyj.tgproxy;

import java.net.InetSocketAddress;
import java.net.Socket;

final class LocalPortProbe {
    private LocalPortProbe() {}

    static boolean isListening(String host, int port, int timeoutMs) {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.max(100, timeoutMs));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
