package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VpsOwnerRecordTest {
    @Test
    public void ownerCredentialsAndManagedTokensRoundTripLocally() {
        VpsRelayConfig relay = relay("client-secret");
        VpsSetupRequest request = request(true);

        VpsOwnerRecord parsed = VpsOwnerRecord.parse(
                VpsOwnerRecord.fromSetup(request, relay).serialize());

        assertNotNull(parsed);
        assertEquals("vps.example.com", parsed.sshHost());
        assertEquals(22, parsed.sshPort());
        assertEquals("root", parsed.sshUser());
        assertEquals("ssh-password", parsed.sshPassword());
        assertEquals("owner-secret", parsed.adminToken());
        assertTrue(parsed.canManage());
        String tokenId = VpsOwnerRecord.clientTokenId("client-secret");
        assertTrue(tokenId.matches("cfg_[0-9a-f]{16}"));
        assertEquals("client-secret", parsed.managedToken(tokenId).secret());
    }

    @Test
    public void optingOutDoesNotPersistSshPasswordButKeepsOwnerCapability() {
        VpsOwnerRecord owner = VpsOwnerRecord.fromSetup(request(false), relay("client-secret"));

        assertEquals("", owner.sshPassword());
        assertEquals("owner-secret", owner.adminToken());
        assertTrue(owner.canManage());
    }

    @Test
    public void relayShareNeverContainsOwnerOrSshMaterial() {
        VpsRelayConfig relay = relay("client-secret");
        VpsOwnerRecord owner = VpsOwnerRecord.fromSetup(request(true), relay);

        String exported = SettingsTransfer.exportVpsRelay(relay);

        assertFalse(exported.contains(owner.adminToken()));
        assertFalse(exported.contains(owner.sshPassword()));
        assertFalse(exported.contains(owner.sshHost()));
        assertTrue(exported.contains("client-secret"));
    }

    @Test
    public void endpointUpdateKeepsPreviouslyCreatedTokenSecrets() {
        VpsOwnerRecord previous = VpsOwnerRecord.fromSetup(request(true), relay("client-secret"))
                .withManagedToken("tok_extra", "Семья", "extra-secret");
        VpsOwnerRecord updated = VpsOwnerRecord.fromSetup(request(true), relay("new-client"))
                .mergedWith(previous);

        assertEquals("extra-secret", updated.managedToken("tok_extra").secret());
        assertEquals("new-client", updated.managedToken(
                VpsOwnerRecord.clientTokenId("new-client")).secret());
    }

    @Test
    public void malformedOrOversizedAdminCredentialsCannotEnableOwnerApi() {
        VpsOwnerRecord unsafe = new VpsOwnerRecord("", "vps.example.com", 22, "root", "",
                "owner\r\nforged", "relay.example.com", 443, true, "/apiws", 1L, null);
        VpsOwnerRecord oversized = new VpsOwnerRecord("", "vps.example.com", 22, "root", "",
                repeat('x', 513), "relay.example.com", 443, true, "/apiws", 1L, null);

        assertFalse(unsafe.canManage());
        assertFalse(oversized.canManage());
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int index = 0; index < count; index++) out.append(value);
        return out.toString();
    }

    private static VpsSetupRequest request(boolean rememberPassword) {
        return VpsSetupRequest.builder()
                .sshHost("vps.example.com")
                .sshPort(22)
                .sshUser("root")
                .sshPassword("ssh-password")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("client-secret")
                .adminToken("owner-secret")
                .releaseVersion("1.1.0")
                .rememberSshPassword(rememberPassword)
                .build();
    }

    private static VpsRelayConfig relay(String token) {
        return VpsRelayConfig.manual(true, "Family Relay", "relay.example.com",
                443, true, "/apiws", token, "");
    }
}
