package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsRelayCapabilitiesTest {
    @Test
    public void missingCapabilitiesUseOnlyLegacyEmbeddedRoutes() {
        VpsRelayCapabilities value = VpsRelayCapabilities.unknown();

        assertTrue(value.supports(2, false));
        assertTrue(value.supports(2, true));
        assertFalse(value.supports(204, false));
        assertFalse(value.supports(4, true));
    }

    @Test
    public void staticCapabilitiesOnlyAdvertiseExactRoutes() throws Exception {
        VpsRelayCapabilities value = VpsRelayCapabilities.parse(response(false, 19));

        assertTrue(value.known());
        assertTrue(value.supports(2, false));
        assertFalse(value.supports(204, false));
        assertFalse(value.dynamicTopology());
        assertEquals(19L, value.topologyRevision());
    }

    @Test
    public void dynamicCapabilitiesRemainFutureProofAcrossStorage() throws Exception {
        VpsRelayCapabilities value = VpsRelayCapabilities.parse(response(true, 20));

        VpsRelayCapabilities restored = VpsRelayCapabilities.fromStored(value.toStored());

        assertEquals(value, restored);
        assertTrue(restored.supports(204, false));
        assertTrue(restored.supports(4, true));
    }

    @Test
    public void legacyStoredCapabilitiesRemainStatic() {
        VpsRelayCapabilities restored = VpsRelayCapabilities.fromStored("1-2;1,2,3;1,2");

        assertTrue(restored.known());
        assertFalse(restored.dynamicTopology());
        assertTrue(restored.supports(2, false));
        assertFalse(restored.supports(204, false));
    }

    @Test
    public void malformedOrOversizedResponseIsRejected() throws Exception {
        try {
            VpsRelayCapabilities.parse("{\"name\":\"tgproxy-relay\","
                    + "\"protocol\":{\"min\":3,\"max\":2},\"topology\":{}}" );
            throw new AssertionError("invalid range accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("protocol"));
        }
    }

    @Test
    public void storedCapabilitiesWithoutProductionRoutesFallBackToLegacyContract() {
        VpsRelayCapabilities restored = VpsRelayCapabilities.fromStored("1-2;1;20;;1,2");

        assertFalse(restored.known());
        assertTrue(restored.supports(2, false));
        assertFalse(restored.supports(204, false));
    }

    private static String response(boolean dynamic, long revision) {
        return "{\"name\":\"tgproxy-relay\",\"protocol\":{\"min\":1,\"max\":2},"
                + "\"topology\":{\"dynamic\":" + dynamic + ",\"revision\":" + revision
                + ",\"productionDcs\":[1,2,3,4,5],\"testDcs\":[1,2,3]}}";
    }
}
