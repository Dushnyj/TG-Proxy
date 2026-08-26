package com.dushnyj.tgproxy;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CfProxyDomainStateTest {

    @Test
    public void successfulDomainIsTriedFirst() {
        CfProxyDomainState state = new CfProxyDomainState(45_000);
        state.markSuccess("fast.example", 1_000);

        assertEquals(Arrays.asList("fast.example", "slow.example", "other.example"),
                state.orderedDomains(Arrays.asList(
                        "slow.example",
                        "fast.example",
                        "other.example"), 2_000));
    }

    @Test
    public void activeDomainIsStoredSeparatelyForWifiAndMobile() {
        CfProxyDomainState state = new CfProxyDomainState(45_000);
        state.markSuccess("wifi.example", CfProxyDomainState.PROFILE_WIFI, 1_000);
        state.markSuccess("mobile.example", CfProxyDomainState.PROFILE_MOBILE, 1_000);

        assertEquals(Arrays.asList("wifi.example", "mobile.example"),
                state.orderedDomains(Arrays.asList(
                        "mobile.example",
                        "wifi.example"), CfProxyDomainState.PROFILE_WIFI, 2_000));
        assertEquals(Arrays.asList("mobile.example", "wifi.example"),
                state.orderedDomains(Arrays.asList(
                        "wifi.example",
                        "mobile.example"), CfProxyDomainState.PROFILE_MOBILE, 2_000));
    }

    @Test
    public void tooManyRequestsDomainIsSkippedUntilCooldownExpires() {
        CfProxyDomainState state = new CfProxyDomainState(45_000);
        state.markTooManyRequests("busy.example", 10_000);

        assertEquals(Arrays.asList("fast.example"),
                state.orderedDomains(Arrays.asList("busy.example", "fast.example"), 12_000));

        assertEquals(Arrays.asList("busy.example", "fast.example"),
                state.orderedDomains(Arrays.asList("busy.example", "fast.example"), 60_000));
    }

    @Test
    public void detectsWebSocket429Errors() {
        assertTrue(CfProxyDomainState.isTooManyRequests(
                new IOException("WS handshake failed: 429")));
        assertFalse(CfProxyDomainState.isTooManyRequests(
                new IOException("WS handshake failed: 404")));
    }

    @Test
    public void rateLimitOnOneOperatorDoesNotDisableDomainOnAnotherNetwork() {
        CfProxyDomainState state = new CfProxyDomainState(45_000);
        state.markTooManyRequests("busy.example", "mobile:mccmnc:25020", 10_000);

        assertEquals(Arrays.asList("fallback.example"),
                state.orderedDomains(Arrays.asList("busy.example", "fallback.example"),
                        "mobile:mccmnc:25020", 12_000));
        assertEquals(Arrays.asList("busy.example", "fallback.example"),
                state.orderedDomains(Arrays.asList("busy.example", "fallback.example"),
                        "wifi:ssid:home", 12_000));
    }
}
