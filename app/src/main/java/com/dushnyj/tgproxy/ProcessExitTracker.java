package com.dushnyj.tgproxy;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;

import java.util.List;

final class ProcessExitTracker {
    private static final String KEY_LAST_RECORDED_EXIT_MS = "last_process_exit_recorded_ms.v1";
    private static final String KEY_LAST_EXIT_SUMMARY = "last_process_exit_summary.v1";
    private static final String KEY_LAST_USER_START_MS = "last_proxy_user_start_ms.v1";
    private static final String KEY_USER_STOP_HANDLED_MS = "user_stop_handled_ms.v1";
    private static final String KEY_PACKAGE_EXIT_HANDLED_MS = "package_exit_handled_ms.v1";
    private static final long RECENT_PACKAGE_UPDATE_MS = 10 * 60_000L;

    private ProcessExitTracker() {
    }

    static boolean persistUserStartIntent(Context context) {
        return preferences(context).edit()
                .putBoolean(ProxyRunStateStore.KEY_DESIRED_RUNNING, true)
                .putLong(KEY_LAST_USER_START_MS, System.currentTimeMillis())
                .commit();
    }

    /** Consumes one Android Task Manager stop before any Activity/receiver restore path. */
    static boolean reconcileSystemUserStop(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        ExitRecord latest = latestExit(context);
        if (latest == null) return false;
        SharedPreferences prefs = preferences(context);
        long boundary = Math.max(prefs.getLong(KEY_LAST_USER_START_MS, 0L),
                Math.max(prefs.getLong(KEY_PACKAGE_EXIT_HANDLED_MS, 0L),
                        prefs.getLong(KEY_USER_STOP_HANDLED_MS, 0L)));
        if (!isUnhandledUserStop(latest, System.currentTimeMillis(), boundary)) return false;
        boolean saved = prefs.edit()
                .putBoolean(ProxyRunStateStore.KEY_DESIRED_RUNNING, false)
                .putLong(KEY_USER_STOP_HANDLED_MS, latest.timestampMs)
                .commit();
        if (!saved) {
            DiagnosticsLog.record("system user stop could not be persisted");
            return false;
        }
        DiagnosticsLog.record("system user stop consumed time=" + latest.timestampMs);
        return true;
    }

    static boolean likelyRunningBeforePackageReplacement(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        ExitRecord latest = latestExit(context);
        if (latest == null) return false;
        boolean result = isLikelyPackageUpdateOfRunningService(
                latest, Build.VERSION.SDK_INT, System.currentTimeMillis());
        if (result) {
            preferences(context).edit()
                    .putLong(KEY_PACKAGE_EXIT_HANDLED_MS, latest.timestampMs)
                    .commit();
        }
        return result;
    }

    static void collect(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ExitRecord latest = latestExit(context);
            if (latest == null) return;
            SharedPreferences prefs = preferences(context);
            long recorded = prefs.getLong(KEY_LAST_RECORDED_EXIT_MS, 0L);
            if (latest.timestampMs <= recorded) {
                String previous = prefs.getString(KEY_LAST_EXIT_SUMMARY, "");
                if (previous != null && !previous.isEmpty()) {
                    DiagnosticsLog.record("previous process exit " + previous);
                }
                return;
            }
            String summary = "reason=" + latest.reason
                    + " status=" + latest.status
                    + " importance=" + latest.importance
                    + " time=" + latest.timestampMs
                    + " description=" + safe(latest.description);
            prefs.edit()
                    .putLong(KEY_LAST_RECORDED_EXIT_MS, latest.timestampMs)
                    .putString(KEY_LAST_EXIT_SUMMARY, summary)
                    .commit();
            DiagnosticsLog.record("previous process exit " + summary);
        } catch (RuntimeException error) {
            DiagnosticsLog.record("process exit history unavailable "
                    + error.getClass().getSimpleName());
        }
    }

    static boolean isUnhandledUserStop(ExitRecord exit, long nowMs, long handledBoundaryMs) {
        if (exit == null || exit.reason != ApplicationExitInfo.REASON_USER_REQUESTED) return false;
        if (exit.timestampMs <= handledBoundaryMs) return false;
        return exit.timestampMs <= nowMs + 60_000L;
    }

    static boolean isLikelyPackageUpdateOfRunningService(ExitRecord exit, int sdkInt, long nowMs) {
        if (exit == null || !exit.wasForegroundService()) return false;
        long age = nowMs - exit.timestampMs;
        if (age < 0L || age > RECENT_PACKAGE_UPDATE_MS) return false;
        if (sdkInt >= 34) return exit.reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED;
        return exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED;
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static ExitRecord latestExit(Context context) {
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return null;
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 10);
            if (exits == null || exits.isEmpty()) return null;
            ApplicationExitInfo latest = null;
            for (ApplicationExitInfo item : exits) {
                if (item != null && (latest == null
                        || item.getTimestamp() > latest.getTimestamp())) latest = item;
            }
            return latest == null ? null : new ExitRecord(latest.getReason(), latest.getStatus(),
                    latest.getImportance(), latest.getTimestamp(), latest.getDescription());
        } catch (RuntimeException error) {
            DiagnosticsLog.record("process exit history unavailable "
                    + error.getClass().getSimpleName());
            return null;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    private static String safe(String value) {
        if (value == null) return "-";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) : normalized;
    }

    static final class ExitRecord {
        final int reason;
        final int status;
        final int importance;
        final long timestampMs;
        final String description;

        ExitRecord(int reason, int status, int importance, long timestampMs, String description) {
            this.reason = reason;
            this.status = status;
            this.importance = importance;
            this.timestampMs = timestampMs;
            this.description = description == null ? "" : description;
        }

        boolean wasForegroundService() {
            return importance > 0
                    && importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
        }
    }
}
