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

    @Test
    public void sameAttachmentDoesNotDowngradeSsidToOpaqueWifi() {
        NetworkProfile selected = NetworkProfileIdentifier.stableProfile(
                NetworkProfile.wifi("mts_gpon5_1ca9", "MTS_GPON5_1CA9"),
                NetworkProfile.opaqueWifi("opaque_174df121f19ee0b28a24"),
                false);

        assertEquals("wifi:ssid:mts_gpon5_1ca9", selected.key());
    }

    @Test
    public void realAttachmentChangeDoesNotInheritPreviousSsid() {
        NetworkProfile selected = NetworkProfileIdentifier.stableProfile(
                NetworkProfile.wifi("mts_gpon5_1ca9", "MTS_GPON5_1CA9"),
                NetworkProfile.opaqueWifi("opaque_new_attachment"),
                true);

        assertEquals("wifi:opaque:new_attachment", selected.key());
    }

    @Test
    public void sameAttachmentUpgradesOpaqueWifiToAvailableSsid() {
        NetworkProfile selected = NetworkProfileIdentifier.stableProfile(
                NetworkProfile.opaqueWifi("opaque_174df121f19ee0b28a24"),
                NetworkProfile.wifi("mts_gpon5_1ca9", "MTS_GPON5_1CA9"),
                false);

        assertEquals("wifi:ssid:mts_gpon5_1ca9", selected.key());
    }

    @Test
    public void sameAttachmentIgnoresTransientMissingCapabilities() {
        NetworkProfile selected = NetworkProfileIdentifier.stableProfile(
                NetworkProfile.mobile("25001", "MTS RUS"),
                NetworkProfile.defaultProfile(),
                false);

        assertEquals("mobile:mccmnc:25001", selected.key());
    }
}
