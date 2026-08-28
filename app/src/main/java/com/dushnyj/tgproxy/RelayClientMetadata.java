package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

final class RelayClientMetadata {
    private static volatile Headers current = Headers.empty();

    private RelayClientMetadata() {}

    static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        AndroidDeviceIdentity.Value identity = AndroidDeviceIdentity.load(app);
        String versionName = BuildConfig.VERSION_NAME;
        long versionCode = BuildConfig.VERSION_CODE;
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            if (info.versionName != null) versionName = info.versionName;
            versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
        }
        current = new Headers(
                ascii(identity.deviceId), ascii(identity.previousDeviceId),
                ascii(identity.manufacturer), ascii(identity.brand), ascii(identity.model),
                ascii(identity.device), ascii(identity.product),
                ascii(identity.canonicalBrand), ascii(identity.marketingName),
                ascii(versionName), String.valueOf(versionCode), ascii(Build.VERSION.RELEASE));
    }

    static void appendHttpHeaders(StringBuilder request) {
        if (request == null) return;
        Headers value = current;
        append(request, "X-TGProxy-Device-ID", value.deviceId);
        append(request, "X-TGProxy-Previous-Device-ID", value.previousDeviceId);
        append(request, "X-TGProxy-Identity-Version", "2");
        append(request, "X-TGProxy-Manufacturer", value.manufacturer);
        append(request, "X-TGProxy-Brand", value.brand);
        append(request, "X-TGProxy-Model", value.model);
        append(request, "X-TGProxy-Device-Code", value.device);
        append(request, "X-TGProxy-Product", value.product);
        append(request, "X-TGProxy-Canonical-Brand", value.canonicalBrand);
        append(request, "X-TGProxy-Device-Name", value.marketingName);
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

    private static final class Headers {
        final String deviceId, previousDeviceId, manufacturer, brand, model, device, product;
        final String canonicalBrand, marketingName, appVersion, appCode, android;

        Headers(String deviceId, String previousDeviceId, String manufacturer, String brand,
                String model, String device, String product, String canonicalBrand,
                String marketingName, String appVersion, String appCode, String android) {
            this.deviceId = deviceId;
            this.previousDeviceId = previousDeviceId;
            this.manufacturer = manufacturer;
            this.brand = brand;
            this.model = model;
            this.device = device;
            this.product = product;
            this.canonicalBrand = canonicalBrand;
            this.marketingName = marketingName;
            this.appVersion = appVersion;
            this.appCode = appCode;
            this.android = android;
        }

        static Headers empty() {
            return new Headers("", "", "", "", "", "", "", "", "", "", "", "");
        }
    }
}
