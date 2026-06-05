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
        if (!canUseDirectWs(dc)) return false;
        if (media && dc == 2 && dcRedirects != null) {
            String dc2 = dcRedirects.get(2);
            String dc4 = dcRedirects.get(4);
            if (FLOWSEAL_REACHABLE_IP.equals(dc2) && FLOWSEAL_REACHABLE_IP.equals(dc4)) {
                return false;
            }
        }
        return true;
    }

    public static boolean allowDirectTelegramFallback(String dst, int port) {
        return port == 443 && FLOWSEAL_REACHABLE_IP.equals(dst);
    }

    private TgRoutePolicy() {}
}
