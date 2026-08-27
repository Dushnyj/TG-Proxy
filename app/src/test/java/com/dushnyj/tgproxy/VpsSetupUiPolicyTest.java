package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class VpsSetupUiPolicyTest {
    @Test
    public void ipHostUsesTlsEndpointWithoutPretendingToBeDomain() {
        assertFalse(VpsSetupUiPolicy.useTlsDomain("203.0.113.10", true));
        assertTrue(VpsSetupUiPolicy.useTlsEndpoint("203.0.113.10", true));
        assertEquals(443, VpsSetupUiPolicy.effectiveRelayPort("203.0.113.10", 443, true));
        assertTrue(VpsSetupUiPolicy.initialTlsChecked("203.0.113.10", true, 443));
    }

    @Test
    public void domainHostCanUseTlsDomainMode() {
        assertTrue(VpsSetupUiPolicy.useTlsDomain("relay.example.com", true));
        assertEquals(443, VpsSetupUiPolicy.effectiveRelayPort("relay.example.com", 18080, true));
        assertTrue(VpsSetupUiPolicy.initialTlsChecked("relay.example.com", true, 443));
    }

    @Test
    public void emptyHostKeepsTlsCheckedForDomainDiscovery() {
        assertFalse(VpsSetupUiPolicy.useTlsDomain("", true));
        assertTrue(VpsSetupUiPolicy.initialTlsChecked("", true, 443));
    }
}

