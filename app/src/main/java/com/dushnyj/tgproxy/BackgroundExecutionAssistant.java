package com.dushnyj.tgproxy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.app.AppOpsManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.location.LocationManager;
import android.content.pm.PackageManager;

import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Method;

final class BackgroundExecutionAssistant {
    private static final int MIUI_OP_AUTO_START = 10008;

    enum AutostartState {
        NOT_REQUIRED,
        ALLOWED,
        DENIED,
        UNKNOWN
    }

    private BackgroundExecutionAssistant() {
    }

    static boolean isBatteryOptimizationDisabled(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static boolean requestBatteryOptimizationExemption(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        Intent direct = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.getPackageName()));
        if (open(context, direct)) return true;
        return open(context, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    static boolean openManufacturerAutostart(Context context) {
        if (context == null) return false;
        for (ComponentName component : manufacturerComponents()) {
            Intent intent = new Intent().setComponent(component);
            intent.putExtra("package_name", context.getPackageName());
            intent.putExtra("packageName", context.getPackageName());
            intent.putExtra("app_label", context.getApplicationInfo().loadLabel(
                    context.getPackageManager()).toString());
            if (open(context, intent)) return true;
        }
        return openAppSettings(context);
    }

    static boolean requiresManualAutostartConfirmation() {
        String manufacturer = manufacturer().toLowerCase(Locale.US);
        return manufacturer.contains("xiaomi") || manufacturer.contains("redmi")
                || manufacturer.contains("poco") || manufacturer.contains("huawei")
                || manufacturer.contains("honor") || manufacturer.contains("oppo")
                || manufacturer.contains("realme") || manufacturer.contains("oneplus")
                || manufacturer.contains("vivo") || manufacturer.contains("iqoo")
                || manufacturer.contains("samsung");
    }

    static AutostartState manufacturerAutostartState(Context context) {
        String manufacturer = manufacturer().toLowerCase(Locale.US);
        if (!requiresManualAutostartConfirmation()) return AutostartState.NOT_REQUIRED;
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")
                || manufacturer.contains("poco")) {
            return readMiuiAutostartState(context);
        }
        return AutostartState.UNKNOWN;
    }

    private static AutostartState readMiuiAutostartState(Context context) {
        if (context == null) return AutostartState.UNKNOWN;
        AppOpsManager manager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (manager == null) return AutostartState.UNKNOWN;
        String[] methodNames = {"checkOpNoThrow", "unsafeCheckOpNoThrow"};
        for (String methodName : methodNames) {
            try {
                Method method = AppOpsManager.class.getDeclaredMethod(methodName,
                        int.class, int.class, String.class);
                method.setAccessible(true);
                Object result = method.invoke(manager, MIUI_OP_AUTO_START,
                        context.getApplicationInfo().uid, context.getPackageName());
                if (!(result instanceof Integer)) continue;
                int mode = (Integer) result;
                if (mode == AppOpsManager.MODE_ALLOWED) return AutostartState.ALLOWED;
                if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) {
                    return AutostartState.DENIED;
                }
                return AutostartState.UNKNOWN;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // MIUI does not expose this AppOp consistently across releases.
            }
        }
        return AutostartState.UNKNOWN;
    }

    static boolean areNotificationsAllowed(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null
                    : manager.getNotificationChannel(ProxyService.CHANNEL_ID);
            return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    static boolean openNotificationSettings(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent channel = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, ProxyService.CHANNEL_ID);
            if (open(context, channel)) return true;
        }
        return openAppSettings(context);
    }

    static boolean isLocationEnabled(Context context) {
        if (context == null) return false;
        try {
            LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return manager.isLocationEnabled();
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean hasNetworkIdentityPermissions(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && (context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean openLocationSettings(Context context) {
        return context != null && open(context, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }

    static boolean openAppSettings(Context context) {
        if (context == null) return false;
        return open(context, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName())));
    }

    static String manufacturer() {
        String value = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER.trim();
        return value.isEmpty() ? "Android" : value;
    }

    private static List<ComponentName> manufacturerComponents() {
        String manufacturer = manufacturer().toLowerCase(Locale.US);
        ArrayList<ComponentName> result = new ArrayList<>();
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")
                || manufacturer.contains("poco")) {
            result.add(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            result.add(new ComponentName("com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"));
        } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            result.add(new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
        } else if (manufacturer.contains("oppo") || manufacturer.contains("realme")
                || manufacturer.contains("oneplus")) {
            result.add(new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            result.add(new ComponentName("com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"));
        } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            result.add(new ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
        } else if (manufacturer.contains("samsung")) {
            result.add(new ComponentName("com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"));
        }
        return result;
    }

    private static boolean open(Context context, Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
