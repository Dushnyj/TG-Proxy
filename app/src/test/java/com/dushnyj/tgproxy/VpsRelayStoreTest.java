package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VpsRelayStoreTest {
    @Test
    public void globalRelayRemainsGlobalWhenNetworkProfileChanges() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Global VPS",
                "relay.example.com", 443, true, "/apiws", "token", "");

        store.saveRelay(relay, "");

        VpsRelayConfig wifi = store.selectedRelay("wifi:ssid:home");
        VpsRelayConfig mobile = store.selectedRelay("mobile:mccmnc:25001");
        assertEquals("", wifi.profileKey());
        assertEquals("", mobile.profileKey());
        assertTrue(wifi.isAllowedForProfile("wifi:ssid:home"));
        assertTrue(wifi.isAllowedForProfile("mobile:mccmnc:25001"));
        assertTrue(mobile.isAllowedForProfile("wifi:ssid:other"));
    }

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
    public void sameEndpointKeepsIndependentCredentialsPerProfile() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig first = VpsRelayConfig.manual(true, "Relay",
                "relay.example.com", 443, true, "/apiws", "old", "");
        VpsRelayConfig updated = VpsRelayConfig.manual(true, "Relay new",
                "https://relay.example.com/apiws", 443, true, "/apiws", "new", "");

        store.saveRelay(first, "wifi:ssid:home");
        VpsRelayStore.Record record = store.saveRelay(updated, "wifi:ssid:work");

        List<VpsRelayStore.Record> relays = store.relays();
        assertEquals(2, relays.size());
        assertEquals("new", store.relay(record.id()).config().token());
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

    @Test
    public void disablingPrimaryPromotesEnabledBackup() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayStore.Record primary = store.saveRelay(VpsRelayConfig.manual(true, "Primary",
                "one.example.com", 443, true, "/apiws", "one", ""), "wifi:home");
        VpsRelayStore.Record backup = store.saveRelay(VpsRelayConfig.manual(true, "Backup",
                "two.example.com", 443, true, "/apiws", "two", ""), "wifi:work");
        store.bindProfile("wifi:home", primary.id());
        assertTrue(store.setRelayEnabledForProfile("wifi:home", backup.id(), true));

        assertTrue(store.setRelayEnabledForProfile("wifi:home", primary.id(), false));

        assertEquals(backup.id(), store.selectedRelayId("wifi:home"));
        assertEquals(1, store.relayPool("wifi:home").size());
        assertEquals("Backup", store.relayPool("wifi:home").get(0).name());
    }

    @Test
    public void knownOwnerTokenCanBeActivatedAsPrimary() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Phone",
                "relay.example.com", 443, true, "/apiws", "secret", "");

        VpsRelayStore.Record saved = store.activateConnection(relay, "wifi:home");

        assertEquals(saved.id(), store.selectedRelayId("wifi:home"));
        assertEquals(saved.id(), store.relayIdFor(relay));
        assertTrue(store.selectedRelay("wifi:home").isUsable());
    }

    @Test
    public void revokedCredentialIsRemovedAndBindingFallsBack() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig revoked = VpsRelayConfig.manual(true, "Revoked",
                "relay.example.com", 443, true, "/apiws", "revoked", "");
        VpsRelayStore.Record revokedRecord = store.saveRelay(revoked, "wifi:home");
        VpsRelayStore.Record backup = store.saveRelay(VpsRelayConfig.manual(true, "Backup",
                "backup.example.com", 443, true, "/apiws", "backup", ""), "wifi:work");
        store.bindProfile("wifi:home", revokedRecord.id());
        assertTrue(store.setRelayEnabledForProfile("wifi:home", backup.id(), true));

        assertTrue(store.deleteConnection(revoked, "revoked"));

        assertNull(store.relay(revokedRecord.id()));
        assertEquals(backup.id(), store.selectedRelayId("wifi:home"));
    }

    @Test
    public void relayAndBindingArePersistedInOneAtomicStoreOperation() {
        RecordingStore persistence = new RecordingStore();
        VpsRelayStore store = new VpsRelayStore(persistence);
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Relay",
                "relay.example.com", 443, true, "/apiws", "token", "");

        store.saveRelay(relay, "wifi:ssid:home");

        assertEquals(1, persistence.batchWrites);
        assertEquals(0, persistence.singleWrites);
        assertTrue(persistence.values.containsKey(VpsRelayStore.KEY_RELAYS));
        assertTrue(persistence.values.containsKey(VpsRelayStore.KEY_PROFILE_BINDINGS));
    }

    @Test
    public void tlsAndPlaintextEndpointsCannotCollideInStableIds() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayStore.Record tls = store.saveRelay(VpsRelayConfig.manual(true, "TLS",
                "relay.example.com", 443, true, "/apiws", "one", ""), "wifi:ssid:home");
        VpsRelayStore.Record plain = store.saveRelay(VpsRelayConfig.manual(true, "Plain",
                "relay.example.com", 443, false, "/apiws", "two", ""), "wifi:ssid:work");

        assertFalse(tls.id().equals(plain.id()));
        assertEquals(2, store.relays().size());
    }

    @Test
    public void failedAtomicWriteDoesNotPublishRelayInMemory() {
        VpsRelayStore store = new VpsRelayStore(new FailingStore());
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Relay",
                "relay.example.com", 443, true, "/apiws", "token", "");

        assertNull(store.saveRelay(relay, "wifi:ssid:home"));
        assertTrue(store.relays().isEmpty());
        assertNull(store.selectedRelay("wifi:ssid:home"));
    }

    @Test
    public void legacyHiddenWifiBindingIsNotAssignedToAnUnrelatedOpaqueWifi() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Home Relay",
                "relay.example.com", 443, true, "/apiws", "token", "");
        store.saveRelay(relay, "wifi:hidden");

        assertNull(store.selectedRelay("wifi:opaque:a1b2c3d4"));
        assertEquals("wifi:hidden", store.selectedRelay("wifi:hidden").profileKey());
    }

    @Test
    public void negotiatedCapabilitiesSurviveRelayStoreRoundTrip() throws Exception {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayCapabilities capabilities = VpsRelayCapabilities.parse(
                "{\"name\":\"tgproxy-relay\",\"protocol\":{\"min\":1,\"max\":2},"
                        + "\"topology\":{\"dynamic\":true,\"revision\":42,"
                        + "\"productionDcs\":[1,2,204],\"testDcs\":[1,4]}}" );
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Relay",
                "relay.example.com", 443, true, "/apiws", "token", "")
                .withCapabilities(capabilities);

        VpsRelayStore.Record saved = store.saveRelay(relay, "wifi:ssid:home");
        VpsRelayConfig restored = store.relay(saved.id()).config();

        assertEquals(capabilities, restored.capabilities());
        assertTrue(restored.supportsRoute(999, false));
    }

    @Test
    public void duplicateDefaultNamesReceiveReadableNumbers() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayStore.Record first = store.saveRelay(VpsRelayConfig.manual(true, "VPS Relay",
                "one.example.com", 443, true, "/apiws", "one", ""), "wifi:home");
        VpsRelayStore.Record second = store.saveRelay(VpsRelayConfig.manual(true, "VPS Relay",
                "two.example.com", 443, true, "/apiws", "two", ""), "wifi:home");

        assertEquals("VPS Relay", first.config().name());
        assertEquals("VPS Relay 2", second.config().name());
    }

    @Test
    public void relayPoolKeepsSelectedRelayFirstAndOtherSavedRelaysAsFallbacks() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayStore.Record first = store.saveRelay(VpsRelayConfig.manual(true, "Primary",
                "one.example.com", 443, true, "/apiws", "one", ""), "wifi:home");
        VpsRelayStore.Record second = store.saveRelay(VpsRelayConfig.manual(true, "Backup",
                "two.example.com", 443, true, "/apiws", "two", ""), "wifi:work");
        store.bindProfile("wifi:home", first.id());
        assertTrue(store.setRelayEnabledForProfile("wifi:home", second.id(), true));

        List<VpsRelayConfig> pool = store.relayPool("wifi:home");

        assertEquals(2, pool.size());
        assertEquals("Primary", pool.get(0).name());
        assertEquals("Backup", pool.get(1).name());
        assertEquals("wifi:home", pool.get(0).profileKey());
        assertEquals("wifi:home", pool.get(1).profileKey());
    }

    @Test
    public void relaySavedForAnotherProfileDoesNotLeakIntoCurrentPool() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayStore.Record home = store.saveRelay(VpsRelayConfig.manual(true, "Home",
                "home.example.com", 443, true, "/apiws", "home", ""), "wifi:home");
        store.saveRelay(VpsRelayConfig.manual(true, "Work",
                "work.example.com", 443, true, "/apiws", "work", ""), "wifi:work");

        List<VpsRelayConfig> pool = store.relayPool("wifi:home");

        assertEquals(1, pool.size());
        assertEquals(home.id(), store.selectedRelayId("wifi:home"));
        assertEquals("Home", pool.get(0).name());
    }

    @Test
    public void disablingInheritedGlobalPrimaryKeepsExplicitEmptyProfileOverride() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayStore.Record global = store.saveRelay(VpsRelayConfig.manual(true, "Global",
                "global.example.com", 443, true, "/apiws", "global", ""), "");

        assertEquals(global.id(), store.selectedRelayId("wifi:home"));
        assertTrue(store.setRelayEnabledForProfile("wifi:home", global.id(), false));

        assertEquals("", store.selectedRelayId("wifi:home"));
        assertTrue(store.relayPool("wifi:home").isEmpty());
        assertEquals(global.id(), store.selectedRelayId("wifi:work"));
    }

    @Test
    public void enablingProfileBackupKeepsInheritedGlobalFallbackPolicy() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayStore.Record global = store.saveRelay(VpsRelayConfig.manual(true, "Global",
                "global.example.com", 443, true, "/apiws", "global", ""), "");
        VpsRelayStore.Record backup = store.saveRelay(VpsRelayConfig.manual(false, "Backup",
                "backup.example.com", 443, true, "/apiws", "backup", ""), "wifi:work");

        assertTrue(store.setRelayEnabledForProfile("wifi:home", backup.id(), true));

        List<VpsRelayConfig> pool = store.relayPool("wifi:home");
        assertEquals(2, pool.size());
        assertEquals(global.id(), store.selectedRelayId("wifi:home"));
        assertEquals("Global", pool.get(0).name());
        assertEquals("Backup", pool.get(1).name());

        store.saveRelay(VpsRelayConfig.manual(true, "New global",
                "global-two.example.com", 443, true, "/apiws", "global-two", ""), "");
        assertEquals(global.id(), store.selectedRelayId("wifi:home"));
    }

    @Test
    public void editingSelectedRelayUpdatesItWithoutLeavingOrphan() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsRelayStore.Record original = store.saveRelay(VpsRelayConfig.manual(true, "Relay",
                "old.example.com", 443, true, "/apiws", "old", ""), "wifi:home");
        VpsRelayStore.Record updated = store.updateRelayInto(original.id(),
                VpsRelayConfig.manual(true, "Relay", "new.example.com", 443,
                        true, "/apiws", "new", ""), "wifi:home", null);

        assertEquals(original.id(), updated.id());
        assertEquals(1, store.relays().size());
        assertEquals("new.example.com", store.selectedRelay("wifi:home").host());
        assertEquals("new", store.selectedRelay("wifi:home").token());
    }

    private static final class RecordingStore implements VpsRelayStore.KeyValueStore {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        int singleWrites;
        int batchWrites;

        @Override public String getString(String key, String fallback) {
            String value = values.get(key);
            return value == null ? fallback : value;
        }

        @Override public boolean putString(String key, String value) {
            singleWrites++;
            values.put(key, value);
            return true;
        }

        @Override public boolean putStrings(Map<String, String> updates) {
            batchWrites++;
            values.putAll(updates);
            return true;
        }
    }

    private static final class FailingStore implements VpsRelayStore.KeyValueStore {
        @Override public String getString(String key, String fallback) { return fallback; }
        @Override public boolean putString(String key, String value) { return false; }
        @Override public boolean putStrings(Map<String, String> updates) { return false; }
    }
}

