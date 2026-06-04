package com.dushnyj.tgproxy;

enum RouteType {
    DIRECT_WS("direct_ws", "Direct WS"),
    VPS_RELAY("vps_relay", "VPS Relay"),
    WORKER("worker", "Cloudflare Worker"),
    CUSTOM_CLOUDFLARE("custom_cf", "Custom Cloudflare"),
    PUBLIC_CLOUDFLARE("public_cf", "Cloudflare CDN"),
    TCP_FALLBACK("tcp_fallback", "TCP fallback");

    private final String id;
    private final String displayName;

    RouteType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    String id() {
        return id;
    }

    String displayName() {
        return displayName;
    }
}
