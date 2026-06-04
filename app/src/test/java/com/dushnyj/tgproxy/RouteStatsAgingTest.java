package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class RouteStatsAgingTest {
    @Test
    public void staleFailuresExpireSoRouteIsNotBrokenForever() {
        RouteStats stats = new RouteStats();
        stats.recordFailure(RouteError.TIMEOUT, 1_000L);

        stats.pruneExpired(1_000L + RouteStats.STATS_RETENTION_MS + 1L);

        assertEquals(0, stats.totalFailures());
        assertEquals(RouteError.NONE, stats.lastError());
        assertFalse(stats.isCoolingDown(1_000L + RouteStats.STATS_RETENTION_MS + 1L));
    }

    @Test
    public void staleSuccessSamplesExpireSoOldGoodRouteDoesNotStayPreferredForever() {
        RouteStats stats = new RouteStats();
        stats.recordSuccess(1_000L, 40);
        stats.recordSuccess(2_000L, 45);
        stats.recordSuccess(3_000L, 50);

        stats.pruneExpired(3_000L + RouteStats.STATS_RETENTION_MS + 1L);

        assertEquals(0, stats.successCount());
        assertEquals(-1, stats.medianLatencyMs());
        assertFalse(stats.hasStableEvidence());
    }
}
