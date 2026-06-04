package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class FlowsealConnectivityTest {
    @Test
    public void cfProxyTestUsesSameSixDcsAsFlowsealDesktop() {
        List<FlowsealConnectivity.Probe> cases =
                FlowsealConnectivity.cfProxyCases("example.com");

        assertEquals(6, cases.size());
        assertEquals("kws1.example.com", cases.get(0).connectHost);
        assertEquals("kws203.example.com", cases.get(5).connectHost);
        assertEquals("/apiws", cases.get(5).path);
    }

    @Test
    public void workerTestUsesFlowsealDstMappingForDc203() {
        List<FlowsealConnectivity.Probe> cases =
                FlowsealConnectivity.workerCases("worker.example");

        assertEquals(6, cases.size());
        assertEquals("worker.example", cases.get(5).connectHost);
        assertEquals("/apiws?dst=91.105.192.100&dc=203&media=0", cases.get(5).path);
    }
}
