package com.dushnyj.tgproxy;

import java.util.Locale;

public final class TgConstants {
    public static final int BUF = 131072;

    public static String humanBytes(long n) {
        double value = n;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        for (int i = 0; i < units.length - 1; i++) {
            if (Math.abs(value) < 1024) {
                return String.format(Locale.US, "%.1f%s", value, units[i]);
            }
            value /= 1024;
        }
        return String.format(Locale.US, "%.1f%s", value, units[units.length - 1]);
    }

    public static String[] wsDomains(int dc, boolean isMedia) {
        if (!supportsTelegramWebSocketDc(dc)) return new String[0];
        if (isMedia) {
            return new String[]{
                    "kws" + dc + "-1.web.telegram.org",
                    "kws" + dc + ".web.telegram.org"
            };
        }
        return new String[]{
                "kws" + dc + ".web.telegram.org",
                "kws" + dc + "-1.web.telegram.org"
        };
    }

    /** Official Telegram Web K currently publishes explicit WSS ingress only for DC 1..5. */
    static boolean supportsTelegramWebSocketDc(int dc) {
        return dc >= 1 && dc <= 5;
    }

    private TgConstants() {
    }
}
