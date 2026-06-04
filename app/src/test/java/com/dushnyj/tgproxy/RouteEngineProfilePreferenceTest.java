package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class RouteEngineProfilePreferenceTest {
    @Test
    public void manualCloudflarePriorityChangesCandidateOrderForProfile() {
        RoutePlan plan = new RouteEngine().plan(RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.wifi("home"))
                .routePreference(RoutePreference.CLOUDFLARE_FIRST)
                .cfMode(MtProtoProxyEngine.CF_MODE_AUTO)
                .dcRedirects(dcRules())
                .publicCfDomains(Arrays.asList("pclead.co.uk"))
                .build(), 2, false, "", new LinkedHashMap<>(), 1_000L);

        assertEquals(RouteType.PUBLIC_CLOUDFLARE, plan.selected().type());
        assertEquals(Arrays.asList(RouteType.PUBLIC_CLOUDFLARE, RouteType.DIRECT_WS),
                plan.routeTypes());
    }

    @Test
    public void manualDirectPriorityStillHonorsCooldownAndDiagnostics() {
        RouteCandidate direct = RouteCandidate.directWs(2, false, "149.154.167.220");
        Map<String, RouteStats> stats = new LinkedHashMap<>();
        RouteStats directStats = new RouteStats();
        directStats.recordFailure(RouteError.TIMEOUT, 10_000L);
        stats.put(direct.key(), directStats);

        RoutePlan plan = new RouteEngine().plan(RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.mobile("tele2"))
                .routePreference(RoutePreference.DIRECT_FIRST)
                .cfMode(MtProtoProxyEngine.CF_MODE_AUTO)
                .dcRedirects(dcRules())
                .publicCfDomains(Arrays.asList("pclead.co.uk"))
                .build(), 2, false, direct.key(), stats, 11_000L);

        assertFalse(plan.routeTypes().contains(RouteType.DIRECT_WS));
        assertEquals(RouteType.PUBLIC_CLOUDFLARE, plan.selected().type());
    }

    private static Map<Integer, String> dcRules() {
        LinkedHashMap<Integer, String> rules = new LinkedHashMap<>();
        rules.put(2, "149.154.167.220");
        return rules;
    }
}
