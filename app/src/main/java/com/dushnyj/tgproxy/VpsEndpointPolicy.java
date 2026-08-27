package com.dushnyj.tgproxy;

import java.net.InetAddress;
import java.net.IDN;
import java.util.Locale;

final class VpsEndpointPolicy {
    enum Mode { IP, DUCKDNS, OWN_DOMAIN }

    private VpsEndpointPolicy() {}

    static String normalizeHost(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (value.startsWith("https://")) value = value.substring(8);
        else if (value.startsWith("http://")) value = value.substring(7);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        if (value.startsWith("[") && value.contains("]")) {
            value = value.substring(1, value.indexOf(']'));
        } else {
            int first = value.indexOf(':');
            int last = value.lastIndexOf(':');
            if (first > 0 && first == last && value.substring(first + 1).matches("\\d{1,5}")) {
                value = value.substring(0, first);
            }
        }
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (!value.contains(":")) {
            try {
                value = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.US);
            } catch (Exception ignored) {
                // Validation below will reject malformed input without changing what the user typed.
            }
        }
        return value;
    }

    static boolean isIpLiteral(String raw) {
        String value = normalizeHost(raw);
        if (value.isEmpty()) return false;
        if (value.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            String[] parts = value.split("\\.");
            for (String part : parts) {
                try {
                    if (Integer.parseInt(part) > 255) return false;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            return true;
        }
        if (!value.contains(":") || !value.matches("[0-9a-fA-F:.%]+")) return false;
        try {
            return InetAddress.getByName(value).getHostAddress().contains(":");
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isDomain(String raw) {
        String value = normalizeHost(raw);
        if (value.length() < 4 || value.length() > 253 || isIpLiteral(value)) return false;
        if (!value.contains(".") || value.contains("..")) return false;
        String[] labels = value.split("\\.");
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return false;
        }
        String suffix = labels[labels.length - 1];
        return suffix.matches("[a-z]{2,63}")
                || suffix.matches("xn--[a-z0-9-]{2,59}");
    }

    static boolean isDuckDnsDomain(String raw) {
        String value = normalizeHost(raw);
        return isDomain(value) && value.endsWith(".duckdns.org")
                && value.length() > ".duckdns.org".length();
    }

    static String duckDnsSubdomain(String raw) {
        String value = normalizeHost(raw);
        if (!isDuckDnsDomain(value)) return "";
        return value.substring(0, value.length() - ".duckdns.org".length());
    }

    static Mode suggestedMode(VpsRelayConfig relay) {
        if (relay == null || !relay.isUsable()) return Mode.IP;
        if (isIpLiteral(relay.host())) return Mode.IP;
        if (isDuckDnsDomain(relay.host())) return Mode.DUCKDNS;
        return Mode.OWN_DOMAIN;
    }
}
