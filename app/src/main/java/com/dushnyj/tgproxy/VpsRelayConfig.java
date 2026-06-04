package com.dushnyj.tgproxy;

import java.util.Locale;

final class VpsRelayConfig {
    private final boolean enabled;
    private final String name;
    private final String host;
    private final int port;
    private final boolean tls;
    private final String path;
    private final String token;
    private final String profileKey;

    private VpsRelayConfig(boolean enabled, String name, String host, int port,
                           boolean tls, String path, String token, String profileKey) {
        this.enabled = enabled;
        this.name = valueOr(name, "VPS Relay");
        this.host = normalizeHost(host);
        this.port = port;
        this.tls = tls;
        this.path = normalizePath(path);
        this.token = valueOr(token, "");
        this.profileKey = valueOr(profileKey, "");
    }

    static VpsRelayConfig manual(boolean enabled, String name, String host, int port,
                                 boolean tls, String path, String token, String profileKey) {
        return new VpsRelayConfig(enabled, name, host, port, tls, path, token, profileKey);
    }

    static VpsRelayConfig disabled() {
        return new VpsRelayConfig(false, "", "", 0, true, "/apiws", "", "");
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean isUsable() {
        return enabled
                && !host.isEmpty()
                && port > 0
                && port <= 65535
                && !path.isEmpty()
                && !token.isEmpty();
    }

    boolean isAllowedForProfile(String currentProfileKey) {
        if (!isUsable()) return false;
        if (profileKey.isEmpty()) return true;
        return profileKey.equals(currentProfileKey == null ? "" : currentProfileKey.trim());
    }

    String name() {
        return name;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    boolean tls() {
        return tls;
    }

    String path() {
        return path;
    }

    String token() {
        return token;
    }

    String profileKey() {
        return profileKey;
    }

    VpsRelayConfig withProfileKey(String profileKey) {
        return new VpsRelayConfig(enabled, name, host, port, tls, path, token, profileKey);
    }

    String baseUrl() {
        return (tls ? "https://" : "http://") + host + ":" + port;
    }

    String maskedToken() {
        if (token.length() <= 8) return "****";
        return token.substring(0, Math.min(5, token.length()))
                + "****_"
                + token.substring(token.length() - 4);
    }

    private static String normalizeHost(String raw) {
        String value = valueOr(raw, "").toLowerCase(Locale.US);
        if (value.startsWith("https://")) value = value.substring("https://".length());
        else if (value.startsWith("http://")) value = value.substring("http://".length());
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value;
    }

    private static String normalizePath(String raw) {
        String value = valueOr(raw, "");
        if (value.isEmpty()) return "";
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String valueOr(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }
}
