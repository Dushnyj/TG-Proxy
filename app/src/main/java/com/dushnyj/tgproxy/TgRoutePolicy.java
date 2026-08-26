package com.dushnyj.tgproxy;

import java.util.Map;

public final class TgRoutePolicy {

    public static final boolean DEFAULT_SMART_SLEEP = false;

    private static final String FLOWSEAL_REACHABLE_IP = "149.154.167.220";

    public static boolean canUseDirectWs(int dc) {
        return MtProtoConfig.isValidDc(dc);
    }

    public static String[] targetIpsForDirectWs(int dc) {
        if (!canUseDirectWs(dc)) return new String[0];
        return new String[]{FLOWSEAL_REACHABLE_IP};
    }

    public static boolean shouldUseDirectWs(int dc, boolean media, Map<Integer, String> dcRedirects) {
        // Main/media ordering is expressed by TgConstants.wsDomains(). Suppressing DC2 media
        // here left a Direct-only installation with no candidate at all, even though the same
        // mapping is supported by tg-ws-proxy.
        return canUseDirectWs(dc);
    }

    public static boolean allowDirectTelegramFallback(String dst, int port) {
        return port == 443 && FLOWSEAL_REACHABLE_IP.equals(dst);
    }

    private TgRoutePolicy() {}
}
