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
    public void autoModeStillUsesWorkerBeforeDirectAndCf() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220");
        engine.setCfWorkerDomains(Collections.singletonList("worker.example"));
        engine.setCfProxyMode(MtProtoProxyEngine.CF_MODE_AUTO);

        assertEquals(Arrays.asList(
                MtProtoProxyEngine.ROUTE_WORKER,
                MtProtoProxyEngine.ROUTE_DIRECT,
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
    public void cfWarmupKeysAreSeparatedByNetworkProfile() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setDcRules("2:149.154.167.220\n4:149.154.167.220");

        engine.setMobileNetwork(false);
        assertEquals(Arrays.asList("wifi:2", "wifi:4"), engine.cfWarmupKeys());

        engine.setMobileNetwork(true);
        assertEquals(Arrays.asList("mobile:2", "mobile:4"), engine.cfWarmupKeys());
    }
}
