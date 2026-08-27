package com.dushnyj.tgproxy;

import java.util.Locale;

final class NetworkProfileRecord {
    private final NetworkProfile profile;
    private final String displayName;
    private final RoutePreference routePreference;
    private final long createdMs;
    private final long lastSeenMs;
    private final int seenCount;

    NetworkProfileRecord(NetworkProfile profile, String displayName,
                         RoutePreference routePreference, long createdMs,
                         long lastSeenMs, int seenCount) {
        this.profile = profile == null ? NetworkProfile.defaultProfile() : profile;
        String name = displayName == null ? "" : displayName.trim();
        this.displayName = name.isEmpty() || isLegacyGeneratedName(name, this.profile)
                ? this.profile.defaultDisplayName()
                : name;
        this.routePreference = routePreference == null ? RoutePreference.AUTO : routePreference;
        this.createdMs = createdMs <= 0L ? lastSeenMs : createdMs;
        this.lastSeenMs = Math.max(0L, lastSeenMs);
        this.seenCount = Math.max(0, seenCount);
    }

    static NetworkProfileRecord create(NetworkProfile profile, long nowMs) {
        return new NetworkProfileRecord(profile, "", RoutePreference.AUTO, nowMs, nowMs, 1);
    }

    NetworkProfileRecord seen(long nowMs) {
        return new NetworkProfileRecord(profile, displayName, routePreference,
                createdMs, nowMs, seenCount + 1);
    }

    NetworkProfileRecord renamed(String name) {
        return new NetworkProfileRecord(profile, name, routePreference,
                createdMs, lastSeenMs, seenCount);
    }

    NetworkProfileRecord withRoutePreference(RoutePreference preference) {
        return new NetworkProfileRecord(profile, displayName, preference,
                createdMs, lastSeenMs, seenCount);
    }

    String key() {
        return profile.key();
    }

    NetworkProfile profile() {
        return profile;
    }

    String displayName() {
        return displayName;
    }

    RoutePreference routePreference() {
        return routePreference;
    }

    long createdMs() {
        return createdMs;
    }

    long lastSeenMs() {
        return lastSeenMs;
    }

    int seenCount() {
        return seenCount;
    }

    private static boolean isLegacyGeneratedName(String name, NetworkProfile profile) {
        return name.equals("Mobile " + profile.id())
                || name.equals("Wi-Fi " + profile.id())
                || name.equals("Wi-Fi")
                || name.equals("Tele2")
                || name.equals("T2 Black")
                || name.equals("Mobile tele2")
                || name.equals("Mobile t2_black")
                || name.equals("Mobile tele2_russia")
                || name.equals("Wi-Fi 192.168.1.1")
                || (profile.isWifi() && isLegacyGeneratedWifiLabel(name));
    }

    /**
     * v1.0.8 displayed a four-character hash when Android redacted the SSID. It looked like a
     * real network name (for example, "Wi-Fi • 174D"), but was only an installation-local
     * fingerprint. Never preserve that generated label as a user-visible profile name.
     */
    static boolean isLegacyGeneratedWifiLabel(String name) {
        String value = name == null ? "" : name.trim().toLowerCase(Locale.US);
        value = value.replace('\u2010', '-')
                .replace('\u2011', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-');
        return value.matches("wi[- ]?fi\\s*[\\u2022\\u00b7*]\\s*[0-9a-f]{4}"
                + "(?:\\s*[\\u2022\\u00b7*]\\s*(?:active|активен))?");
    }
}
