package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import androidx.preference.PreferenceManager;

import java.util.UUID;

final class RelayClientMetadata {
    private static final String KEY_DEVICE_ID = "relay_device_id.v1";
    private static volatile Headers current = Headers.empty();

    private RelayClientMetadata() {}

    static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        String deviceId = safeIdentifier(prefs.getString(KEY_DEVICE_ID, ""));
        if (deviceId.isEmpty()) {
            deviceId = "dev_" + UUID.randomUUID().toString().replace("-", "");
            if (!prefs.edit().putString(KEY_DEVICE_ID, deviceId).commit()) {
                DiagnosticsLog.record("relay device id persist failed");
            }
        }
        String versionName = BuildConfig.VERSION_NAME;
        long versionCode = BuildConfig.VERSION_CODE;
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            if (info.versionName != null) versionName = info.versionName;
            versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
        }
        current = new Headers(deviceId, ascii(Build.MANUFACTURER), ascii(Build.MODEL),
                ascii(versionName), String.valueOf(versionCode), ascii(Build.VERSION.RELEASE));
    }

    static void appendHttpHeaders(StringBuilder request) {
        if (request == null) return;
        Headers value = current;
        append(request, "X-TGProxy-Device-ID", value.deviceId);
        append(request, "X-TGProxy-Manufacturer", value.manufacturer);
        append(request, "X-TGProxy-Model", value.model);
        append(request, "X-TGProxy-App-Version", value.appVersion);
        append(request, "X-TGProxy-App-Code", value.appCode);
        append(request, "X-TGProxy-Android", value.android);
    }

    private static void append(StringBuilder request, String name, String value) {
        if (!value.isEmpty()) request.append(name).append(": ").append(value).append("\r\n");
    }

    private static String ascii(String raw) {
        String value = raw == null ? "" : raw.trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < 128; i++) {
            char ch = value.charAt(i);
            if (ch >= 0x21 && ch <= 0x7e) out.append(ch);
            else if (Character.isWhitespace(ch) && out.length() > 0
                    && out.charAt(out.length() - 1) != ' ') out.append(' ');
        }
        return out.toString().trim();
    }

    private static String safeIdentifier(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("[A-Za-z0-9._-]{8,96}") ? clean : "";
    }

    private static final class Headers {
        final String deviceId, manufacturer, model, appVersion, appCode, android;

        Headers(String deviceId, String manufacturer, String model, String appVersion,
                String appCode, String android) {
            this.deviceId = deviceId; this.manufacturer = manufacturer; this.model = model;
            this.appVersion = appVersion; this.appCode = appCode; this.android = android;
        }

        static Headers empty() { return new Headers("", "", "", "", "", ""); }
    }
}
