package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BootstrapPingPlannerTest {
    @Test
    public void buildsTelegramProbeBeforeAnyTelegramClientSessionExists() {
        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .routePreference(RoutePreference.RELAY_FIRST)
                .vpsRelays(Arrays.asList(
                        relay("Relay 1", "one.example.com", "one"),
                        relay("Relay 2", "two.example.com", "two")))
                .dcRedirects(MtProtoConfig.relayDcRules())
                .build();

        BootstrapPingPlanner.Plan plan = BootstrapPingPlanner.plan(
                settings, 2, MtProtoConfig.relayDcRules());

        assertFalse(plan.isEmpty());
        assertTrue(plan.identity().startsWith("bootstrap|"));
        assertEquals(2, plan.targets().stream()
                .filter(target -> target.kind() == RoutePingTarget.Kind.VPS_RELAY).count());
    }

    @Test
    public void publicCloudflareUsesRealDomainInsteadOfInternalLabel() {
        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .publicCfDomains(Collections.singletonList("cf.example.com"))
                .dcRedirects(MtProtoConfig.relayDcRules())
                .build();

        BootstrapPingPlanner.Plan plan = BootstrapPingPlanner.plan(
                settings, 2, MtProtoConfig.relayDcRules());

        assertTrue(plan.targets().stream().anyMatch(target ->
                "kws2.cf.example.com".equals(target.host())));
    }

    private static VpsRelayConfig relay(String name, String host, String token) {
        return VpsRelayConfig.manual(true, name, host, 443,
                true, "/apiws", token, "");
    }
}
