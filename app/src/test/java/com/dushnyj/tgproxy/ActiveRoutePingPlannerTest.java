package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.List;

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
    public void cloudflareMarkerWithoutActiveDomainIsNotUsedAsHost() {
        RouteState state = RouteState.active(
                RouteCandidate.publicCloudflare(2, "public-cf"),
                "", -1, "unknown");

        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(state);

        assertEquals(0, targets.size());
    }
}
