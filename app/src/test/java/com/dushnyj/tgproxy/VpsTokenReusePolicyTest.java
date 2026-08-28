package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsTokenReusePolicyTest {
    @Test
    public void sameManagedVpsOffersKnownTokenAcrossPublicAlias() {
        VpsRelayConfig oldEndpoint = relay("old.example.com", "old-secret");
        VpsOwnerRecord owner = VpsOwnerRecord.fromSetup(request(oldEndpoint), oldEndpoint)
                .withManagedToken("tok_family", "Семья", "family-secret");
        VpsRelayConfig requested = relay("new.example.com", "probe-token");

        List<VpsRelayConfig> choices = VpsTokenReusePolicy.choices(
                Collections.singletonList(requested), Collections.emptyList(),
                Collections.emptyList(), owner, true, false,
                Collections.emptyList(), "wifi-home");

        assertEquals(2, choices.size());
        assertEquals("old-secret", choices.get(0).token());
        assertEquals("family-secret", choices.get(1).token());
        assertEquals("new.example.com", choices.get(0).host());
    }

    @Test
    public void disabledSavedConnectionCanBeReusedAndIsDeduplicatedBySecret() {
        VpsRelayConfig endpoint = relay("relay.example.com", "same-secret");
        VpsRelayConfig disabled = endpoint.withEnabled(false);
        VpsRelayStore.Record saved = new VpsRelayStore.Record("relay_one", disabled);
        VpsOwnerRecord owner = VpsOwnerRecord.fromSetup(request(endpoint), endpoint);

        List<VpsRelayConfig> choices = VpsTokenReusePolicy.choices(
                Collections.singletonList(endpoint), Collections.singletonList(saved),
                Collections.singletonList(owner), owner, true, false,
                Collections.emptyList(), "");

        assertEquals(1, choices.size());
        assertEquals("same-secret", choices.get(0).token());
    }

    @Test
    public void unrelatedOwnerIsNotOfferedWithoutAuditedSshMatch() {
        VpsRelayConfig requested = relay("wanted.example.com", "probe-token");
        VpsRelayConfig unrelated = relay("other.example.com", "other-secret");
        VpsOwnerRecord owner = VpsOwnerRecord.fromSetup(request(unrelated), unrelated);

        List<VpsRelayConfig> choices = VpsTokenReusePolicy.choices(
                Arrays.asList(requested), Collections.emptyList(),
                Collections.singletonList(owner), null, false, false,
                Collections.emptyList(), "");

        assertEquals(0, choices.size());
    }

    @Test
    public void auditedServerInventoryRejectsRevokedLocalSecret() {
        VpsRelayConfig endpoint = relay("relay.example.com", "stale-secret");
        VpsRelayStore.Record saved = new VpsRelayStore.Record("relay_one", endpoint);

        List<VpsRelayConfig> choices = VpsTokenReusePolicy.choices(
                Collections.singletonList(endpoint), Collections.singletonList(saved),
                Collections.emptyList(), null, true, true,
                Collections.singletonList("cfg_not_the_local_token"), "");

        assertEquals(0, choices.size());
    }

    @Test
    public void auditedServerInventoryKeepsMatchingRecoverableSecret() {
        VpsRelayConfig endpoint = relay("relay.example.com", "saved-secret");
        String activeId = VpsOwnerRecord.clientTokenId(endpoint.token());

        List<VpsRelayConfig> choices = VpsTokenReusePolicy.choices(
                Collections.singletonList(endpoint),
                Collections.singletonList(new VpsRelayStore.Record("relay_one", endpoint)),
                Collections.emptyList(), null, true, true,
                Collections.singletonList(activeId), "");

        assertEquals(1, choices.size());
        assertEquals("saved-secret", choices.get(0).token());
    }

    @Test
    public void dynamicServerTokenIdIsMatchedThroughOwnerInventory() {
        VpsRelayConfig endpoint = relay("relay.example.com", "dynamic-secret");
        VpsRelayStore.Record saved = new VpsRelayStore.Record("relay_one", endpoint);
        VpsOwnerRecord owner = VpsOwnerRecord.fromSetup(request(endpoint), endpoint)
                .withManagedToken("tok_0123456789abcdef01234567", "Телефон",
                        "dynamic-secret");

        List<VpsRelayConfig> choices = VpsTokenReusePolicy.choices(
                Collections.singletonList(endpoint), Collections.singletonList(saved),
                Collections.singletonList(owner), owner, true, true,
                Collections.singletonList("tok_0123456789abcdef01234567"), "wifi-home");

        assertEquals(1, choices.size());
        assertEquals("dynamic-secret", choices.get(0).token());
    }

    @Test
    public void selectedRevokedSecretIsNotCarriedIntoExistingRelayPlan() {
        String activeId = VpsOwnerRecord.clientTokenId("active-secret");

        assertFalse(VpsTokenReusePolicy.isPresentOnAuditedServer(
                "revoked-secret", true, true, Collections.singletonList(activeId)));
        assertTrue(VpsTokenReusePolicy.isPresentOnAuditedServer(
                "active-secret", true, true, Collections.singletonList(activeId)));
        assertTrue(VpsTokenReusePolicy.isPresentOnAuditedServer(
                "revoked-secret", true, false, Collections.emptyList()));
    }

    private static VpsRelayConfig relay(String host, String token) {
        return VpsRelayConfig.manual(true, "VPS Relay", host,
                443, true, "/apiws", token, "");
    }

    private static VpsSetupRequest request(VpsRelayConfig relay) {
        return VpsSetupRequest.builder()
                .sshHost("192.0.2.10")
                .sshPort(22)
                .sshUser("root")
                .sshPassword("password")
                .relayHost(relay.host())
                .relayPort(relay.port())
                .relayTls(relay.tls())
                .relayPath(relay.path())
                .relayToken(relay.token())
                .adminToken("admin-secret")
                .releaseVersion("test")
                .build();
    }
}
