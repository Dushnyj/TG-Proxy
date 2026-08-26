package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MtProtoConfigTest {
    @Test
    public void generatedSecretIsThirtyTwoLowercaseHexChars() {
        String secret = MtProtoConfig.generateSecretHex();

        assertEquals(32, secret.length());
        assertTrue(secret.matches("[0-9a-f]{32}"));
    }

    @Test
    public void telegramLinkUsesMtProtoDdSecret() {
        String link = MtProtoConfig.telegramProxyLink("127.0.0.1", 1443,
                "0123456789abcdef0123456789abcdef");

        assertEquals(
                "tg://proxy?server=127.0.0.1&port=1443&secret=dd0123456789abcdef0123456789abcdef",
                link);
    }

    @Test
    public void validatesRawAndTelegramDdSecretsWithoutGeneratingFallbacks() {
        String raw = "0123456789abcdef0123456789abcdef";

        assertTrue(MtProtoConfig.isValidSecretHex(raw));
        assertTrue(MtProtoConfig.isValidSecretHex("dd" + raw));
        assertEquals(false, MtProtoConfig.isValidSecretHex(""));
        assertEquals(false, MtProtoConfig.isValidSecretHex("not-a-secret"));
    }

    @Test
    public void parsesFlowsealDcRulesAndKeepsOrder() {
        Map<Integer, String> rules = MtProtoConfig.parseDcRules(
                "2:149.154.167.220\n\n4:149.154.167.220");

        assertEquals(2, rules.size());
        assertEquals("149.154.167.220", rules.get(2));
        assertEquals("149.154.167.220", rules.get(4));
        assertEquals("2:149.154.167.220\n4:149.154.167.220",
                MtProtoConfig.formatDcRules(rules));
    }

    @Test
    public void acceptsFlowsealMediaDc203Rule() {
        Map<Integer, String> rules = MtProtoConfig.parseDcRules("203:91.105.192.100");

        assertEquals("91.105.192.100", rules.get(203));
    }

    @Test
    public void acceptsFuturePositiveDcRule() {
        Map<Integer, String> rules = MtProtoConfig.parseUserDcRules("204:203.0.113.10");

        assertEquals("203.0.113.10", rules.get(204));
    }

    @Test
    public void validatesSingleUserDcRule() {
        Map<Integer, String> rules = MtProtoConfig.parseUserDcRules("4:149.154.167.220");

        assertEquals(1, rules.size());
        assertEquals("149.154.167.220", rules.get(4));
    }

    @Test
    public void validatesMultipleUserDcRules() {
        Map<Integer, String> rules = MtProtoConfig.parseUserDcRules(
                "2:149.154.167.220\n4:149.154.167.220");

        assertEquals(2, rules.size());
        assertEquals("149.154.167.220", rules.get(2));
        assertEquals("149.154.167.220", rules.get(4));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedDcRules() {
        MtProtoConfig.parseDcRules("bad-rule");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyUserDcRules() {
        MtProtoConfig.parseUserDcRules("  \n ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidUserDcIp() {
        MtProtoConfig.parseUserDcRules("2:999.154.167.220");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroDcRule() {
        MtProtoConfig.parseUserDcRules("0:149.154.167.220");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateUserDcRules() {
        MtProtoConfig.parseUserDcRules(
                "2:149.154.167.220\n2:149.154.167.221");
    }
}
