package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class FlowsealConnectivityTest {
    @Test
    public void cfProxyTestUsesOnlyExplicitTelegramWebSocketDcs() {
        List<FlowsealConnectivity.Probe> cases =
                FlowsealConnectivity.cfProxyCases("example.com");

        assertEquals(5, cases.size());
        assertEquals("kws1.example.com", cases.get(0).connectHost);
        assertEquals("kws5.example.com", cases.get(4).connectHost);
        assertEquals("kws5.example.com", cases.get(4).mediaConnectHost);
        assertEquals("/apiws", cases.get(4).path);
    }

    @Test
    public void workerTestUsesFlowsealDstMappingForDc203() {
        List<FlowsealConnectivity.Probe> cases =
                FlowsealConnectivity.workerCases("worker.example");

        assertEquals(6, cases.size());
        assertEquals("worker.example", cases.get(5).connectHost);
        assertEquals("/apiws?dst=91.105.192.100&dc=203&media=0", cases.get(5).path);
        assertEquals("/apiws?dst=91.105.192.100&dc=203&media=1", cases.get(5).mediaPath);
    }
}
