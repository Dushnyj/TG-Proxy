package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsEndpointPolicyTest {
    @Test
    public void normalizesPublicEndpointsWithoutResolvingDomains() {
        assertEquals("relay.example.com", VpsEndpointPolicy.normalizeHost(
                "HTTPS://Relay.Example.com:443/apiws"));
        assertEquals("2001:db8::1", VpsEndpointPolicy.normalizeHost(
                "[2001:db8::1]:443"));
        assertTrue(VpsEndpointPolicy.isIpLiteral("203.0.113.10"));
        assertFalse(VpsEndpointPolicy.isIpLiteral("999.243.116.73"));
        assertTrue(VpsEndpointPolicy.isDomain("пример.рф"));
    }

    @Test
    public void acceptsOnlyValidDuckDnsHostNames() {
        assertTrue(VpsEndpointPolicy.isDuckDnsDomain("My-Relay.duckdns.org"));
        assertEquals("my-relay", VpsEndpointPolicy.duckDnsSubdomain(
                "my-relay.duckdns.org"));
        assertFalse(VpsEndpointPolicy.isDuckDnsDomain("duckdns.org"));
        assertFalse(VpsEndpointPolicy.isDuckDnsDomain("relay.duckdns.org.example.com"));
    }
}
