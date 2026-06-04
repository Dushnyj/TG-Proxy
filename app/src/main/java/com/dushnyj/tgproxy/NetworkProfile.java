package com.dushnyj.tgproxy;

import java.util.Locale;

final class NetworkProfile {
    enum Kind {
        WIFI,
        MOBILE,
        MANUAL,
        DEFAULT
    }

    private final Kind kind;
    private final String id;
    private final String displayLabel;

    private NetworkProfile(Kind kind, String id) {
        this(kind, id, "");
    }

    private NetworkProfile(Kind kind, String id, String displayLabel) {
        this.kind = kind == null ? Kind.DEFAULT : kind;
        this.id = normalize(id);
        this.displayLabel = displayLabel == null ? "" : displayLabel.trim();
    }

    static NetworkProfile wifi(String id) {
        return new NetworkProfile(Kind.WIFI, id);
    }

    static NetworkProfile wifi(String id, String displayLabel) {
        return new NetworkProfile(Kind.WIFI, id, displayLabel);
    }

    static NetworkProfile hiddenWifi() {
        return new NetworkProfile(Kind.WIFI, "hidden");
    }

    static NetworkProfile mobile(String id) {
        return new NetworkProfile(Kind.MOBILE, id);
    }

    static NetworkProfile mobile(String id, String displayLabel) {
        return new NetworkProfile(Kind.MOBILE, id, displayLabel);
    }

    static NetworkProfile manual(String id, String displayLabel) {
        return new NetworkProfile(Kind.MANUAL, id, displayLabel);
    }

    static NetworkProfile defaultProfile() {
        return new NetworkProfile(Kind.DEFAULT, "default");
    }

    Kind kind() {
        return kind;
    }

    String id() {
        return id;
    }

    String key() {
        switch (kind) {
            case WIFI:
                return isHiddenWifiId(id) ? "wifi:hidden" : "wifi:ssid:" + id;
            case MOBILE:
                return isMccMnc(id) ? "mobile:mccmnc:" + id : "mobile:name:" + id;
            case MANUAL:
                return "manual:" + id;
            case DEFAULT:
            default:
                return "default:default";
        }
    }

    String defaultDisplayName() {
        if (!displayLabel.isEmpty()) return displayLabel;
        switch (kind) {
            case WIFI:
                return wifiDisplayName(id);
            case MOBILE:
                return mobileDisplayName(id);
            case MANUAL:
                return "Manual profile";
            case DEFAULT:
            default:
                return "Default network";
        }
    }

    boolean isMobile() {
        return kind == Kind.MOBILE;
    }

    boolean isWifi() {
        return kind == Kind.WIFI;
    }

    boolean isManual() {
        return kind == Kind.MANUAL;
    }

    boolean isHiddenWifi() {
        return kind == Kind.WIFI && isHiddenWifiId(id);
    }

    String legacyKey() {
        return kind.name().toLowerCase(Locale.US) + ":" + id;
    }

    String cfProfileId() {
        return key();
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return normalized.isEmpty() ? "default" : normalized;
    }

    private static boolean isMccMnc(String value) {
        return value != null && value.matches("\\d{5,6}");
    }

    private static boolean isHiddenWifiId(String value) {
        return value == null
                || value.isEmpty()
                || "default".equals(value)
                || "default_wifi".equals(value)
                || "hidden".equals(value)
                || "unknown_ssid".equals(value);
    }

    private static String wifiDisplayName(String id) {
        if (isHiddenWifiId(id)) {
            return "Wi-Fi (имя скрыто)";
        }
        return titleCase(id.replace('_', ' ')).replace("Wifi", "WiFi");
    }

    private static String mobileDisplayName(String id) {
        if (id == null || id.isEmpty() || "default".equals(id) || "default_mobile".equals(id)) {
            return "Mobile network";
        }
        return titleCase(id.replace('_', ' '));
    }

    private static String titleCase(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String part : text.trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(part.substring(0, 1).toUpperCase(Locale.US));
            if (part.length() > 1) out.append(part.substring(1));
        }
        return out.toString();
    }
}
