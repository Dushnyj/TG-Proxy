package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MtProtoProxyEngineRouteTest {

    @Test
    public void cfProxyIsPreferredBeforeDirectTelegramWhenEnabled() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220\n4:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_ON);

        assertEquals(Arrays.asList(
                MtProtoProxyEngine.ROUTE_CF_PROXY,
                MtProtoProxyEngine.ROUTE_DIRECT),
                engine.routePlanForDc(2, false));
    }

    @Test
    public void workerThenCfThenDirectWhenAllRoutesAreConfigured() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220\n4:149.154.167.220");
        engine.setCfWorkerDomains(Collections.singletonList("worker.example"));
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_ON);

        assertEquals(Arrays.asList(
                MtProtoProxyEngine.ROUTE_WORKER,
                MtProtoProxyEngine.ROUTE_CF_PROXY,
                MtProtoProxyEngine.ROUTE_DIRECT),
                engine.routePlanForDc(2, false));
    }

    @Test
    public void directRouteRemainsAvailableWhenRelaysAreDisabled() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_OFF);

        assertEquals(Collections.singletonList(MtProtoProxyEngine.ROUTE_DIRECT),
                engine.routePlanForDc(2, false));
    }

    @Test
    public void autoModeTriesDirectBeforeCfFallback() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_AUTO);

        assertEquals(Arrays.asList(
                MtProtoProxyEngine.ROUTE_DIRECT,
                MtProtoProxyEngine.ROUTE_CF_PROXY),
                engine.routePlanForDc(2, false));
    }

    @Test
    public void wifiAutoModeKeepsWorkerAfterDirectAndBeforeCf() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfWorkerDomains(Collections.singletonList("worker.example"));
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_AUTO);

        assertEquals(Arrays.asList(
                MtProtoProxyEngine.ROUTE_DIRECT,
                MtProtoProxyEngine.ROUTE_WORKER,
                MtProtoProxyEngine.ROUTE_CF_PROXY),
                engine.routePlanForDc(2, false));
    }

    @Test
    public void autoModeTriesCfBeforeDirectOnMobileNetwork() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setMobileNetwork(true);
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_AUTO);

        assertEquals(Arrays.asList(
                MtProtoProxyEngine.ROUTE_CF_PROXY,
                MtProtoProxyEngine.ROUTE_DIRECT),
                engine.routePlanForDc(2, false));
    }

    @Test
    public void vpsRelayParticipatesOnlyForAllowedNetworkProfile() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setNetworkProfile(NetworkProfile.mobile("25020", "t2 Black"));
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_AUTO);
        engine.setVpsRelayConfig(VpsRelayConfig.manual(true, "Relay", "relay.example.com",
                8443, true, "/apiws", "token", "mobile:mccmnc:25020"));

        assertEquals(Arrays.asList(
                MtProtoProxyEngine.ROUTE_VPS_RELAY,
                MtProtoProxyEngine.ROUTE_CF_PROXY,
                MtProtoProxyEngine.ROUTE_DIRECT),
                engine.routePlanForDc(2, false));

        engine.setNetworkProfile(NetworkProfile.wifi("home"));
        assertEquals(Arrays.asList(
                MtProtoProxyEngine.ROUTE_DIRECT,
                MtProtoProxyEngine.ROUTE_CF_PROXY),
                engine.routePlanForDc(2, false));
    }

    @Test
    public void cfWarmupKeysAreSeparatedByNetworkProfile() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220\n4:149.154.167.220");

        engine.setMobileNetwork(false);
        assertEquals(Arrays.asList("wifi:ssid:wifi:2", "wifi:ssid:wifi:4"), engine.cfWarmupKeys());

        engine.setMobileNetwork(true);
        assertEquals(Arrays.asList("mobile:name:mobile:2", "mobile:name:mobile:4"), engine.cfWarmupKeys());
    }

    @Test
    public void currentRouteStateIsInactiveUntilRouteHasVerifiedSuccess() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_OFF);

        RouteState state = engine.currentRouteState();

        assertFalse(state.active());
        assertEquals("no verified route", state.reason());
    }

    @Test
    public void currentRouteStateUsesLastRouteSuccessAsVerificationTime() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_OFF);
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        RouteStats stats = new RouteStats();
        long verifiedAt = System.currentTimeMillis() - 10_000L;
        stats.recordSuccess(verifiedAt, 80);
        Map<String, RouteStats> routeStats = new LinkedHashMap<>();
        routeStats.put(direct.key(), stats);

        engine.replaceRouteStats(routeStats);
        RouteState state = engine.currentRouteState();

        assertEquals(direct.key(), state.key());
        assertEquals(verifiedAt, state.verifiedAtMs());
    }

    @Test
    public void routeStatsFromPreviousNetworkGenerationAreIgnored() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        long oldGeneration = engine.routeGeneration();

        engine.setNetworkProfile(NetworkProfile.mobile("25001", "MTS"));
        engine.recordRouteSuccess(direct, 80, oldGeneration);
        engine.recordRouteFailure(direct, RouteError.TIMEOUT, oldGeneration);

        assertTrue(engine.routeStatsSnapshot().isEmpty());
    }

    @Test
    public void routeStatsFromCurrentNetworkGenerationAreRecorded() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        long generation = engine.routeGeneration();

        engine.recordRouteSuccess(direct, 80, generation);

        assertEquals(1, engine.routeStatsSnapshot().get(direct.key()).successCount());
    }

    @Test
    public void resetDiagnosticsStateClearsRouteEvidenceAndStartsNewGeneration() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        long generation = engine.routeGeneration();

        engine.recordRouteSuccess(direct, 80, generation);
        engine.resetDiagnosticsState();

        assertTrue(engine.routeStatsSnapshot().isEmpty());
        assertFalse(engine.currentRouteState().active());
        assertTrue(engine.routeGeneration() > generation);
    }
}
