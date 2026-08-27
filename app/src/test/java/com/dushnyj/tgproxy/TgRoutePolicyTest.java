package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TgRoutePolicyTest {

    @Test
    public void directWsOnlyAcceptsPublishedTelegramWebDcRange() {
        assertTrue(TgRoutePolicy.canUseDirectWs(2));
        assertTrue(TgRoutePolicy.canUseDirectWs(4));
        assertFalse(TgRoutePolicy.canUseDirectWs(203));
        assertFalse(TgRoutePolicy.canUseDirectWs(204));

        assertFalse(TgRoutePolicy.canUseDirectWs(0));
    }

    @Test
    public void directWsTargetsKnownReachableTelegramIp() {
        assertArrayEquals(new String[]{"149.154.167.220"}, TgRoutePolicy.targetIpsForDirectWs(2));
        assertArrayEquals(new String[]{"149.154.167.220"}, TgRoutePolicy.targetIpsForDirectWs(4));
    }

    @Test
    public void blockedTelegramDestinationsDoNotFallbackDirectlyByDefault() {
        assertFalse(TgRoutePolicy.allowDirectTelegramFallback("149.154.175.100", 443));
        assertFalse(TgRoutePolicy.allowDirectTelegramFallback("91.108.56.116", 443));
        assertTrue(TgRoutePolicy.allowDirectTelegramFallback("149.154.167.220", 443));
    }

    @Test
    public void smartSleepIsDisabledByDefaultForLargeMediaTransfers() {
        assertFalse(TgRoutePolicy.DEFAULT_SMART_SLEEP);
    }

    @Test
    public void mediaDc2KeepsDirectCandidateWhenDc2AndDc4ShareFlowsealIp() {
        Map<Integer, String> rules = MtProtoConfig.parseDcRules(
                "2:149.154.167.220\n4:149.154.167.220");

        assertTrue(TgRoutePolicy.shouldUseDirectWs(2, true, rules));
        assertTrue(TgRoutePolicy.shouldUseDirectWs(2, false, rules));
        assertTrue(TgRoutePolicy.shouldUseDirectWs(4, true, rules));
        assertEquals("149.154.167.220", rules.get(2));
    }
}
