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
                && host.length() <= 253
                && host.matches("[a-z0-9._:%-]+")
                && port > 0
                && port <= 65535
                && isValidPath(path)
                && !token.isEmpty()
                && token.length() <= 512
                && !containsHeaderUnsafe(token);
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

    boolean sameRoutingIdentity(VpsRelayConfig other) {
        if (other == null) return false;
        return enabled == other.enabled
                && port == other.port
                && tls == other.tls
                && host.equals(other.host)
                && path.equals(other.path)
                && token.equals(other.token)
                && profileKey.equals(other.profileKey);
    }

    boolean sameEndpoint(VpsRelayConfig other) {
        if (other == null) return false;
        return port == other.port
                && tls == other.tls
                && host.equals(other.host)
                && path.equals(other.path);
    }

    String baseUrl() {
        String authority = host.indexOf(':') >= 0 && !host.startsWith("[")
                ? "[" + host + "]" : host;
        return (tls ? "https://" : "http://") + authority + ":" + port;
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
        if (value.startsWith("[")) {
            int closing = value.indexOf(']');
            if (closing > 1) return value.substring(1, closing);
        }
        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            String suffix = value.substring(firstColon + 1);
            if (suffix.matches("\\d{1,5}")) value = value.substring(0, firstColon);
        }
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

    private static boolean containsHttpUnsafe(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    /** Mirrors the Relay path contract so an imported/manual profile cannot fail later on VPS. */
    static boolean isValidPath(String value) {
        if (value == null || value.isEmpty() || value.length() > 256
                || value.charAt(0) != '/' || containsHttpUnsafe(value)
                || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            return false;
        }
        if ("/healthz".equals(value) || "/version".equals(value)
                || "/test-routes".equals(value)) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (isAsciiAlphaNumeric(ch) || "/._~-".indexOf(ch) >= 0) continue;
            if (ch == '%' && index + 2 < value.length()
                    && isHex(value.charAt(index + 1)) && isHex(value.charAt(index + 2))) {
                index += 2;
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean isAsciiAlphaNumeric(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9');
    }

    private static boolean isHex(char value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private static boolean containsHeaderUnsafe(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e) return true;
        }
        return false;
    }
}
