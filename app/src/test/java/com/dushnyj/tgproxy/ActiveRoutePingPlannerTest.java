package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ActiveRoutePingPlannerTest {
    @Test
    public void cloudflarePingUsesOnlyActiveDomainAndDoesNotMutateDomainState() {
        RouteState state = RouteState.active(RouteCandidate.publicCloudflare(2, "lovetrue.co.uk"),
                "lovetrue.co.uk", 250, "stable");

        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(state);

        assertEquals(1, targets.size());
        assertEquals("kws2.lovetrue.co.uk", targets.get(0).host());
        assertEquals(RoutePingTarget.Kind.WEBSOCKET, targets.get(0).kind());
        assertFalse(targets.get(0).updatesRouteState());
    }

    @Test
    public void directPingUsesDirectWebsocketHostInsteadOfCloudflarePool() {
        RouteState state = RouteState.active(
                RouteCandidate.directWs(4, false, "149.154.167.220"),
                "", 70, "stable");

        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(state);

        assertEquals(1, targets.size());
        assertEquals("149.154.167.220", targets.get(0).host());
        assertEquals("kws4.web.telegram.org", targets.get(0).sni());
        assertEquals(RoutePingTarget.Kind.WEBSOCKET, targets.get(0).kind());
    }

    @Test
    public void directPingUsesTheEndpointThatWonTheFixedIpDnsRace() {
        RouteState state = RouteState.active(
                RouteCandidate.directWs(4, false, "149.154.167.220"),
                "kws4.web.telegram.org", 70, "stable");

        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(state);

        assertEquals(1, targets.size());
        assertEquals("kws4.web.telegram.org", targets.get(0).host());
        assertEquals("kws4.web.telegram.org", targets.get(0).sni());
    }

    @Test
    public void directPingKeepsTheExactSniThatWonEvenWhenFixedIpWon() {
        RouteState state = RouteState.active(
                RouteCandidate.directWs(4, true, "149.154.167.220"),
                "149.154.167.220", "kws4-1.web.telegram.org",
                70, "stable", 1_000L);

        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(state);

        assertEquals(1, targets.size());
        assertEquals("149.154.167.220", targets.get(0).host());
        assertEquals("kws4-1.web.telegram.org", targets.get(0).sni());
    }

    @Test
    public void cloudflareMarkerWithoutActiveDomainIsNotUsedAsHost() {
        RouteState state = RouteState.active(
                RouteCandidate.publicCloudflare(2, "public-cf"),
                "", -1, "unknown");

        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(state);

        assertEquals(0, targets.size());
    }

    @Test
    public void vpsRelayPingUsesAuthenticatedRelayWebsocketPath() {
        RouteState state = RouteState.active(
                RouteCandidate.vpsRelay("VPS Relay", "relay.example.com", 443),
                "relay.example.com", 73, "stable");
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "VPS Relay",
                "relay.example.com", 443, true, "/apiws", "token", "");

        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(
                state, relay, dcRules());

        assertEquals(1, targets.size());
        assertEquals(RoutePingTarget.Kind.VPS_RELAY, targets.get(0).kind());
        assertEquals("relay.example.com", targets.get(0).host());
        assertEquals("/apiws", targets.get(0).path());
        assertEquals(2, targets.get(0).dc());
    }

    @Test
    public void vpsRelayPingDoesNotFallbackToPlainTcpWithoutToken() {
        RouteState state = RouteState.active(
                RouteCandidate.vpsRelay("VPS Relay", "relay.example.com", 443),
                "relay.example.com", 73, "stable");

        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(
                state, VpsRelayConfig.disabled(), dcRules());

        assertEquals(0, targets.size());
    }

    @Test
    public void testEnvironmentPingKeepsTestScopeForRelay() {
        RouteState state = RouteState.active(
                RouteCandidate.vpsRelay("VPS Relay", "relay.example.com", 443,
                        3, true, true),
                "relay.example.com", 73, "stable");
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "VPS Relay",
                "relay.example.com", 443, true, "/apiws", "token", "");

        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(
                state, relay, dcRules());

        assertEquals(1, targets.size());
        assertEquals(true, targets.get(0).test());
        assertEquals(true, targets.get(0).media());
        assertEquals(3, targets.get(0).dc());
    }

    @Test
    public void directTestEnvironmentPingUsesTelegramTestWebsocketPath() {
        RouteState state = RouteState.active(
                RouteCandidate.directWs(2, false, true, "149.154.167.40"),
                "149.154.167.40", 73, "stable");

        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(state);

        assertEquals(1, targets.size());
        assertEquals("/apiws_test", targets.get(0).path());
        assertEquals(true, targets.get(0).test());
    }

    @Test
    public void workerPingUsesActuallyVerifiedFallbackDomain() {
        RouteState state = RouteState.active(
                RouteCandidate.worker(2, false, "worker-one.example"),
                "worker-two.example", 90, "stable");

        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(
                state, VpsRelayConfig.disabled(), dcRules());

        assertEquals(1, targets.size());
        assertEquals("worker-two.example", targets.get(0).host());
        assertEquals("worker-two.example", targets.get(0).sni());
    }

    private Map<Integer, String> dcRules() {
        LinkedHashMap<Integer, String> rules = new LinkedHashMap<>();
        rules.put(2, "149.154.167.220");
        rules.put(4, "149.154.167.220");
        return rules;
    }
}
