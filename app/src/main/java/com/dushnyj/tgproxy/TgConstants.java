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
        int webDc = dc == 203 ? 2 : dc;
        if (isMedia) {
            return new String[]{
                    "kws" + webDc + "-1.web.telegram.org",
                    "kws" + webDc + ".web.telegram.org"
            };
        }
        return new String[]{
                "kws" + webDc + ".web.telegram.org",
                "kws" + webDc + "-1.web.telegram.org"
        };
    }

    private TgConstants() {
    }
}
