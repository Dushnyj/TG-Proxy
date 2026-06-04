package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainUiStateTest {
    @Test
    public void settingsAreLockedWhileProxyIsRunning() {
        assertFalse(MainUiState.canOpenSettings(true));
        assertTrue(MainUiState.canOpenSettings(false));
    }

    @Test
    public void trafficSummaryUsesOneStableLine() {
        assertEquals("↑ 12 KB   ↓ 34 KB",
                MainUiState.trafficSummary("12 KB", "34 KB"));
    }

    @Test
    public void idleTrafficSummaryStillUsesOneStableLine() {
        assertEquals("↑ -   ↓ -", MainUiState.emptyTrafficSummary());
    }

    @Test
    public void uptimeSummaryUsesFixedWidthClock() {
        assertEquals("01:02:03", MainUiState.uptimeSummary(3_723_000L));
    }

    @Test
    public void mainActionsUseDiagnosticsInsteadOfManualPing() {
        assertEquals(Arrays.asList(
                MainUiState.MainAction.DIAGNOSTICS,
                MainUiState.MainAction.SETTINGS,
                MainUiState.MainAction.TELEGRAM,
                MainUiState.MainAction.GITHUB),
                MainUiState.mainActions());
    }

    @Test
    public void settingsSectionsAreSplitIntoNavigationGroups() {
        assertEquals(Arrays.asList(
                MainUiState.SettingsSection.CONNECTION,
                MainUiState.SettingsSection.SYSTEM,
                MainUiState.SettingsSection.ABOUT),
                MainUiState.settingsSections());
    }

    @Test
    public void settingsContentIsGroupedUnderThreeTabs() {
        assertEquals(Arrays.asList(
                MainUiState.SettingsContent.CONNECTION,
                MainUiState.SettingsContent.PROFILES,
                MainUiState.SettingsContent.ROUTING,
                MainUiState.SettingsContent.VPS_RELAY,
                MainUiState.SettingsContent.CLOUDFLARE_WORKER,
                MainUiState.SettingsContent.IMPORT_EXPORT),
                MainUiState.settingsContent(MainUiState.SettingsSection.CONNECTION));
        assertEquals(Arrays.asList(
                MainUiState.SettingsContent.INTERFACE,
                MainUiState.SettingsContent.BEHAVIOR,
                MainUiState.SettingsContent.DIAGNOSTICS_LOGS,
                MainUiState.SettingsContent.UPDATES),
                MainUiState.settingsContent(MainUiState.SettingsSection.SYSTEM));
        assertEquals(Arrays.asList(
                MainUiState.SettingsContent.ABOUT),
                MainUiState.settingsContent(MainUiState.SettingsSection.ABOUT));
    }

    @Test
    public void realtimeIndicatorsUseShortRefreshIntervals() {
        assertEquals(1_000L, MainUiState.STATS_REFRESH_INTERVAL_MS);
        assertEquals(1_000L, MainUiState.NOTIFICATION_REFRESH_INTERVAL_MS);
        assertEquals(3_000L, MainUiState.AUTO_PING_INTERVAL_MS);
    }

    @Test
    public void pingSummaryDoesNotExposeNegativeValues() {
        assertEquals("-", MainUiState.pingSummary(-1));
        assertEquals("261 ms", MainUiState.pingSummary(261));
    }

    @Test
    public void failedAutoPingOverridesPreviousRoutePing() {
        RouteState routeState = RouteState.active(
                RouteCandidate.publicCloudflare(2, "lovetrue.co.uk"),
                "lovetrue.co.uk", 261, "stable");

        assertEquals(MainUiState.PING_ERROR_MS, MainUiState.displayedPing(
                routeState,
                routeState.key(),
                MainUiState.PING_ERROR_MS,
                10_000L,
                10_500L));
        assertEquals("error", MainUiState.pingSummary(MainUiState.PING_ERROR_MS));
    }

    @Test
    public void connectionSummaryDoesNotExposeNegativeValues() {
        assertEquals("-", MainUiState.connectionSummary(-1));
        assertEquals("3", MainUiState.connectionSummary(3));
    }
}
