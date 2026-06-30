package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VpsRelayStoreTest {
    @Test
    public void savedRelayCanBeSelectedForDifferentNetworkProfiles() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Home VPS",
                "relay.example.com", 443, true, "/apiws", "token", "");

        VpsRelayStore.Record saved = store.saveRelay(relay, "wifi:ssid:home");

        assertEquals(saved.id(), store.selectedRelayId("wifi:ssid:home"));
        assertNull(store.selectedRelay("mobile:mccmnc:25020"));

        store.bindProfile("mobile:mccmnc:25020", saved.id());
        VpsRelayConfig mobileRelay = store.selectedRelay("mobile:mccmnc:25020");

        assertEquals("relay.example.com", mobileRelay.host());
        assertEquals("mobile:mccmnc:25020", mobileRelay.profileKey());
        assertTrue(mobileRelay.isAllowedForProfile("mobile:mccmnc:25020"));
        assertFalse(mobileRelay.isAllowedForProfile("wifi:ssid:home"));
    }

    @Test
    public void savingSameEndpointUpdatesExistingRelayInsteadOfDuplicating() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig first = VpsRelayConfig.manual(true, "Relay",
                "relay.example.com", 443, true, "/apiws", "old", "");
        VpsRelayConfig updated = VpsRelayConfig.manual(true, "Relay new",
                "https://relay.example.com/apiws", 443, true, "/apiws", "new", "");

        store.saveRelay(first, "wifi:ssid:home");
        VpsRelayStore.Record record = store.saveRelay(updated, "wifi:ssid:work");

        List<VpsRelayStore.Record> relays = store.relays();
        assertEquals(1, relays.size());
        assertEquals(record.id(), relays.get(0).id());
        assertEquals("Relay new", relays.get(0).config().name());
        assertEquals(record.id(), store.selectedRelayId("wifi:ssid:work"));
    }

    @Test
    public void emptyRelayIdClearsSelectedProfileBinding() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Relay",
                "relay.example.com", 18080, false, "/apiws", "token", "");
        VpsRelayStore.Record record = store.saveRelay(relay, "wifi:ssid:home");

        assertEquals(record.id(), store.selectedRelayId("wifi:ssid:home"));

        store.bindProfile("wifi:ssid:home", "");

        assertEquals(null, store.selectedRelay("wifi:ssid:home"));
        assertEquals(1, store.relays().size());
    }

    @Test
    public void deletingRelayRemovesEveryProfileBinding() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Relay",
                "relay.example.com", 443, true, "/apiws", "token", "");
        VpsRelayStore.Record record = store.saveRelay(relay, "wifi:ssid:home");
        store.bindProfile("mobile:mccmnc:25001", record.id());

        assertTrue(store.deleteRelay(record.id()));

        assertEquals(0, store.relays().size());
        assertNull(store.selectedRelay("wifi:ssid:home"));
        assertNull(store.selectedRelay("mobile:mccmnc:25001"));
    }

    @Test
    public void disabledDraftIsNotSavedAsRelayProfile() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig draft = VpsRelayConfig.manual(false, "Draft",
                "", 443, true, "/apiws", "", "");

        assertNull(store.saveUsableRelay(draft, "wifi:ssid:home"));

        assertEquals(0, store.relays().size());
        assertNull(store.selectedRelay("wifi:ssid:home"));
    }
}

