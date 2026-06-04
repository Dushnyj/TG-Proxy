package com.dushnyj.tgproxy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class MainUiState {
    private MainUiState() {}

    static final long STATS_REFRESH_INTERVAL_MS = 1_000L;
    static final long NOTIFICATION_REFRESH_INTERVAL_MS = 1_000L;
    static final long AUTO_PING_INTERVAL_MS = 3_000L;
    static final long PING_MEASUREMENT_TTL_MS = 60_000L;
    static final int PING_ERROR_MS = -2;

    enum MainAction {
        DIAGNOSTICS,
        SETTINGS,
        TELEGRAM,
        GITHUB
    }

    enum SettingsSection {
        CONNECTION,
        SYSTEM,
        ABOUT
    }

    enum SettingsContent {
        CONNECTION,
        ROUTING,
        PROFILES,
        VPS_RELAY,
        CLOUDFLARE_WORKER,
        IMPORT_EXPORT,
        DIAGNOSTICS_LOGS,
        BEHAVIOR,
        INTERFACE,
        UPDATES,
        ABOUT
    }

    private static final List<MainAction> MAIN_ACTIONS = Collections.unmodifiableList(Arrays.asList(
            MainAction.DIAGNOSTICS,
            MainAction.SETTINGS,
            MainAction.TELEGRAM,
            MainAction.GITHUB));

    private static final List<SettingsSection> SETTINGS_SECTIONS = Collections.unmodifiableList(Arrays.asList(
            SettingsSection.CONNECTION,
            SettingsSection.SYSTEM,
            SettingsSection.ABOUT));
    private static final List<SettingsContent> CONNECTION_CONTENT =
            Collections.unmodifiableList(Arrays.asList(
                    SettingsContent.CONNECTION,
                    SettingsContent.PROFILES,
                    SettingsContent.ROUTING,
                    SettingsContent.VPS_RELAY,
                    SettingsContent.CLOUDFLARE_WORKER,
                    SettingsContent.IMPORT_EXPORT));
    private static final List<SettingsContent> SYSTEM_CONTENT =
            Collections.unmodifiableList(Arrays.asList(
                    SettingsContent.INTERFACE,
                    SettingsContent.BEHAVIOR,
                    SettingsContent.DIAGNOSTICS_LOGS,
                    SettingsContent.UPDATES));
    private static final List<SettingsContent> ABOUT_CONTENT =
            Collections.unmodifiableList(Collections.singletonList(SettingsContent.ABOUT));

    static boolean canOpenSettings(boolean proxyRunning) {
        return !proxyRunning;
    }

    static List<MainAction> mainActions() {
        return MAIN_ACTIONS;
    }

    static List<SettingsSection> settingsSections() {
        return SETTINGS_SECTIONS;
    }

    static List<SettingsContent> settingsContent(SettingsSection section) {
        if (section == SettingsSection.SYSTEM) return SYSTEM_CONTENT;
        if (section == SettingsSection.ABOUT) return ABOUT_CONTENT;
        return CONNECTION_CONTENT;
    }

    static String trafficSummary(String bytesUp, String bytesDown) {
        return "↑ " + bytesUp + "   ↓ " + bytesDown;
    }

    static String emptyTrafficSummary() {
        return trafficSummary("-", "-");
    }

    static String uptimeSummary(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d",
                seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L);
    }

    static String pingSummary(int pingMs) {
        if (pingMs == PING_ERROR_MS) return "error";
        return pingMs < 0 ? "-" : pingMs + " ms";
    }

    static int displayedPing(RouteState routeState, String measuredRouteKey,
                             int measuredPingMs, long measuredAtMs, long nowMs) {
        if (routeState != null
                && routeState.key().equals(measuredRouteKey == null ? "" : measuredRouteKey)
                && nowMs - measuredAtMs <= PING_MEASUREMENT_TTL_MS) {
            return measuredPingMs;
        }
        return routeState == null ? -1 : routeState.pingMs();
    }

    static String connectionSummary(int activeConnections) {
        return activeConnections < 0 ? "-" : String.valueOf(activeConnections);
    }
}
