package com.dushnyj.tgproxy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private final VpsRelayCapabilities capabilities;
    private final String instanceId;

    private VpsRelayConfig(boolean enabled, String name, String host, int port,
                           boolean tls, String path, String token, String profileKey) {
        this(enabled, name, host, port, tls, path, token, profileKey,
                VpsRelayCapabilities.unknown(), "");
    }

    private VpsRelayConfig(boolean enabled, String name, String host, int port,
                           boolean tls, String path, String token, String profileKey,
                           VpsRelayCapabilities capabilities) {
        this(enabled, name, host, port, tls, path, token, profileKey, capabilities, "");
    }

    private VpsRelayConfig(boolean enabled, String name, String host, int port,
                           boolean tls, String path, String token, String profileKey,
                           VpsRelayCapabilities capabilities, String instanceId) {
        this.enabled = enabled;
        this.name = valueOr(name, "VPS Relay");
        this.host = normalizeHost(host);
        this.port = port;
        this.tls = tls;
        this.path = normalizePath(path);
        this.token = valueOr(token, "");
        this.profileKey = valueOr(profileKey, "");
        this.capabilities = capabilities == null
                ? VpsRelayCapabilities.unknown() : capabilities;
        this.instanceId = validInstanceId(instanceId) ? instanceId.trim() : "";
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
        return enabled && hasValidConnection();
    }

    boolean hasValidEndpoint() {
        return !host.isEmpty()
                && isValidHost(host)
                && port > 0
                && port <= 65535
                && isValidPath(path);
    }

    boolean hasValidConnection() {
        return hasValidEndpoint()
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

    VpsRelayCapabilities capabilities() { return capabilities; }

    String instanceId() { return instanceId; }

    boolean supportsRoute(int dc, boolean test) { return capabilities.supports(dc, test); }

    VpsRelayConfig withProfileKey(String profileKey) {
        return new VpsRelayConfig(enabled, name, host, port, tls, path, token, profileKey,
                capabilities, instanceId);
    }

    VpsRelayConfig withEnabled(boolean enabled) {
        return new VpsRelayConfig(enabled, name, host, port, tls, path, token, profileKey,
                capabilities, instanceId);
    }

    VpsRelayConfig withTokenAndName(String token, String name) {
        return new VpsRelayConfig(true, name, host, port, tls, path, token, profileKey,
                capabilities, instanceId);
    }

    VpsRelayConfig withName(String name) {
        return new VpsRelayConfig(enabled, name, host, port, tls, path, token, profileKey,
                capabilities, instanceId);
    }

    VpsRelayConfig withCapabilities(VpsRelayCapabilities value) {
        return new VpsRelayConfig(enabled, name, host, port, tls, path, token, profileKey,
                value, instanceId);
    }

    VpsRelayConfig withInstanceId(String value) {
        return new VpsRelayConfig(enabled, name, host, port, tls, path, token, profileKey,
                capabilities, value);
    }

    boolean sameRoutingIdentity(VpsRelayConfig other) {
        if (other == null) return false;
        return enabled == other.enabled
                && port == other.port
                && tls == other.tls
                && host.equals(other.host)
                && path.equals(other.path)
                && token.equals(other.token)
                && profileKey.equals(other.profileKey)
                && capabilities.equals(other.capabilities)
                && instanceId.equals(other.instanceId);
    }

    /**
     * Compares only the server connection entered by the user. Negotiated capabilities,
     * display name and profile binding are metadata and must not make an unchanged form look
     * unsaved. In particular, a successful Relay test enriches the stored record with
     * capabilities that are not represented by editable fields.
     */
    boolean sameRelayConnection(VpsRelayConfig other) {
        if (other == null) return false;
        return enabled == other.enabled
                && port == other.port
                && tls == other.tls
                && host.equals(other.host)
                && path.equals(other.path)
                && token.equals(other.token);
    }

    boolean sameEndpoint(VpsRelayConfig other) {
        if (other == null) return false;
        return port == other.port
                && tls == other.tls
                && host.equals(other.host)
                && path.equals(other.path);
    }

    boolean sameServer(VpsRelayConfig other) {
        if (other == null) return false;
        if (!instanceId.isEmpty() && !other.instanceId.isEmpty()) {
            return instanceId.equals(other.instanceId);
        }
        return sameEndpoint(other);
    }

    String serverIdentityKey() {
        return instanceId.isEmpty()
                ? (tls ? "tls" : "plain") + "|" + host + "|" + port + "|" + path
                : instanceId;
    }

    /** Stable non-secret identity used to keep failover statistics separate per Relay token. */
    String routingId() {
        String value = (tls ? "tls" : "plain") + "\n" + host + "\n" + port + "\n"
                + path + "\n" + token;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("r");
            for (int i = 0; i < 8; i++) out.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
            return out.toString();
        } catch (Exception ignored) {
            return "r" + Integer.toHexString(value.hashCode());
        }
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
        while (value.endsWith(".") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** Pure validation for DNS names/IPv4 plus literal-only parsing for IPv6. */
    static boolean isValidHost(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (value.isEmpty() || value.length() > 253 || value.indexOf('%') >= 0) return false;
        if (value.indexOf(':') >= 0) {
            try {
                InetAddress parsed = InetAddress.getByName(value);
                return parsed instanceof Inet6Address;
            } catch (Exception ignored) {
                return false;
            }
        }
        if (value.matches("[0-9.]+")) {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) return false;
            for (String octet : octets) {
                if (octet.isEmpty() || octet.length() > 3) return false;
                int number;
                try { number = Integer.parseInt(octet); }
                catch (NumberFormatException ignored) { return false; }
                if (number < 0 || number > 255) return false;
            }
            return true;
        }
        for (String label : value.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                return false;
            }
            for (int index = 0; index < label.length(); index++) {
                char ch = label.charAt(index);
                if (!isAsciiAlphaNumeric(ch) && ch != '-') return false;
            }
        }
        return true;
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

    /** Mirrors the Relay canonical path contract so reverse proxies and Go ServeMux agree. */
    static boolean isValidPath(String value) {
        if (value == null || value.isEmpty() || value.length() > 256
                || value.charAt(0) != '/' || containsHttpUnsafe(value)
                || value.length() == 1 || value.endsWith("/") || value.contains("//")
                || value.indexOf('?') >= 0 || value.indexOf('#') >= 0
                || value.indexOf('%') >= 0) {
            return false;
        }
        if ("/healthz".equals(value) || "/version".equals(value)
                || "/identity".equals(value)
                || "/capabilities".equals(value)
                || "/test-routes".equals(value) || "/connect".equals(value)
                || "/admin".equals(value) || value.startsWith("/admin/")
                || "/apiws/connect".equals(value)
                || "/apiws/identity".equals(value)
                || "/apiws/admin".equals(value)
                || value.startsWith("/apiws/admin/")) {
            return false;
        }
        String[] segments = value.substring(1).split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
            for (int index = 0; index < segment.length(); index++) {
                char ch = segment.charAt(index);
                if (!isAsciiAlphaNumeric(ch) && "._~-".indexOf(ch) < 0) return false;
            }
        }
        return true;
    }

    private static boolean isAsciiAlphaNumeric(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9');
    }

    private static boolean containsHeaderUnsafe(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e) return true;
        }
        return false;
    }

    static boolean validInstanceId(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("ri_[0-9a-f]{32,64}");
    }
}
