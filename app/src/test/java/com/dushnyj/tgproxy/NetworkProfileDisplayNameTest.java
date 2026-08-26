package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class NetworkProfileDisplayNameTest {
    @Test
    public void usesReadableDefaultNamesForKnownProfiles() {
        assertEquals("T2 BLACK", NetworkProfile.mobile("25020", "T2 BLACK").defaultDisplayName());
        assertEquals("mobile:mccmnc:25020", NetworkProfile.mobile("25020", "T2 BLACK").key());
        assertEquals("Wi-Fi (имя скрыто)", NetworkProfile.wifi("default_wifi").defaultDisplayName());
        assertEquals("wifi:hidden", NetworkProfile.wifi("default_wifi").key());
        assertEquals("wifi:ssid:home_wifi", NetworkProfile.wifi("home_wifi", "Home Wi-Fi").key());
        assertEquals("Home Wi-Fi", NetworkProfile.wifi("home_wifi", "Home Wi-Fi").defaultDisplayName());
    }

    @Test
    public void opaqueWifiUsesNonSsidKeyAndCanBeDistinguished() {
        NetworkProfile first = NetworkProfile.opaqueWifi("opaque_a1b2c3d4");
        NetworkProfile second = NetworkProfile.opaqueWifi("opaque_ffeeddcc");

        assertEquals("wifi:opaque:a1b2c3d4", first.key());
        assertEquals("Wi-Fi • A1B2", first.defaultDisplayName());
        assertFalse(first.key().equals(second.key()));
    }
}
