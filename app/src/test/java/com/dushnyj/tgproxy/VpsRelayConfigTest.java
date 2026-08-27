package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsRelayConfigTest {
    @Test
    public void normalizesManualRelayFieldsAndMasksToken() {
        VpsRelayConfig config = VpsRelayConfig.manual(
                true,
                " Work Relay ",
                " Relay.Example.Com ",
                8443,
                true,
                "apiws",
                "tgpr_abcdef123456",
                "wifi:ssid:work");

        assertTrue(config.isEnabled());
        assertTrue(config.isUsable());
        assertEquals("Work Relay", config.name());
        assertEquals("relay.example.com", config.host());
        assertEquals(8443, config.port());
        assertEquals("/apiws", config.path());
        assertEquals("https://relay.example.com:8443", config.baseUrl());
        assertEquals("tgpr_****_3456", config.maskedToken());
        assertTrue(config.isAllowedForProfile("wifi:ssid:work"));
        assertFalse(config.isAllowedForProfile("mobile:mccmnc:25020"));
    }

    @Test
    public void disabledRelayCanKeepIncompleteDraftWithoutParticipatingInRoutes() {
        VpsRelayConfig config = VpsRelayConfig.manual(
                false, "", "", 0, true, "", "", "");

        assertFalse(config.isEnabled());
        assertFalse(config.isUsable());
        assertFalse(config.isAllowedForProfile("wifi:ssid:work"));
    }

    @Test
    public void enabledRelayRequiresHostPortPathAndToken() {
        assertFalse(VpsRelayConfig.manual(true, "Relay", "", 443,
                true, "/apiws", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 70000,
                true, "/apiws", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws", "", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws\r\nInjected: yes", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws", "token\r\nInjected: yes", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws", "token with space", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws", "токен", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws?dst=unexpected", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws%", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws%XZ", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws/", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/nested//apiws", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/nested/../apiws", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/healthz", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/admin", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws/admin/v1/tokens", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws/connect", "token", "").isUsable());
        assertTrue(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/private-relay/v1", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "relay.example.com", 443,
                true, "/apiws", repeat('t', 513), "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "bad_host.example.com", 443,
                true, "/apiws", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "999.1.2.3", 443,
                true, "/apiws", "token", "").isUsable());
        assertFalse(VpsRelayConfig.manual(true, "Relay", "fe80::1%wlan0", 443,
                true, "/apiws", "token", "").isUsable());
    }

    @Test
    public void acceptsBracketedIpv6AndPastedAuthorityWithoutDuplicatingPort() {
        VpsRelayConfig ipv6 = VpsRelayConfig.manual(true, "IPv6", "https://[2001:db8::1]:8443/apiws",
                8443, true, "/apiws", "token", "");
        VpsRelayConfig domain = VpsRelayConfig.manual(true, "Domain", "relay.example.com:8443",
                8443, true, "/apiws", "token", "");

        assertEquals("2001:db8::1", ipv6.host());
        assertEquals("https://[2001:db8::1]:8443", ipv6.baseUrl());
        assertEquals("relay.example.com", domain.host());
        assertEquals("https://relay.example.com:8443", domain.baseUrl());
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}

