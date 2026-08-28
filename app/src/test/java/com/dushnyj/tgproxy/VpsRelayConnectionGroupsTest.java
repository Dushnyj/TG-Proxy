package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class VpsRelayConnectionGroupsTest {
    @Test
    public void sameInstallationGroupsEndpointAliasesAndKeepsPrimaryFirst() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        String instance = "ri_0123456789abcdef0123456789abcdef";
        VpsRelayStore.Record backup = store.saveRelay(VpsRelayConfig.manual(true, "Backup",
                        "relay-backup.example.com", 443, true, "/apiws", "token-two", "")
                .withInstanceId(instance), "wifi:home");
        VpsRelayStore.Record primary = store.saveRelay(VpsRelayConfig.manual(true, "Primary",
                        "relay.example.com", 443, true, "/apiws", "token-one", "")
                .withInstanceId(instance), "wifi:home");
        store.makePrimary("wifi:home", primary.id());

        List<VpsRelayConnectionGroups.Group> groups = VpsRelayConnectionGroups.build(
                store.relays(), store, "wifi:home");

        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).connections().size());
        assertEquals(primary.id(), groups.get(0).connections().get(0).id());
        assertEquals(backup.id(), groups.get(0).connections().get(1).id());
    }

    @Test
    public void sameEndpointWithDifferentTokensIsOneServerGroup() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        store.saveRelay(VpsRelayConfig.manual(true, "Phone",
                "relay.example.com", 443, true, "/apiws", "one", ""), "wifi:home");
        store.saveRelay(VpsRelayConfig.manual(false, "Tablet",
                "relay.example.com", 443, true, "/apiws", "two", ""), "wifi:home");

        List<VpsRelayConnectionGroups.Group> groups = VpsRelayConnectionGroups.build(
                store.relays(), store, "wifi:home");

        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).connections().size());
    }

    @Test
    public void endpointDifferencesRemainSeparateWithoutServerIdentity() {
        VpsRelayStore store = VpsRelayStore.inMemory();
        store.saveRelay(VpsRelayConfig.manual(true, "One",
                "relay.example.com", 443, true, "/apiws", "one", ""), "wifi:home");
        store.saveRelay(VpsRelayConfig.manual(true, "Two",
                "relay.example.com", 8443, true, "/apiws", "two", ""), "wifi:home");

        assertEquals(2, VpsRelayConnectionGroups.build(
                store.relays(), store, "wifi:home").size());
    }
}
