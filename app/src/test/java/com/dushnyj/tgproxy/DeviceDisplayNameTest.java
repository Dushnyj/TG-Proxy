package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DeviceDisplayNameTest {
    @Test
    public void knownXiaomiModelUsesVerifiedRetailName() {
        DeviceDisplayName.Identity value = DeviceDisplayName.resolve(
                "Xiaomi", "xiaomi", "2407FPN8EG", "John's phone");

        assertEquals("Xiaomi", value.brand);
        assertEquals("Xiaomi 14T Pro", value.marketingName);
    }

    @Test
    public void knownSamsungModelIncludesBrandAndRetailFamily() {
        DeviceDisplayName.Identity value = DeviceDisplayName.resolve(
                "samsung", "samsung", "SM-S926B", "Galaxy S24+");

        assertEquals("Samsung", value.brand);
        assertEquals("Samsung Galaxy S24+", value.marketingName);
    }

    @Test
    public void redmiRetailNameIsNotPrefixedWithXiaomiTwice() {
        DeviceDisplayName.Identity value = DeviceDisplayName.resolve(
                "Xiaomi", "xiaomi", "Redmi Note 8 Pro", "Redmi Note 8 Pro");

        assertEquals("Redmi Note 8 Pro", value.marketingName);
    }

    @Test
    public void arbitraryUserDeviceNameDoesNotReplaceHardwareModel() {
        DeviceDisplayName.Identity value = DeviceDisplayName.resolve(
                "oneplus", "oneplus", "CPH2609", "Alex's phone");

        assertEquals("OnePlus CPH2609", value.marketingName);
    }

    @Test
    public void productFamilyGetsCanonicalManufacturerPrefix() {
        DeviceDisplayName.Identity value = DeviceDisplayName.resolve(
                "Google", "google", "unknown-model", "Pixel 9 Pro");

        assertEquals("Google Pixel 9 Pro", value.marketingName);
    }
}
