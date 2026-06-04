package com.dushnyj.tgproxy;

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
                || name.equals("Wi-Fi 192.168.1.1");
    }
}
