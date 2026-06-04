package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

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
}
