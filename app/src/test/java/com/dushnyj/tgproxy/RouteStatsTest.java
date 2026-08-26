package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteStatsTest {
    @Test
    public void verifiedTrafficRefreshesEvidenceWithoutInflatingSuccessCounter() {
        RouteStats stats = new RouteStats();
        stats.recordSuccess(1_000L, 40);

        stats.recordVerifiedTraffic(20_000L);

        assertEquals(1, stats.successCount());
        assertEquals(20_000L, stats.lastSuccessMs());
        assertEquals(RouteError.NONE, stats.lastError());
    }

    @Test
    public void recordsSeparateFailureTypes() {
        RouteStats stats = new RouteStats();

        stats.recordFailure(RouteError.TOO_MANY_REQUESTS, 1_000L);
        stats.recordFailure(RouteError.TIMEOUT, 2_000L);
        stats.recordFailure(RouteError.RESET, 3_000L);

        assertEquals(1, stats.failureCount(RouteError.TOO_MANY_REQUESTS));
        assertEquals(1, stats.failureCount(RouteError.TIMEOUT));
        assertEquals(1, stats.failureCount(RouteError.RESET));
        assertEquals(3, stats.totalFailures());
    }

    @Test
    public void cooldownDependsOnFailureSeverity() {
        RouteStats stats = new RouteStats();

        stats.recordFailure(RouteError.TOO_MANY_REQUESTS, 10_000L);
        assertTrue(stats.isCoolingDown(20_000L));
        assertFalse(stats.isCoolingDown(70_000L));

        stats.recordFailure(RouteError.TIMEOUT, 100_000L);
        assertTrue(stats.isCoolingDown(110_000L));
        assertFalse(stats.isCoolingDown(121_000L));
    }

    @Test
    public void successClearsCooldownAndKeepsStableLatency() {
        RouteStats stats = new RouteStats();

        stats.recordFailure(RouteError.TIMEOUT, 1_000L);
        assertTrue(stats.isCoolingDown(2_000L));

        stats.recordSuccess(3_000L, 300);
        stats.recordSuccess(4_000L, 120);
        stats.recordSuccess(5_000L, 180);

        assertFalse(stats.isCoolingDown(6_000L));
        assertEquals(3, stats.successCount());
        assertEquals(180, stats.medianLatencyMs());
    }
}
