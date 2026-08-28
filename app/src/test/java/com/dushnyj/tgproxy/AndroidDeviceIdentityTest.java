package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

public class AndroidDeviceIdentityTest {
    @Test
    public void stableIdIsDeterministicAndPseudonymous() {
        String first = AndroidDeviceIdentity.stableId("abcdef1234567890", "com.example.app");
        String second = AndroidDeviceIdentity.stableId("ABCDEF1234567890", "com.example.app");

        assertEquals(first, second);
        assertFalse(first.contains("abcdef1234567890"));
        assertEquals(37, first.length());
    }

    @Test
    public void stableIdIsNamespaceScoped() {
        assertNotEquals(
                AndroidDeviceIdentity.stableId("abcdef1234567890", "com.example.one"),
                AndroidDeviceIdentity.stableId("abcdef1234567890", "com.example.two"));
    }

    @Test
    public void knownBrokenAndroidIdsAreRejected() {
        assertEquals("", AndroidDeviceIdentity.stableId("", "com.example.app"));
        assertEquals("", AndroidDeviceIdentity.stableId("0000000000000000", "com.example.app"));
        assertEquals("", AndroidDeviceIdentity.stableId("9774d56d682e549c", "com.example.app"));
        assertEquals("", AndroidDeviceIdentity.stableId("unknown", "com.example.app"));
    }
}
