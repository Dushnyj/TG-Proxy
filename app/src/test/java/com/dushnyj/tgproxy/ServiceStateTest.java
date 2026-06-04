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
}
