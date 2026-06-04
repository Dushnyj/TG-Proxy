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
    }
}

