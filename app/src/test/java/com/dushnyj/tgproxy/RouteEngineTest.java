package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteEngineTest {
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

    private static Map<Integer, String> dcRules() {
        LinkedHashMap<Integer, String> rules = new LinkedHashMap<>();
        rules.put(2, "149.154.167.220");
        rules.put(4, "149.154.167.220");
        return rules;
    }
}
