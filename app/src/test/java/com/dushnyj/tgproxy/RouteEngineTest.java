package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteEngineTest {
    @Test
    public void testDcUsesTestIpRelayAndWorkerButNeverCloudflareProxy() {
        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .dcRedirects(MtProtoConfig.testDcRules())
                .testDc(true)
                .workerDomains(Collections.singletonList("worker.example.com"))
                .publicCfDomains(Collections.singletonList("cf.example.com"))
                .vpsRelay("Relay", "relay.example.com", 443)
                .build();

        List<RouteCandidate> routes = new RouteEngine().buildCandidates(settings, 2, true);

        assertTrue(routes.stream().anyMatch(route -> route.type() == RouteType.DIRECT_WS
                && route.test() && "149.154.167.40".equals(route.endpoint())));
        assertTrue(routes.stream().anyMatch(route -> route.type() == RouteType.VPS_RELAY
                && route.test()));
        assertTrue(routes.stream().anyMatch(route -> route.type() == RouteType.WORKER
                && route.test()));
        assertFalse(routes.stream().anyMatch(route -> route.type() == RouteType.PUBLIC_CLOUDFLARE
                || route.type() == RouteType.CUSTOM_CLOUDFLARE));
    }

    @Test
    public void futureProductionDcCanReachAnUpdatedVpsRelayWithoutAppWhitelist() throws Exception {
        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .vpsRelay(dynamicRelay())
                .build();

        List<RouteCandidate> routes = new RouteEngine().buildCandidates(settings, 204, false);

        assertEquals(1, routes.size());
        assertEquals(RouteType.VPS_RELAY, routes.get(0).type());
        assertEquals(204, routes.get(0).dc());
    }

    @Test
    public void directOnlyIsAnAllowListNotJustAPreference() {
        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .routePreference(RoutePreference.RELAY_FIRST)
                .routeAvailability(RouteAvailability.directOnly())
                .dcRedirects(dcRules())
                .vpsRelay("Relay", "relay.example.com", 443)
                .workerDomains(Collections.singletonList("worker.example.com"))
                .publicCfDomains(Collections.singletonList("cf.example.com"))
                .build();

        List<RouteCandidate> routes = new RouteEngine().buildCandidates(settings, 2, false);

        assertEquals(1, routes.size());
        assertEquals(RouteType.DIRECT_WS, routes.get(0).type());
    }
    @Test
    public void wifiAutoStartsWithDirectAndKeepsCloudflareAsFallback() {
        RouteEngine engine = new RouteEngine();

        RoutePlan plan = engine.plan(RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.wifi("home"))
                .cfMode(MtProtoProxyEngine.CF_MODE_AUTO)
                .dcRedirects(dcRules())
                .publicCfDomains(Arrays.asList("pclead.co.uk", "lovetrue.co.uk"))
                .build(), 2, false, "", new LinkedHashMap<>(), 1_000L);

        assertEquals(Arrays.asList(
                RouteType.DIRECT_WS,
                RouteType.PUBLIC_CLOUDFLARE),
                plan.routeTypes());
    }

    @Test
    public void mobileAutoStartsWithCloudflareAndKeepsDirectAsFallback() {
        RouteEngine engine = new RouteEngine();

        RoutePlan plan = engine.plan(RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.mobile("tele2"))
                .cfMode(MtProtoProxyEngine.CF_MODE_AUTO)
                .dcRedirects(dcRules())
                .publicCfDomains(Arrays.asList("pclead.co.uk", "lovetrue.co.uk"))
                .build(), 2, false, "", new LinkedHashMap<>(), 1_000L);

        assertEquals(Arrays.asList(
                RouteType.PUBLIC_CLOUDFLARE,
                RouteType.DIRECT_WS),
                plan.routeTypes());
    }

    @Test
    public void cooldownRemovesFailingRouteFromPlan() {
        RouteEngine engine = new RouteEngine();
        Map<String, RouteStats> stats = new LinkedHashMap<>();
        RouteCandidate cf = RouteCandidate.publicCloudflare(2, "public-cf");
        RouteStats cfStats = new RouteStats();
        cfStats.recordFailure(RouteError.TOO_MANY_REQUESTS, 10_000L);
        stats.put(cf.key(), cfStats);

        RoutePlan plan = engine.plan(RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.mobile("tele2"))
                .cfMode(MtProtoProxyEngine.CF_MODE_AUTO)
                .dcRedirects(dcRules())
                .publicCfDomains(Arrays.asList("pclead.co.uk"))
                .build(), 2, false, cf.key(), stats, 20_000L);

        assertFalse(plan.routeTypes().contains(RouteType.PUBLIC_CLOUDFLARE));
        assertEquals(RouteType.DIRECT_WS, plan.selected().type());
    }

    @Test
    public void hysteresisDoesNotSwitchOnOneBetterPing() {
        RouteEngine engine = new RouteEngine();
        RouteCandidate cf = RouteCandidate.publicCloudflare(2, "public-cf");
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        Map<String, RouteStats> stats = new LinkedHashMap<>();
        RouteStats cfStats = new RouteStats();
        cfStats.recordSuccess(1_000L, 180);
        cfStats.recordSuccess(2_000L, 190);
        cfStats.recordSuccess(3_000L, 170);
        stats.put(cf.key(), cfStats);

        RouteStats directStats = new RouteStats();
        directStats.recordSuccess(4_000L, 90);
        stats.put(direct.key(), directStats);

        RoutePlan plan = engine.plan(Arrays.asList(direct, cf), cf.key(), stats, 5_000L);

        assertEquals(cf.key(), plan.selected().key());
        assertTrue(plan.requiresWarmupBeforeSwitch().isEmpty());
    }

    @Test
    public void currentRouteFailureAllowsSwitchToFallback() {
        RouteEngine engine = new RouteEngine();
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        RouteCandidate cf = RouteCandidate.publicCloudflare(2, "public-cf");
        Map<String, RouteStats> stats = new LinkedHashMap<>();
        RouteStats directStats = new RouteStats();
        directStats.recordFailure(RouteError.TIMEOUT, 10_000L);
        stats.put(direct.key(), directStats);
        RouteStats cfStats = new RouteStats();
        cfStats.recordSuccess(11_000L, 260);
        cfStats.recordSuccess(12_000L, 240);
        cfStats.recordSuccess(13_000L, 230);
        stats.put(cf.key(), cfStats);

        RoutePlan plan = engine.plan(Arrays.asList(direct, cf), direct.key(), stats, 14_000L);

        assertEquals(cf.key(), plan.selected().key());
        assertEquals(cf.key(), plan.requiresWarmupBeforeSwitch());
    }

    @Test
    public void vpsRelayStatsAreScopedByDcAndMedia() {
        RouteEngine engine = new RouteEngine();
        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.mobile("25001"))
                .routePreference(RoutePreference.RELAY_FIRST)
                .vpsRelay("VPS Relay", "relay.example.com", 443)
                .dcRedirects(dcRules())
                .build();

        RouteCandidate mainDc2 = engine.buildCandidates(settings, 2, false).get(0);
        RouteCandidate mediaDc2 = engine.buildCandidates(settings, 2, true).get(0);
        RouteCandidate mainDc4 = engine.buildCandidates(settings, 4, false).get(0);

        assertEquals("vps_relay:dc2", mainDc2.key());
        assertEquals("vps_relay:dc2:media", mediaDc2.key());
        assertEquals("vps_relay:dc4", mainDc4.key());
    }

    @Test
    public void mediaRelayFailureDoesNotCooldownMainRelayRoute() {
        RouteEngine engine = new RouteEngine();
        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.mobile("25001"))
                .routePreference(RoutePreference.RELAY_FIRST)
                .vpsRelay("VPS Relay", "relay.example.com", 443)
                .dcRedirects(dcRules())
                .build();
        RouteCandidate mediaRelay = RouteCandidate.vpsRelay(
                "VPS Relay", "relay.example.com", 443, 2, true);
        Map<String, RouteStats> stats = new LinkedHashMap<>();
        RouteStats mediaStats = new RouteStats();
        mediaStats.recordFailure(RouteError.TIMEOUT, 10_000L);
        stats.put(mediaRelay.key(), mediaStats);

        RoutePlan mainPlan = engine.plan(settings, 2, false, "", stats, 11_000L);

        assertEquals(RouteType.VPS_RELAY, mainPlan.selected().type());
        assertEquals("vps_relay:dc2", mainPlan.selected().key());
    }

    @Test
    public void workerAndCloudflareStatsAreSeparatedForMediaTraffic() {
        RouteEngine engine = new RouteEngine();
        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.mobile("25001"))
                .cfMode(MtProtoProxyEngine.CF_MODE_AUTO)
                .workerDomains(Arrays.asList("worker.example"))
                .publicCfDomains(Arrays.asList("public.example"))
                .dcRedirects(dcRules())
                .build();

        List<RouteCandidate> main = engine.buildCandidates(settings, 2, false);
        List<RouteCandidate> media = engine.buildCandidates(settings, 2, true);

        assertTrue(main.stream().anyMatch(route -> "worker:dc2".equals(route.key())));
        assertTrue(media.stream().anyMatch(route -> "worker:dc2:media".equals(route.key())));
        assertTrue(main.stream().anyMatch(route -> "public_cf:dc2".equals(route.key())));
        assertTrue(media.stream().anyMatch(route -> "public_cf:dc2:media".equals(route.key())));
    }

    @Test
    public void allCoolingRoutesUseEarliestHalfOpenProbeInsteadOfEmptyPlan() {
        RouteEngine engine = new RouteEngine();
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        RouteCandidate cf = RouteCandidate.publicCloudflare(2, false, "public-cf");
        Map<String, RouteStats> stats = new LinkedHashMap<>();
        RouteStats directStats = new RouteStats();
        directStats.recordFailure(RouteError.TIMEOUT, 10_000L);
        stats.put(direct.key(), directStats);
        RouteStats cfStats = new RouteStats();
        cfStats.recordFailure(RouteError.TOO_MANY_REQUESTS, 10_000L);
        stats.put(cf.key(), cfStats);

        RoutePlan plan = engine.plan(Arrays.asList(cf, direct), "", stats, 11_000L);

        assertFalse(plan.isEmpty());
        assertEquals(direct.key(), plan.selected().key());

        RoutePlan concurrentReconnect = engine.plan(
                Arrays.asList(cf, direct), "", stats, 11_001L);
        assertTrue(concurrentReconnect.isEmpty());

        RoutePlan afterLease = engine.plan(
                Arrays.asList(cf, direct), "", stats, directStats.cooldownUntilMs());
        assertFalse(afterLease.isEmpty());
    }

    @Test
    public void unknownDcUsesOnlyUpdatedVpsRelayWithoutSpeculativePublicRoutes() throws Exception {
        RouteEngine engine = new RouteEngine();
        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.mobile("25001"))
                .routePreference(RoutePreference.RELAY_FIRST)
                .vpsRelay(dynamicRelay())
                .publicCfDomains(Arrays.asList("public.example"))
                .workerDomains(Arrays.asList("worker.example"))
                .build();

        RoutePlan plan = engine.plan(settings, 204, true, "", new LinkedHashMap<>(), 11_000L);

        assertFalse(plan.isEmpty());
        assertEquals(1, plan.routes().size());
        assertEquals(RouteType.VPS_RELAY, plan.selected().type());
        assertEquals(204, plan.selected().dc());
    }

    @Test
    public void futureDcWithExplicitRawIpIsNotMisroutedToSyntheticWebSocketHost() {
        RouteEngine engine = new RouteEngine();
        LinkedHashMap<Integer, String> dcRules = new LinkedHashMap<>();
        dcRules.put(204, "203.0.113.10");
        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.wifi("home"))
                .dcRedirects(dcRules)
                .build();

        RoutePlan plan = engine.plan(settings, 204, false, "", new LinkedHashMap<>(), 11_000L);

        assertTrue(plan.isEmpty());
    }

    @Test
    public void futureTestDcUsesOnlyVpsRelay() throws Exception {
        RouteEngine engine = new RouteEngine();
        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.mobile("25001"))
                .testDc(true)
                .vpsRelay(dynamicRelay())
                .workerDomains(Arrays.asList("worker.example"))
                .build();

        RoutePlan plan = engine.plan(settings, 4, true, "", new LinkedHashMap<>(), 11_000L);

        assertEquals(1, plan.routes().size());
        assertEquals(RouteType.VPS_RELAY, plan.selected().type());
        assertTrue(plan.selected().test());
    }

    @Test
    public void staticRelayCapabilitiesSuppressUnsupportedFutureDc() throws Exception {
        VpsRelayCapabilities capabilities = VpsRelayCapabilities.parse(
                "{\"name\":\"tgproxy-relay\",\"protocol\":{\"min\":1,\"max\":2},"
                        + "\"topology\":{\"productionDcs\":[1,2,3,4,5],\"testDcs\":[1,2,3]}}" );
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Relay", "relay.example.com",
                443, true, "/apiws", "token", "").withCapabilities(capabilities);
        RouteEngine.Settings settings = RouteEngine.Settings.builder().vpsRelay(relay).build();

        assertTrue(new RouteEngine().buildCandidates(settings, 204, false).isEmpty());
    }

    @Test
    public void dynamicRelayCapabilitiesAllowDcAddedAfterLastClientCheck() throws Exception {
        VpsRelayCapabilities capabilities = VpsRelayCapabilities.parse(
                "{\"name\":\"tgproxy-relay\",\"protocol\":{\"min\":1,\"max\":2},"
                        + "\"topology\":{\"dynamic\":true,\"revision\":7,"
                        + "\"productionDcs\":[1,2,3,4,5],\"testDcs\":[1,2,3]}}" );
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Relay", "relay.example.com",
                443, true, "/apiws", "token", "").withCapabilities(capabilities);
        RouteEngine.Settings settings = RouteEngine.Settings.builder().vpsRelay(relay).build();

        List<RouteCandidate> candidates = new RouteEngine().buildCandidates(settings, 204, true);

        assertEquals(1, candidates.size());
        assertEquals(RouteType.VPS_RELAY, candidates.get(0).type());
    }

    @Test
    public void legacyRelayWithoutCapabilitiesDoesNotAdvertiseUnknownDc() {
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Legacy Relay",
                "relay.example.com", 443, true, "/apiws", "token", "");

        assertTrue(new RouteEngine().buildCandidates(
                RouteEngine.Settings.builder().vpsRelay(relay).build(),
                204, false).isEmpty());
        assertFalse(new RouteEngine().buildCandidates(
                RouteEngine.Settings.builder().vpsRelay(relay).build(),
                2, false).isEmpty());
    }

    private static VpsRelayConfig dynamicRelay() throws Exception {
        VpsRelayCapabilities capabilities = VpsRelayCapabilities.parse(
                "{\"name\":\"tgproxy-relay\",\"protocol\":{\"min\":1,\"max\":2},"
                        + "\"topology\":{\"dynamic\":true,\"revision\":7,"
                        + "\"productionDcs\":[1,2,3,4,5],\"testDcs\":[1,2,3]}}" );
        return VpsRelayConfig.manual(true, "Relay", "relay.example.com",
                443, true, "/apiws", "token", "").withCapabilities(capabilities);
    }

    private static Map<Integer, String> dcRules() {
        LinkedHashMap<Integer, String> rules = new LinkedHashMap<>();
        rules.put(2, "149.154.167.220");
        rules.put(4, "149.154.167.220");
        return rules;
    }
}
