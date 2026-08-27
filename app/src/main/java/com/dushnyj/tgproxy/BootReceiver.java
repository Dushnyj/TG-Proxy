package com.dushnyj.tgproxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        ProxyRunStateStore runState = ProxyRunStateStore.fromPreferences(prefs);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            boolean legacyWasRunning = ProcessExitTracker
                    .likelyRunningBeforePackageReplacement(context);
            if (!runState.hasDesiredState()) {
                boolean legacyAutostart = prefs.getBoolean("autostart_boot", true);
                boolean migrated = legacyWasRunning
                        || (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
                        && legacyAutostart);
                if (!runState.setDesiredRunning(migrated)) {
                    DiagnosticsLog.record("package migration failed to persist desired state");
                    return;
                }
                DiagnosticsLog.record("package migration desiredRunning=" + migrated);
            }
            ProxyServiceLauncher.restoreIfDesired(context, "package-replaced");
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            BackgroundReliabilityStore.markBootReceiverObserved(context);
            boolean autostart = prefs.getBoolean("autostart_boot", true);
            if (autostart && runState.setDesiredRunning(true)) {
                ProxyServiceLauncher.restoreIfDesired(context, "boot");
            } else if (autostart) {
                DiagnosticsLog.record("boot start skipped: desired state was not persisted");
            }
        }
    }
}
