package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class VpsRelayImportTargetTest {
    @Test
    public void optionsStartWithAllNetworksThenCurrentNetwork() {
        NetworkProfileRecord current = NetworkProfileRecord.create(
                NetworkProfile.mobile("25001", "MTS"), 1_000L);
        NetworkProfileRecord wifi = NetworkProfileRecord.create(
                NetworkProfile.wifi("home_wifi", "Home WiFi"), 1_000L);
        LinkedHashMap<String, NetworkProfileRecord> profiles = new LinkedHashMap<>();
        profiles.put(current.key(), current);
        profiles.put(wifi.key(), wifi);

        List<VpsRelayImportTarget.Option> options =
                VpsRelayImportTarget.options(current, profiles);

        assertEquals("", options.get(0).profileKey());
        assertEquals(VpsRelayImportTarget.Kind.ALL_NETWORKS, options.get(0).kind());
        assertEquals(current.key(), options.get(1).profileKey());
        assertEquals(VpsRelayImportTarget.Kind.CURRENT_NETWORK, options.get(1).kind());
        assertEquals(wifi.key(), options.get(2).profileKey());
        assertEquals(VpsRelayImportTarget.Kind.SAVED_NETWORK, options.get(2).kind());
    }

    @Test
    public void applyingAllNetworksMakesImportedRelayAvailableEverywhere() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Relay",
                "relay.example.com", 443, true, "/apiws", "token", "");

        VpsRelayImportTarget.apply(store, relay,
                new VpsRelayImportTarget.Option(VpsRelayImportTarget.Kind.ALL_NETWORKS, "", "All"));

        assertNotNull(store.selectedRelay("mobile:mccmnc:25001"));
        assertNotNull(store.selectedRelay("wifi:ssid:home"));
    }

    @Test
    public void applyingSpecificNetworkDoesNotLeakToOtherProfiles() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Relay",
                "relay.example.com", 443, true, "/apiws", "token", "");

        VpsRelayImportTarget.apply(store, relay,
                new VpsRelayImportTarget.Option(VpsRelayImportTarget.Kind.SAVED_NETWORK,
                        "mobile:mccmnc:25001", "MTS"));

        assertNotNull(store.selectedRelay("mobile:mccmnc:25001"));
        assertEquals(null, store.selectedRelay("wifi:ssid:home"));
    }

    @Test
    public void applyingInvalidRelayDoesNotCreateEmptySavedEntry() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig relay = VpsRelayConfig.manual(false, "Draft",
                "", 443, true, "/apiws", "", "");

        assertNull(VpsRelayImportTarget.apply(store, relay,
                new VpsRelayImportTarget.Option(VpsRelayImportTarget.Kind.ALL_NETWORKS, "", "All")));
        assertEquals(0, store.relays().size());
    }
}
