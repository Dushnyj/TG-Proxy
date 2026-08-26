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

import androidx.preference.PreferenceManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

final class NetworkProfileIdentifier {
    private static final String KEY_WIFI_ID_SALT = "network_profile_wifi_salt.v1";
    private static String volatileWifiSalt = "";

    private NetworkProfileIdentifier() {}

    static NetworkProfile current(Context context) {
        if (context == null) return NetworkProfile.defaultProfile();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return NetworkProfile.defaultProfile();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
                if (caps == null) return NetworkProfile.defaultProfile();
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return wifiProfile(context, caps, network);
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return mobileProfile(context);
                }
            } else {
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                if (info == null || !info.isConnected()) return NetworkProfile.defaultProfile();
                if (info.getType() == ConnectivityManager.TYPE_WIFI) {
                    return wifiProfile(context, null, null);
                }
                if (info.getType() == ConnectivityManager.TYPE_MOBILE) return mobileProfile(context);
            }
        } catch (Exception ignored) {
        }
        return NetworkProfile.defaultProfile();
    }

    static NetworkProfile wifiProfile(Context context) {
        NetworkCapabilities capabilities = null;
        Network activeNetwork = null;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activeNetwork = cm.getActiveNetwork();
                capabilities = activeNetwork == null
                        ? null : cm.getNetworkCapabilities(activeNetwork);
            }
        } catch (Exception ignored) {
        }
        return wifiProfile(context, capabilities, activeNetwork);
    }

    private static NetworkProfile wifiProfile(Context context, NetworkCapabilities capabilities,
                                              Network activeNetwork) {
        String ssid = "";
        String bssid = "";
        int networkId = -1;
        try {
            WifiInfo info = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && capabilities != null
                    && capabilities.getTransportInfo() instanceof WifiInfo) {
                info = (WifiInfo) capabilities.getTransportInfo();
            }
            WifiManager manager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (info == null && manager != null) info = manager.getConnectionInfo();
            if (info != null) {
                ssid = stripQuotes(info.getSSID());
                bssid = info.getBSSID();
                networkId = info.getNetworkId();
            }
        } catch (Exception ignored) {
        }
        if (isUsableSsid(ssid)) {
            String normalized = normalizeId(ssid);
            if (!normalized.isEmpty()) return NetworkProfile.wifi(normalized, ssid.trim());
        }
        String opaqueId = opaqueWifiId(wifiSalt(context), bssid, networkId, "");
        return opaqueId.isEmpty() ? NetworkProfile.hiddenWifi() : NetworkProfile.opaqueWifi(opaqueId);
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

    static String opaqueWifiId(String salt, String bssid, int networkId, int gateway) {
        return opaqueWifiId(salt, bssid, networkId, "");
    }

    static String opaqueWifiId(String salt, String bssid, int networkId,
                               String attachmentToken) {
        String signal = "";
        // Never key a hidden network by its gateway: unrelated Wi-Fi networks commonly
        // share 192.168.0.1/192.168.1.1 and would poison each other's route history.
        if (isUsableBssid(bssid)) {
            signal = "bssid:" + bssid.trim().toLowerCase(Locale.US);
        } else if (networkId >= 0) {
            signal = "network-id:" + networkId;
        } else {
            // When Android redacts every Wi-Fi identifier, a Network handle changes on every
            // reconnect and must not become a profile key. Use one installation-scoped hidden
            // Wi-Fi alias instead. Route health is still revalidated on every network handover.
            signal = "redacted-wifi";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(((salt == null ? "" : salt) + "\n" + signal)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("opaque_");
            for (int i = 0; i < 10; i++) {
                out.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
            }
            return out.toString();
        } catch (Exception ignored) {
            return "opaque_" + Integer.toHexString(signal.hashCode());
        }
    }

    private static synchronized String wifiSalt(Context context) {
        if (context == null) return processWifiSalt();
        try {
            android.content.SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
            String stored = prefs.getString(KEY_WIFI_ID_SALT, "");
            if (stored != null && !stored.trim().isEmpty()) return stored.trim();
            String generated = processWifiSalt();
            if (prefs.edit().putString(KEY_WIFI_ID_SALT, generated).commit()) return generated;
        } catch (Exception ignored) {
        }
        return processWifiSalt();
    }

    private static String processWifiSalt() {
        if (!volatileWifiSalt.isEmpty()) return volatileWifiSalt;
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(Locale.US, "%02x", value & 0xff));
        volatileWifiSalt = out.toString();
        return volatileWifiSalt;
    }

    private static boolean isUsableBssid(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        return value.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
                && !"00:00:00:00:00:00".equals(value)
                && !"02:00:00:00:00:00".equals(value)
                && !"ff:ff:ff:ff:ff:ff".equals(value);
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
