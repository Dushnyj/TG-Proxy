package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class NetworkProfileStoreTest {
    @Test
    public void createsSeparateProfilesForDifferentNetworks() {
        NetworkProfileStore store = NetworkProfileStore.inMemory();
        NetworkProfile home = NetworkProfile.wifi("home_wifi");
        NetworkProfile work = NetworkProfile.wifi("work_wifi");
        NetworkProfile tele2 = NetworkProfile.mobile("tele2");

        NetworkProfileRecord homeRecord = store.ensureProfile(home, 1_000L);
        NetworkProfileRecord workRecord = store.ensureProfile(work, 2_000L);
        NetworkProfileRecord tele2Record = store.ensureProfile(tele2, 3_000L);

        assertNotEquals(homeRecord.key(), workRecord.key());
        assertNotEquals(homeRecord.key(), tele2Record.key());
        assertEquals("wifi:ssid:home_wifi", homeRecord.key());
        assertEquals("mobile:name:tele2", tele2Record.key());
        assertEquals("home_wifi", homeRecord.profile().id());
        assertEquals("work_wifi", workRecord.profile().id());
        assertEquals("tele2", tele2Record.profile().id());
    }

    @Test
    public void persistsProfileNameAndManualPriority() {
        NetworkProfileStore store = NetworkProfileStore.inMemory();
        NetworkProfile profile = NetworkProfile.mobile("tele2");
        store.ensureProfile(profile, 1_000L);

        store.renameProfile(profile.key(), "Tele2 LTE");
        store.setRoutePreference(profile.key(), RoutePreference.CLOUDFLARE_FIRST);

        NetworkProfileStore restored = NetworkProfileStore.inMemory(store.exportProfiles());
        NetworkProfileRecord record = restored.ensureProfile(profile, 2_000L);

        assertEquals("Tele2 LTE", record.displayName());
        assertEquals(RoutePreference.CLOUDFLARE_FIRST, record.routePreference());
        assertEquals(2_000L, record.lastSeenMs());
    }

    @Test
    public void persistsPerProfileRouteAvailabilityAndMigratesLegacyRowsToAll() {
        NetworkProfileStore store = NetworkProfileStore.inMemory();
        NetworkProfile profile = NetworkProfile.mobile("25020", "T2 BLACK");
        store.ensureProfile(profile, 1_000L);
        store.setRouteAvailability(profile.key(), RouteAvailability.directOnly());

        NetworkProfileRecord restored = NetworkProfileStore.inMemory(store.exportProfiles())
                .profile(profile.key());

        assertEquals(true, restored.routeAvailability().isEnabled(RouteType.DIRECT_WS));
        assertEquals(false, restored.routeAvailability().isEnabled(RouteType.VPS_RELAY));

        String legacy = "wifi%3Assid%3Ahome\tWIFI\thome\tHome\tAUTO\t1\t2\t3";
        NetworkProfileRecord migrated = NetworkProfileStore.inMemory(legacy)
                .profile("wifi:ssid:home");
        assertEquals(true, migrated.routeAvailability().isEnabled(RouteType.VPS_RELAY));
        assertEquals(true, migrated.routeAvailability().isEnabled(RouteType.PUBLIC_CLOUDFLARE));

        String corrupted = "wifi%3Assid%3Abroken\tWIFI\tbroken\tBroken\tAUTO\t1\t2\t3\t0";
        NetworkProfileRecord recovered = NetworkProfileStore.inMemory(corrupted)
                .profile("wifi:ssid:broken");
        assertEquals(true, recovered.routeAvailability().isEnabled(RouteType.DIRECT_WS));
        assertEquals(true, recovered.routeAvailability().isEnabled(RouteType.VPS_RELAY));
    }

    @Test
    public void uiLookupDoesNotRecordAnotherNetworkActivation() {
        NetworkProfileStore store = NetworkProfileStore.inMemory();
        NetworkProfile profile = NetworkProfile.wifi("home");
        NetworkProfileRecord first = store.ensureProfile(profile, 1_000L);
        String before = store.exportProfiles();

        NetworkProfileRecord lookedUp = store.profileOrCreate(profile, 2_000L);

        assertEquals(first.lastSeenMs(), lookedUp.lastSeenMs());
        assertEquals(first.seenCount(), lookedUp.seenCount());
        assertEquals(before, store.exportProfiles());
    }

    @Test
    public void uiLookupCreatesMissingProfileOnce() {
        NetworkProfileStore store = NetworkProfileStore.inMemory();
        NetworkProfile profile = NetworkProfile.mobile("25020", "T2 BLACK");

        NetworkProfileRecord created = store.profileOrCreate(profile, 3_000L);

        assertEquals(profile.key(), created.key());
        assertEquals(3_000L, created.lastSeenMs());
        assertEquals(1, created.seenCount());
        assertEquals(created.key(), store.profile(profile.key()).key());
    }

    @Test
    public void keepsRouteStatsPerProfile() {
        NetworkProfileStore store = NetworkProfileStore.inMemory();
        NetworkProfile wifi = NetworkProfile.wifi("home");
        NetworkProfile mobile = NetworkProfile.mobile("tele2");

        RouteStats wifiDirect = new RouteStats();
        wifiDirect.recordSuccess(1_000L, 80);
        store.saveRouteStats(wifi, "direct:dc2", wifiDirect);

        RouteStats mobileDirect = new RouteStats();
        mobileDirect.recordFailure(RouteError.TIMEOUT, 1_000L);
        store.saveRouteStats(mobile, "direct:dc2", mobileDirect);

        assertEquals(1, store.routeStats(wifi, 2_000L).get("direct:dc2").successCount());
        assertEquals(0, store.routeStats(wifi, 2_000L).get("direct:dc2").failureCount(RouteError.TIMEOUT));
        assertEquals(0, store.routeStats(mobile, 2_000L).get("direct:dc2").successCount());
        assertEquals(1, store.routeStats(mobile, 2_000L).get("direct:dc2").failureCount(RouteError.TIMEOUT));
    }

    @Test
    public void dropsLegacyGatewayWifiProfilesFromVisibleList() {
        String legacy = "wifi%3Agw_192_168_1_1\tWIFI\tgw_192_168_1_1\tWi-Fi+192.168.1.1\tAUTO\t1\t1\t1\n"
                + "wifi%3Adefault_wifi\tWIFI\tdefault_wifi\tWi-Fi\tAUTO\t2\t2\t1";

        NetworkProfileStore store = NetworkProfileStore.inMemory(legacy);

        assertEquals(null, store.profile("wifi:gw_192_168_1_1"));
        assertEquals(null, store.profile("wifi:default_wifi"));
        assertEquals("Wi-Fi (имя недоступно)", store.profile("wifi:hidden").displayName());
    }

    @Test
    public void mergesLegacyMobileAliasesIntoCurrentSystemOperatorProfile() {
        String legacy = "mobile%3Atele2\tMOBILE\ttele2\tTele2\tCLOUDFLARE_FIRST\t1\t1\t3\n"
                + "mobile%3At2_black\tMOBILE\tt2_black\tMobile+t2_black\tDIRECT_FIRST\t2\t2\t1";
        NetworkProfileStore store = NetworkProfileStore.inMemory(legacy);

        NetworkProfileRecord current = store.ensureProfile(NetworkProfile.mobile("25020", "T2 BLACK"), 3_000L);

        assertEquals("mobile:mccmnc:25020", current.key());
        assertEquals("T2 BLACK", current.displayName());
        assertEquals(RoutePreference.CLOUDFLARE_FIRST, current.routePreference());
        assertEquals(null, store.profile("mobile:name:tele2"));
        assertEquals(null, store.profile("mobile:name:t2_black"));
        assertEquals(5, current.seenCount());
    }

    @Test
    public void cleansGatewayNamesAfterStableWifiProfileExists() {
        String legacy = "mobile%3Aname%3Atele2\tMOBILE\ttele2\tWi-Fi+192.168.1.\tAUTO\t1\t2\t3\n"
                + "wifi%3Ahidden\tWIFI\tdefault_wifi\tWi-Fi+192.168.1.\tAUTO\t2\t3\t4\n"
                + "wifi%3Assid%3Ahome_5g\tWIFI\thome_5g\tHome_5G\tAUTO\t3\t4\t5";

        NetworkProfileStore store = NetworkProfileStore.inMemory(legacy);

        assertEquals("Tele2", store.profile("mobile:name:tele2").displayName());
        assertEquals(null, store.profile("wifi:hidden"));
        assertEquals("Home_5G", store.profile("wifi:ssid:home_5g").displayName());
    }

    @Test
    public void dropsLegacyAutoWifiProfileWhenStableWifiProfileExists() {
        String legacy = "wifi%3Ahidden\tWIFI\thidden\tWiFi -- авто\tAUTO\t1\t2\t1\n"
                + "wifi%3Assid%3Ahome_5g\tWIFI\thome_5g\tHome_5G\tAUTO\t3\t4\t5";

        NetworkProfileStore store = NetworkProfileStore.inMemory(legacy);

        assertEquals(null, store.profile("wifi:hidden"));
        assertEquals("Home_5G", store.profile("wifi:ssid:home_5g").displayName());
    }

    @Test
    public void dropsGenericHiddenWifiProfileWhenStableWifiProfileExists() {
        String legacy = "wifi%3Ahidden\tWIFI\thidden\tWiFi\tAUTO\t1\t2\t1\n"
                + "wifi%3Assid%3Ahome_5g\tWIFI\thome_5g\tHome_5G\tAUTO\t3\t4\t5";

        NetworkProfileStore store = NetworkProfileStore.inMemory(legacy);

        assertEquals(null, store.profile("wifi:hidden"));
        assertEquals("Home_5G", store.profile("wifi:ssid:home_5g").displayName());
    }

    @Test
    public void deletesSelectedProfile() {
        NetworkProfileStore store = NetworkProfileStore.inMemory();
        NetworkProfileRecord home = store.ensureProfile(NetworkProfile.wifi("home", "Home"), 1_000L);
        store.ensureProfile(NetworkProfile.mobile("25020", "T2 BLACK"), 2_000L);

        assertEquals(true, store.deleteProfile(home.key()));

        assertEquals(null, store.profile(home.key()));
        assertEquals(1, store.profilesSnapshot().size());
    }

    @Test
    public void replacesLegacyOpaqueHashLabelWithHonestUnavailableName() {
        String legacy = "wifi%3Aopaque%3A174dbeef\tWIFI\topaque_174dbeef\t"
                + "Wi-Fi+%E2%80%A2+174D\tAUTO\t1\t2\t1";

        NetworkProfileStore store = NetworkProfileStore.inMemory(legacy);

        assertEquals("Wi-Fi (имя недоступно)",
                store.profile("wifi:opaque:174dbeef").displayName());
    }

    @Test
    public void recognizesLegacyHashLabelWithActiveSuffix() {
        assertEquals(true, NetworkProfileRecord.isLegacyGeneratedWifiLabel(
                "Wi-FI * 174D * активен"));
        assertEquals(false, NetworkProfileRecord.isLegacyGeneratedWifiLabel(
                "Wi-Fi • HOME"));
    }

    @Test
    public void opaqueWifiDoesNotAbsorbAmbiguousLegacyHiddenPreference() {
        String legacy = "wifi%3Ahidden\tWIFI\thidden\tHome\tRELAY_FIRST\t1\t2\t3";
        NetworkProfileStore store = NetworkProfileStore.inMemory(legacy);
        NetworkProfile opaque = NetworkProfile.opaqueWifi("opaque_a1b2c3d4");

        NetworkProfileRecord current = store.ensureProfile(opaque, 3_000L);

        assertEquals("wifi:opaque:a1b2c3d4", current.key());
        assertEquals(RoutePreference.AUTO, current.routePreference());
        assertEquals(RoutePreference.RELAY_FIRST,
                store.profile("wifi:hidden").routePreference());
    }
}
