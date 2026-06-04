package com.dushnyj.tgproxy;

import org.junit.Test;

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
}
