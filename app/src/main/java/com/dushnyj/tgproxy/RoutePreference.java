package com.dushnyj.tgproxy;

enum RoutePreference {
    AUTO,
    DIRECT_FIRST,
    CLOUDFLARE_FIRST,
    RELAY_FIRST;

    static RoutePreference fromStored(String value) {
        if (value == null || value.trim().isEmpty()) return AUTO;
        try {
            return RoutePreference.valueOf(value.trim().toUpperCase(java.util.Locale.US));
        } catch (Exception ignored) {
            return AUTO;
        }
    }
}
