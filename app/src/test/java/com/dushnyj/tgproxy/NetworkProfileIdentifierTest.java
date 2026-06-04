package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NetworkProfileIdentifierTest {
    @Test
    public void normalizesHumanNetworkNamesIntoStableProfileIds() {
        assertEquals("home_wifi", NetworkProfileIdentifier.normalizeId("Home Wi-Fi"));
        assertEquals("tele2_russia", NetworkProfileIdentifier.normalizeId("Tele2 Russia"));
        assertEquals("mgts_5g", NetworkProfileIdentifier.normalizeId("\"MGTS 5G\""));
    }

    @Test
    public void unknownNamesBecomeEmptySoCallerCanFallback() {
        assertEquals("", NetworkProfileIdentifier.normalizeId("   "));
        assertEquals("", NetworkProfileIdentifier.normalizeId("!!!"));
    }

    @Test
    public void preservesSystemMobileOperatorNamesWhenNumericCodeIsUnavailable() {
        assertEquals("t2_black", NetworkProfileIdentifier.normalizeMobileId("T2 BLACK"));
        assertEquals("tele2_russia", NetworkProfileIdentifier.normalizeMobileId("Tele2 Russia"));
        assertEquals("mts_rus", NetworkProfileIdentifier.normalizeMobileId("MTS RUS"));
        assertEquals("", NetworkProfileIdentifier.normalizeMobileId("!!!"));
    }

    @Test
    public void activeDataSubscriptionOperatorWinsOverDefaultSimOperator() {
        NetworkProfile profile = NetworkProfileIdentifier.mobileProfileFromSignals(
                "25001", "MTS RUS",
                "25020", "T2 BLACK");

        assertEquals("mobile:mccmnc:25001", profile.key());
        assertEquals("MTS RUS", profile.defaultDisplayName());
    }

    @Test
    public void activeDataSubscriptionNameWinsEvenWhenNumericCodeIsHidden() {
        NetworkProfile profile = NetworkProfileIdentifier.mobileProfileFromSignals(
                "", "MTS RUS",
                "25020", "T2 BLACK");

        assertEquals("mobile:name:mts_rus", profile.key());
        assertEquals("MTS RUS", profile.defaultDisplayName());
    }
}
