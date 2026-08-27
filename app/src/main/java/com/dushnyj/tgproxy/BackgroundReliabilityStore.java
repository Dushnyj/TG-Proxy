package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

final class BackgroundReliabilityStore {
    private static final String KEY_PROMPTED_VERSION = "background_prompted_version.v3";
    private static final String KEY_AUTOSTART_CONFIRMED = "background_autostart_confirmed.v1";
    private static final String KEY_BOOT_RECEIVER_AT = "background_boot_receiver_at.v1";
    private static final String KEY_NETWORK_IDENTITY_PERMISSION_VERSION =
            "network_identity_permission_requested_version.v1";

    private final Context context;
    private final SharedPreferences prefs;

    BackgroundReliabilityStore(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
        this.prefs = this.context == null ? null
                : PreferenceManager.getDefaultSharedPreferences(this.context);
    }

    Status status() {
        boolean battery = BackgroundExecutionAssistant.isBatteryOptimizationDisabled(context);
        boolean notifications = BackgroundExecutionAssistant.areNotificationsAllowed(context);
        boolean bootEnabled = prefs != null && prefs.getBoolean("autostart_boot", true);
        boolean manufacturerConfirmed = !BackgroundExecutionAssistant
                .requiresManualAutostartConfirmation()
                || (prefs != null && (prefs.getBoolean(KEY_AUTOSTART_CONFIRMED, false)
                || prefs.getLong(KEY_BOOT_RECEIVER_AT, 0L) > 0L));
        return new Status(battery, notifications, bootEnabled, manufacturerConfirmed);
    }

    boolean shouldPrompt(int versionCode) {
        Status status = status();
        int prompted = prefs == null ? 0 : prefs.getInt(KEY_PROMPTED_VERSION, 0);
        return !status.ready() && prompted != versionCode;
    }

    void markPrompted(int versionCode) {
        if (prefs != null) prefs.edit().putInt(KEY_PROMPTED_VERSION, versionCode).commit();
    }

    void setAutostartConfirmed(boolean confirmed) {
        if (prefs != null) prefs.edit().putBoolean(KEY_AUTOSTART_CONFIRMED, confirmed).commit();
    }

    boolean autostartConfirmed() {
        return prefs != null && prefs.getBoolean(KEY_AUTOSTART_CONFIRMED, false);
    }

    boolean shouldRequestNetworkIdentityPermissions(int versionCode) {
        return prefs != null
                && prefs.getInt(KEY_NETWORK_IDENTITY_PERMISSION_VERSION, 0) != versionCode;
    }

    boolean hasRequestedNetworkIdentityPermissions() {
        return prefs != null && prefs.getInt(KEY_NETWORK_IDENTITY_PERMISSION_VERSION, 0) > 0;
    }

    void markNetworkIdentityPermissionsRequested(int versionCode) {
        if (prefs != null) {
            prefs.edit().putInt(KEY_NETWORK_IDENTITY_PERMISSION_VERSION, versionCode).commit();
        }
    }

    static void markBootReceiverObserved(Context context) {
        if (context == null) return;
        PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext()).edit()
                .putLong(KEY_BOOT_RECEIVER_AT, System.currentTimeMillis())
                .putBoolean(KEY_AUTOSTART_CONFIRMED, true)
                .commit();
    }

    static final class Status {
        final boolean batteryUnrestricted;
        final boolean notificationsAllowed;
        final boolean bootEnabled;
        final boolean autostartConfirmed;

        Status(boolean batteryUnrestricted, boolean notificationsAllowed,
               boolean bootEnabled, boolean autostartConfirmed) {
            this.batteryUnrestricted = batteryUnrestricted;
            this.notificationsAllowed = notificationsAllowed;
            this.bootEnabled = bootEnabled;
            this.autostartConfirmed = autostartConfirmed;
        }

        boolean ready() {
            return batteryUnrestricted && notificationsAllowed
                    && bootEnabled && autostartConfirmed;
        }
    }
}
