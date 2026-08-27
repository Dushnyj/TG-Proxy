package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServiceStateTest {
    @Test
    public void processWithoutListeningPortIsNotActive() {
        ServiceState state = ServiceState.from(true, true, false, false,
                RouteState.active(RouteCandidate.directWs(2, false, "149.154.167.220"),
                        "", 80, "stable"));

        assertEquals(ServiceState.Status.DEGRADED, state.status());
        assertFalse(state.isFullyActive());
    }

    @Test
    public void pausedServiceReportsSleepMode() {
        ServiceState state = ServiceState.from(true, true, true, true,
                RouteState.inactive("paused"));

        assertEquals(ServiceState.Status.SLEEP, state.status());
        assertFalse(state.isFullyActive());
    }

    @Test
    public void runningEngineWithListeningPortAndRouteIsActive() {
        ServiceState state = ServiceState.from(true, true, true, false,
                RouteState.active(RouteCandidate.directWs(2, false, "149.154.167.220"),
                        "", 80, "stable"));

        assertEquals(ServiceState.Status.ACTIVE, state.status());
        assertTrue(state.isFullyActive());
    }

    @Test
    public void staleRouteEvidenceDoesNotReportActiveProxy() {
        ServiceState state = ServiceState.from(true, true, true, false,
                RouteState.active(RouteCandidate.directWs(2, false, "149.154.167.220"),
                        "", 80, "stable", 1_000L),
                false,
                ServiceState.ROUTE_EVIDENCE_MAX_AGE_MS + 2_000L);

        assertEquals(ServiceState.Status.READY_FOR_TELEGRAM, state.status());
        assertFalse(state.isFullyActive());
    }

    @Test
    public void healthyListenerWithoutTelegramTrafficIsReadyNotBroken() {
        ServiceState state = ServiceState.from(true, true, true, false,
                RouteState.inactive("no verified route"), false, false,
                0L, false, 10_000L);

        assertEquals(ServiceState.Status.READY_FOR_TELEGRAM, state.status());
    }

    @Test
    public void telegramSocketWithoutRouteIsConnecting() {
        ServiceState state = ServiceState.from(true, true, true, false,
                RouteState.inactive("waiting first payload"), false, false,
                1L, false, 10_000L);

        assertEquals(ServiceState.Status.CONNECTING_TELEGRAM, state.status());
    }

    @Test
    public void telegramSocketWithOnlyStaleRouteEvidenceIsConnecting() {
        ServiceState state = ServiceState.from(true, true, true, false,
                RouteState.active(RouteCandidate.directWs(2, false, "149.154.167.220"),
                        "", 80, "stale", 1_000L), false, false,
                1L, false, ServiceState.ROUTE_EVIDENCE_MAX_AGE_MS + 2_000L);

        assertEquals(ServiceState.Status.CONNECTING_TELEGRAM, state.status());
    }

    @Test
    public void recentUpstreamFailureIsDegraded() {
        ServiceState state = ServiceState.from(true, true, true, false,
                RouteState.inactive("route failed"), false, false,
                1L, true, 10_000L);

        assertEquals(ServiceState.Status.DEGRADED, state.status());
    }

    @Test
    public void activeRouteWinsOverFailureOfAnotherCandidate() {
        ServiceState state = ServiceState.from(true, true, true, false,
                RouteState.active(RouteCandidate.directWs(2, false, "149.154.167.220"),
                        "", 95, "stable", 9_900L),
                false, false, 6L, true, 10_000L);

        assertEquals(ServiceState.Status.ACTIVE, state.status());
    }

    @Test
    public void oldFailureWithoutTelegramClientDoesNotMakeHealthyListenerBroken() {
        ServiceState state = ServiceState.from(true, true, true, false,
                RouteState.inactive("previous route failed"), false, false,
                0L, true, 10_000L);

        assertEquals(ServiceState.Status.READY_FOR_TELEGRAM, state.status());
    }

    @Test
    public void retryingEngineIsNotReportedAsStartingOrActive() {
        ServiceState state = ServiceState.from(true, false, false, false,
                RouteState.inactive("bind failed"),
                true,
                10_000L);

        assertEquals(ServiceState.Status.RETRYING, state.status());
        assertFalse(state.isFullyActive());
    }

    @Test
    public void deadEngineIsExplicitWhenServiceHasNoRetryAndNoListener() {
        ServiceState state = ServiceState.from(true, false, false, false,
                RouteState.inactive("engine stopped"),
                false,
                10_000L);

        assertEquals(ServiceState.Status.DEAD, state.status());
        assertFalse(state.isFullyActive());
    }
}
