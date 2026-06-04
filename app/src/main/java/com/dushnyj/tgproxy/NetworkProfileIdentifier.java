package com.dushnyj.tgproxy;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.util.Locale;

final class NetworkProfileIdentifier {
    private NetworkProfileIdentifier() {}

    static NetworkProfile current(Context context) {
        if (context == null) return NetworkProfile.defaultProfile();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            if (caps == null) return NetworkProfile.defaultProfile();
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return wifiProfile(context);
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return mobileProfile(context);
            }
        } catch (Exception ignored) {
        }
        return NetworkProfile.defaultProfile();
    }

    static NetworkProfile wifiProfile(Context context) {
        String ssid = "";
        try {
            WifiManager manager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = manager == null ? null : manager.getConnectionInfo();
            ssid = info == null ? "" : stripQuotes(info.getSSID());
        } catch (Exception ignored) {
        }
        if (!isUsableSsid(ssid)) return NetworkProfile.hiddenWifi();
        String normalized = normalizeId(ssid);
        return normalized.isEmpty() ? NetworkProfile.hiddenWifi() : NetworkProfile.wifi(normalized, ssid.trim());
    }

    static NetworkProfile mobileProfile(Context context) {
        TelephonyManager defaultManager = null;
        try {
            defaultManager =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        } catch (Exception ignored) {
        }
        String[] defaultSignals = mobileSignals(defaultManager);
        String[] activeSignals = mobileSignals(activeDataTelephonyManager(defaultManager));
        return mobileProfileFromSignals(activeSignals[0], activeSignals[1],
                defaultSignals[0], defaultSignals[1]);
    }

    static NetworkProfile mobileProfileFromSignals(String activeCode, String activeName,
                                                   String defaultCode, String defaultName) {
        String code = firstMccMnc(activeCode);
        String name = firstNonEmpty(activeName);
        if (!code.isEmpty()) return NetworkProfile.mobile(code, name.isEmpty() ? code : name);

        String normalizedActiveName = normalizeMobileId(name);
        if (!normalizedActiveName.isEmpty()) {
            return NetworkProfile.mobile(normalizedActiveName, name);
        }

        code = firstMccMnc(defaultCode);
        name = firstNonEmpty(defaultName);
        if (!code.isEmpty()) return NetworkProfile.mobile(code, name.isEmpty() ? code : name);

        String normalizedDefaultName = normalizeMobileId(name);
        return normalizedDefaultName.isEmpty()
                ? NetworkProfile.mobile("default_mobile")
                : NetworkProfile.mobile(normalizedDefaultName, name);
    }

    static String wifiId(Context context) {
        return wifiProfile(context).id();
    }

    static String mobileId(Context context) {
        return mobileProfile(context).id();
    }

    static String normalizeMobileId(String raw) {
        return normalizeId(raw);
    }

    static String normalizeId(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        value = value.replace("-", "");
        value = value.replaceAll("[^a-z0-9._]+", "_");
        value = value.replaceAll("_+", "_");
        while (value.startsWith("_")) value = value.substring(1);
        while (value.endsWith("_")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static boolean isUsableSsid(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.US);
        return !"<unknown ssid>".equals(lower)
                && !"unknown_ssid".equals(lower)
                && !"0x".equals(lower);
    }

    private static String stripQuotes(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String firstMccMnc(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.matches("\\d{5,6}")) return normalized;
        }
        return "";
    }

    private static TelephonyManager activeDataTelephonyManager(TelephonyManager fallback) {
        if (fallback == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return fallback;
        try {
            int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                subId = SubscriptionManager.getActiveDataSubscriptionId();
            }
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subId = SubscriptionManager.getDefaultDataSubscriptionId();
            }
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return fallback.createForSubscriptionId(subId);
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String[] mobileSignals(TelephonyManager manager) {
        if (manager == null) return new String[]{"", ""};
        try {
            return new String[]{
                    firstMccMnc(manager.getNetworkOperator(), manager.getSimOperator()),
                    firstNonEmpty(manager.getNetworkOperatorName(), manager.getSimOperatorName())
            };
        } catch (Exception ignored) {
            return new String[]{"", ""};
        }
    }
}
