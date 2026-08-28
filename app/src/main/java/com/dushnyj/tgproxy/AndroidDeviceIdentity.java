package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Produces a stable, pseudonymous installation/device identity without sending ANDROID_ID. */
final class AndroidDeviceIdentity {
    private static final String KEY_DEVICE_ID_V1 = "relay_device_id.v1";
    private static final String KEY_DEVICE_ID_V2 = "relay_device_id.v2";
    private static final String DEVICE_ID_NAMESPACE = "com.dushnyj.tgproxy";

    private AndroidDeviceIdentity() {}

    static Value load(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        String previous = safeIdentifier(prefs.getString(KEY_DEVICE_ID_V1, ""));
        String stable = safeIdentifier(prefs.getString(KEY_DEVICE_ID_V2, ""));

        if (stable.isEmpty() && Build.VERSION.SDK_INT >= 26) {
            String androidId = Settings.Secure.getString(
                    app.getContentResolver(), Settings.Secure.ANDROID_ID);
            stable = stableId(androidId, DEVICE_ID_NAMESPACE);
            if (!stable.isEmpty() && !prefs.edit().putString(KEY_DEVICE_ID_V2, stable).commit()) {
                DiagnosticsLog.record("relay stable device id persist failed");
            }
        }
        if (stable.isEmpty()) {
            if (previous.isEmpty()) {
                previous = "dev_" + UUID.randomUUID().toString().replace("-", "");
                if (!prefs.edit().putString(KEY_DEVICE_ID_V1, previous).commit()) {
                    DiagnosticsLog.record("relay device id persist failed");
                }
            }
            stable = previous;
            previous = "";
        } else if (stable.equals(previous)) {
            previous = "";
        }

        String deviceName = "";
        try {
            deviceName = Settings.Global.getString(app.getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        DeviceDisplayName.Identity display = DeviceDisplayName.resolve(
                Build.MANUFACTURER, Build.BRAND, Build.MODEL, deviceName);
        return new Value(stable, previous, Build.MANUFACTURER, Build.BRAND, Build.MODEL,
                Build.DEVICE, Build.PRODUCT, display.brand, display.marketingName);
    }

    static String stableId(String androidId, String packageName) {
        String id = androidId == null ? "" : androidId.trim().toLowerCase(Locale.US);
        if (id.isEmpty() || id.equals("9774d56d682e549c") || id.equals("unknown") ||
                id.matches("0+")) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("tgproxy-device-v2\n" + clean(packageName) + "\n" + id)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("dev2_");
            for (int i = 0; i < 16; i++) out.append(String.format(Locale.US, "%02x", hash[i]));
            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String safeIdentifier(String value) {
        String clean = clean(value);
        return clean.matches("[A-Za-z0-9._-]{8,96}") ? clean : "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static final class Value {
        final String deviceId, previousDeviceId, manufacturer, brand, model, device, product;
        final String canonicalBrand, marketingName;

        Value(String deviceId, String previousDeviceId, String manufacturer, String brand,
              String model, String device, String product, String canonicalBrand,
              String marketingName) {
            this.deviceId = clean(deviceId);
            this.previousDeviceId = clean(previousDeviceId);
            this.manufacturer = clean(manufacturer);
            this.brand = clean(brand);
            this.model = clean(model);
            this.device = clean(device);
            this.product = clean(product);
            this.canonicalBrand = clean(canonicalBrand);
            this.marketingName = clean(marketingName);
        }
    }
}
