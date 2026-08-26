package com.dushnyj.tgproxy;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProcessExitTrackerTest {
    @Test
    public void api34PackageUpdateOfForegroundServiceMigratesRunningState() {
        long now = 1_000_000L;
        ProcessExitTracker.ExitRecord exit = new ProcessExitTracker.ExitRecord(
                ApplicationExitInfo.REASON_PACKAGE_UPDATED, 0,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
                now - 1_000L, "updated");

        assertTrue(ProcessExitTracker.isLikelyPackageUpdateOfRunningService(exit, 34, now));
    }

    @Test
    public void api30LegacyUpdateReasonRequiresForegroundServiceImportance() {
        long now = 1_000_000L;
        ProcessExitTracker.ExitRecord running = new ProcessExitTracker.ExitRecord(
                ApplicationExitInfo.REASON_USER_REQUESTED, 0,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
                now - 1_000L, "legacy update");
        ProcessExitTracker.ExitRecord stopped = new ProcessExitTracker.ExitRecord(
                ApplicationExitInfo.REASON_USER_REQUESTED, 0,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
                now - 1_000L, "legacy update");

        assertTrue(ProcessExitTracker.isLikelyPackageUpdateOfRunningService(running, 30, now));
        assertFalse(ProcessExitTracker.isLikelyPackageUpdateOfRunningService(stopped, 30, now));
    }

    @Test
    public void explicitRestartAfterUserStopSupersedesOldExit() {
        long now = 1_000_000L;
        ProcessExitTracker.ExitRecord exit = new ProcessExitTracker.ExitRecord(
                ApplicationExitInfo.REASON_USER_REQUESTED, 0,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
                now - 5_000L, "user stop");

        assertTrue(ProcessExitTracker.isUnhandledUserStop(exit, now, 0L));
        assertFalse(ProcessExitTracker.isUnhandledUserStop(exit, now, now - 1_000L));
    }

    @Test
    public void unconsumedSystemStopDoesNotExpireAfterThirtyMinutes() {
        long now = 100_000_000L;
        ProcessExitTracker.ExitRecord exit = new ProcessExitTracker.ExitRecord(
                ApplicationExitInfo.REASON_USER_REQUESTED, 0,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
                now - 4 * 60 * 60_000L, "user stop");

        assertTrue(ProcessExitTracker.isUnhandledUserStop(exit, now, 0L));
    }
}
