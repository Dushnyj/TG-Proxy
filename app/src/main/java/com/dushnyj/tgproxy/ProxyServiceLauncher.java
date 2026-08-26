package com.dushnyj.tgproxy;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;

final class ProxyServiceLauncher {
    private static final int RECOVERY_REQUEST_CODE = 7401;
    private static final long RECOVERY_INTERVAL_MS = 15 * 60_000L;

    private ProxyServiceLauncher() {
    }

    static boolean startByUser(Context context) {
        Context app = context.getApplicationContext();
        if (!ProcessExitTracker.persistUserStartIntent(app)) {
            DiagnosticsLog.record("failed to persist desired proxy state");
            return false;
        }
        return startExistingDesired(app, ProxyService.ACTION_START);
    }

    static boolean restoreIfDesired(Context context, String reason) {
        Context app = context.getApplicationContext();
        if (ProcessExitTracker.reconcileSystemUserStop(app)) {
            cancelRecovery(app);
            return false;
        }
        if (!state(app).desiredRunning()) return false;
        return startExistingDesired(app, ProxyService.ACTION_RESTORE_PREFIX + reason);
    }

    static boolean stopByUser(Context context) {
        Context app = context.getApplicationContext();
        if (!state(app).setDesiredRunning(false)) {
            DiagnosticsLog.record("failed to persist stopped proxy state");
            return false;
        }
        cancelRecovery(app);
        Intent intent = new Intent(app, ProxyService.class);
        intent.setAction(ProxyService.ACTION_STOP);
        if (ProxyService.getInstance() != null) {
            app.startService(intent);
        } else {
            app.stopService(new Intent(app, ProxyService.class));
        }
        return true;
    }

    static void scheduleRecovery(Context context, long delayMs) {
        Context app = context.getApplicationContext();
        if (!state(app).desiredRunning()) {
            cancelRecovery(app);
            return;
        }
        if (!canUseRecoveryAlarm(app)) {
            cancelRecovery(app);
            return;
        }
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        long triggerAt = SystemClock.elapsedRealtime() + Math.max(5_000L, delayMs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt, recoveryIntent(app));
        } else {
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, recoveryIntent(app));
        }
    }

    static void scheduleRegularRecovery(Context context) {
        scheduleRecovery(context, RECOVERY_INTERVAL_MS);
    }

    static void cancelRecovery(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(recoveryIntent(context));
    }

    private static boolean startExistingDesired(Context context, String action) {
        if (ProxyService.getInstance() != null) {
            scheduleRegularRecovery(context);
            return true;
        }
        Intent service = new Intent(context, ProxyService.class);
        service.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
            DiagnosticsLog.record("proxy service launch requested action=" + action);
            scheduleRegularRecovery(context);
            return true;
        } catch (RuntimeException error) {
            DiagnosticsLog.record("proxy service launch failed " + error.getClass().getSimpleName());
            scheduleRecovery(context, 60_000L);
            return false;
        }
    }

    private static PendingIntent recoveryIntent(Context context) {
        Intent intent = new Intent(context, ProxyRestartReceiver.class);
        intent.setAction(ProxyRestartReceiver.ACTION_RECOVER);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, RECOVERY_REQUEST_CODE, intent, flags);
    }

    private static boolean canUseRecoveryAlarm(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static ProxyRunStateStore state(Context context) {
        return ProxyRunStateStore.fromPreferences(
                PreferenceManager.getDefaultSharedPreferences(context));
    }
}
