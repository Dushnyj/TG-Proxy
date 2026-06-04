package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsTransferTest {
    @Test
    public void safeProfileExportDoesNotIncludeSecretsOrRelayToken() throws Exception {
        SettingsTransfer.Data data = sampleData();

        String exported = SettingsTransfer.exportSafeProfile(data);

        assertTrue(exported.startsWith("TGPROXY-SETTINGS-v1"));
        assertTrue(exported.contains("kind=safe_profile"));
        assertTrue(exported.contains("routePreference=RELAY_FIRST"));
        assertFalse(exported.contains("mtprotoSecret"));
        assertFalse(exported.contains("relay.token"));
        assertFalse(exported.contains("relay-token"));
        assertFalse(exported.toLowerCase().contains("ssh"));
    }

    @Test
    public void relayExportIncludesTokenButNeverSshCredentials() throws Exception {
        String exported = SettingsTransfer.exportVpsRelay(sampleData().relayConfig());

        assertTrue(exported.contains("kind=vps_relay"));
        assertTrue(exported.contains("relay.token=relay-token"));
        assertFalse(exported.toLowerCase().contains("ssh"));

        SettingsTransfer.Data imported = SettingsTransfer.parse(exported, "").data();
        assertEquals("relay.example.com", imported.relayConfig().host());
        assertEquals("relay-token", imported.relayConfig().token());
    }

    @Test
    public void encryptedFullExportRequiresCorrectPasswordAndKeepsSecret() throws Exception {
        String exported = SettingsTransfer.exportEncrypted(sampleData(), "correct-password");

        assertTrue(exported.startsWith("TGPROXY-ENC-v1"));
        try {
            SettingsTransfer.parse(exported, "wrong-password");
            throw new AssertionError("wrong password accepted");
        } catch (SettingsTransferException expected) {
            assertTrue(expected.getMessage().contains("password"));
        }

        SettingsTransfer.Imported imported = SettingsTransfer.parse(exported, "correct-password");
        assertEquals(SettingsTransfer.Kind.FULL_PROFILE, imported.kind());
        assertEquals("00112233445566778899aabbccddeeff", imported.data().mtProtoSecret());
        assertEquals("relay-token", imported.data().relayConfig().token());
    }

    @Test
    public void deeplinkRoundTripUsesUrlSafePayload() throws Exception {
        String exported = SettingsTransfer.exportVpsRelay(sampleData().relayConfig());
        String deeplink = SettingsTransfer.toDeepLink(exported);

        assertTrue(deeplink.startsWith("tgproxy://import?data="));

        SettingsTransfer.Imported imported = SettingsTransfer.parseDeepLink(deeplink, "");
        assertEquals(SettingsTransfer.Kind.VPS_RELAY, imported.kind());
        assertEquals("relay.example.com", imported.data().relayConfig().host());
    }

    private static SettingsTransfer.Data sampleData() {
        return SettingsTransfer.Data.builder()
                .profileName("Tele2 LTE")
                .routePreference(RoutePreference.RELAY_FIRST)
                .customIp("127.0.0.1")
                .customPort(1080)
                .mtProtoSecret("00112233445566778899aabbccddeeff")
                .dcRules("2:149.154.167.220\n4:149.154.167.220")
                .cfMode(MtProtoProxyEngine.CF_MODE_AUTO)
                .cfDomains("kws1.example.com\nkws2.example.com")
                .workerDomains("worker.example.workers.dev")
                .relayConfig(VpsRelayConfig.manual(true, "Work Relay",
                        "relay.example.com", 18080, false, "/apiws", "relay-token", ""))
                .build();
    }
}
