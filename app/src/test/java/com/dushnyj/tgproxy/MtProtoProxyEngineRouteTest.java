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
    public void switchingRelayDoesNotCarryOldRelayCooldownIntoNewServer() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        RouteStats stale = new RouteStats();
        stale.recordFailure(RouteError.TIMEOUT, 10_000L);
        Map<String, RouteStats> stats = new LinkedHashMap<>();
        stats.put("vps_relay:dc2", stale);
        VpsRelayConfig first = VpsRelayConfig.manual(true, "First", "one.example.com",
                443, true, "/apiws", "token-one", "");
        VpsRelayConfig second = VpsRelayConfig.manual(true, "Second", "two.example.com",
                443, true, "/apiws", "token-two", "");

        engine.applyRuntimeConfiguration(RuntimeConfigSnapshot.builder()
                .relay(first).routeStats(stats).build());
        assertTrue(engine.routeStatsSnapshot().containsKey("vps_relay:dc2"));

        engine.applyRuntimeConfiguration(RuntimeConfigSnapshot.builder()
                .relay(second).routeStats(stats).build());

        assertFalse(engine.routeStatsSnapshot().containsKey("vps_relay:dc2"));
    }


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
    public void cfWarmupKeysAreSeparatedByNetworkProfileWithoutBurstingEveryDc() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220\n4:149.154.167.220");

        engine.setMobileNetwork(false);
        assertEquals(Arrays.asList(
                "wifi:ssid:wifi:2:main", "wifi:ssid:wifi:2:media"), engine.cfWarmupKeys());

        engine.setMobileNetwork(true);
        assertEquals(Arrays.asList(
                "mobile:name:mobile:2:main", "mobile:name:mobile:2:media"), engine.cfWarmupKeys());
    }

    @Test
    public void currentRouteStateIsInactiveUntilRouteHasVerifiedSuccess() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_OFF);

        RouteState state = engine.currentRouteState();

        assertFalse(state.active());
        assertEquals("no verified route on current network", state.reason());
    }

    @Test
    public void persistedRouteStatsDoNotPretendCurrentNetworkWasVerified() {
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

        assertFalse(state.active());
        assertEquals("no verified route on current network", state.reason());
        assertEquals(verifiedAt,
                engine.routeStatsSnapshot().get(direct.key()).lastSuccessMs());
    }

    @Test
    public void networkAttachmentChangeInvalidatesEvidenceButKeepsLearnedStats() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_OFF);
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        long generation = engine.routeGeneration();
        engine.recordRouteSuccess(direct, 80, generation);

        assertTrue(engine.currentRouteState().active());
        engine.onNetworkAttachmentChanged();

        assertFalse(engine.currentRouteState().active());
        assertEquals(1, engine.routeStatsSnapshot().get(direct.key()).successCount());
        assertTrue(engine.routeGeneration() > generation);
    }

    @Test
    public void currentRouteStateUsesFreshVerifiedMediaScopeInsteadOfArbitraryFirstDc() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220\n4:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_OFF);
        RouteCandidate dc4Media = RouteCandidate.directWs(4, true, "149.154.167.220");

        engine.recordRouteSuccess(dc4Media, 75, engine.routeGeneration());
        RouteState state = engine.currentRouteState();

        assertTrue(state.active());
        assertEquals(4, state.candidate().dc());
        assertTrue(state.candidate().media());
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

    @Test
    public void cleanSessionReleaseKeepsLastVerifiedRouteForCurrentNetwork() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_OFF);
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        long generation = engine.routeGeneration();

        engine.recordRouteSuccess(direct, 80, generation, direct.endpoint(), 41L);
        engine.unregisterRouteEvidence("2:main", 41L, direct.key(), generation, false);

        assertTrue(engine.currentRouteState().active());
        assertEquals(direct.key(), engine.currentRouteState().candidate().key());
    }

    @Test
    public void failedLastSessionInvalidatesActiveRouteEvidence() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_OFF);
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        long generation = engine.routeGeneration();

        engine.recordRouteSuccess(direct, 80, generation, direct.endpoint(), 42L);
        engine.unregisterRouteEvidence("2:main", 42L, direct.key(), generation, true);

        assertFalse(engine.currentRouteState().active());
    }

    @Test
    public void failedSessionDoesNotInvalidateHealthyPeerOnSameRoute() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_OFF);
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        long generation = engine.routeGeneration();

        engine.recordRouteSuccess(direct, 80, generation, direct.endpoint(), 43L);
        engine.recordRouteSuccess(direct, 70, generation, direct.endpoint(), 44L);
        boolean healthyPeer = engine.unregisterRouteEvidence(
                "2:main", 43L, direct.key(), generation, true);

        assertTrue(healthyPeer);
        assertTrue(engine.currentRouteState().active());
    }

    @Test
    public void routeIdentityAndEndpointSwitchAtomicallyAndPromoteHealthyFallback()
            throws Exception {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_AUTO);
        engine.setCfProxyDomains(Collections.singletonList("fallback.example"));
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        RouteCandidate cloudflare = RouteCandidate.publicCloudflare(2, false, "public-cf");
        long generation = engine.routeGeneration();

        engine.recordRouteSuccess(direct, 80, generation, direct.endpoint(), 51L);
        Thread.sleep(2L);
        engine.recordRouteSuccess(cloudflare, 90, generation, "fallback.example", 52L);

        RouteState cloudflareState = engine.currentRouteState();
        assertEquals(cloudflare.key(), cloudflareState.key());
        assertEquals("fallback.example", cloudflareState.activeEndpoint());

        engine.unregisterRouteEvidence("2:main", 52L, cloudflare.key(), generation, false);

        RouteState directState = engine.currentRouteState();
        assertEquals(direct.key(), directState.key());
        assertEquals(direct.endpoint(), directState.activeEndpoint());
    }
}
