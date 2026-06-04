package com.dushnyj.tgproxy;

final class VpsSshCredentials {
    private final String host;
    private final int port;
    private final String user;
    private final String password;

    VpsSshCredentials(String host, int port, String user, String password) {
        this.host = clean(host);
        this.port = port <= 0 || port > 65535 ? 22 : port;
        this.user = clean(user);
        this.password = password == null ? "" : password;
    }

    boolean isValid() {
        return !host.isEmpty() && port > 0 && port <= 65535 && !user.isEmpty();
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String user() {
        return user;
    }

    String password() {
        return password;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
