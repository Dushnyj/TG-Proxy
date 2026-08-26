package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetworkProfileIdentifierOpaqueWifiTest {
    @Test
    public void hiddenWifiIdentityIsStableAndDoesNotExposeNetworkSignals() {
        String first = NetworkProfileIdentifier.opaqueWifiId(
                "install-salt", "aa:bb:cc:dd:ee:ff", -1, 0);
        String again = NetworkProfileIdentifier.opaqueWifiId(
                "install-salt", "AA:BB:CC:DD:EE:FF", -1, 0);

        assertEquals(first, again);
        assertTrue(first.startsWith("opaque_"));
        assertFalse(first.contains("aa:bb"));
    }

    @Test
    public void bssidTakesPriorityAndSeparatesAccessPoints() {
        String first = NetworkProfileIdentifier.opaqueWifiId(
                "install-salt", "aa:bb:cc:dd:ee:ff", 7, 123);
        String differentRadio = NetworkProfileIdentifier.opaqueWifiId(
                "install-salt", "11:22:33:44:55:66", 7, 456);

        assertFalse(first.equals(differentRadio));
    }

    @Test
    public void redactedSignalsUseStableInstallScopedIdentityNotAttachmentOrGateway() {
        String first = NetworkProfileIdentifier.opaqueWifiId(
                "install-salt", "02:00:00:00:00:00", -1, "network-41");
        String second = NetworkProfileIdentifier.opaqueWifiId(
                "install-salt", "02:00:00:00:00:00", -1, "network-42");

        assertTrue(first.startsWith("opaque_"));
        assertEquals(first, second);
        assertEquals(first, NetworkProfileIdentifier.opaqueWifiId(
                "install-salt", "02:00:00:00:00:00", -1, 0x0101A8C0));
    }
}
