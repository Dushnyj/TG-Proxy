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

    @Test
    public void encryptedRelayExportDoesNotExposeTokenAndRoundTrips() throws Exception {
        String exported = SettingsTransfer.exportEncryptedVpsRelay(
                sampleData().relayConfig(), "relay-password");

        assertFalse(exported.contains("relay-token"));
        SettingsTransfer.Imported imported = SettingsTransfer.parse(exported, "relay-password");
        assertEquals(SettingsTransfer.Kind.VPS_RELAY, imported.kind());
        assertEquals("relay-token", imported.data().relayConfig().token());
    }

    @Test
    public void parseAcceptsDeeplinkEmbeddedInSharedText() throws Exception {
        String exported = SettingsTransfer.exportVpsRelay(sampleData().relayConfig());
        String deeplink = SettingsTransfer.toDeepLink(exported);

        SettingsTransfer.Imported imported = SettingsTransfer.parse(
                "TG Proxy import:\n" + deeplink + "\n", "");

        assertEquals(SettingsTransfer.Kind.VPS_RELAY, imported.kind());
        assertEquals("relay-token", imported.data().relayConfig().token());
    }

    @Test
    public void oversizedPayloadIsRejectedBeforeParsingOrDecoding() throws Exception {
        StringBuilder payload = new StringBuilder(SettingsTransfer.MAX_IMPORT_CHARS + 1);
        while (payload.length() <= SettingsTransfer.MAX_IMPORT_CHARS) payload.append('x');

        assertTooLarge(() -> SettingsTransfer.parse(payload.toString(), ""));
        assertTooLarge(() -> SettingsTransfer.parseDeepLink(
                "tgproxy://import?data=" + payload, ""));
    }

    @Test
    public void malformedEncryptedEnvelopeIsRejected() throws Exception {
        try {
            SettingsTransfer.parse("TGPROXY-ENC-v1\nsalt=0\niv=00\ndata=00", "password");
            throw new AssertionError("malformed encrypted profile accepted");
        } catch (SettingsTransferException expected) {
            assertTrue(expected.getMessage().contains("damaged"));
        }
    }

    @Test
    public void safeProfileCannotSmuggleRelayCredentials() throws Exception {
        String payload = SettingsTransfer.exportSafeProfile(sampleData())
                + "\nrelay.enabled=1"
                + "\nrelay.host=relay.example.com"
                + "\nrelay.port=443"
                + "\nrelay.tls=1"
                + "\nrelay.path=%2Fapiws"
                + "\nrelay.token=stolen-token";

        assertRejected(() -> SettingsTransfer.parse(payload, ""), "not allowed");
    }

    @Test
    public void safeProfileCannotSmuggleMtProtoSecret() throws Exception {
        String payload = SettingsTransfer.exportSafeProfile(sampleData())
                + "\nmtprotoSecret=ffeeddccbbaa99887766554433221100";

        assertRejected(() -> SettingsTransfer.parse(payload, ""), "not allowed");
    }

    @Test
    public void plaintextFullProfileAndInvalidRelayAreRejected() throws Exception {
        String plainFull = SettingsTransfer.exportSafeProfile(sampleData())
                .replace("kind=safe_profile", "kind=full_profile")
                + "\nmtprotoSecret=00112233445566778899aabbccddeeff";
        assertRejected(() -> SettingsTransfer.parse(plainFull, ""), "encrypted");

        String invalidRelay = "TGPROXY-SETTINGS-v1\nkind=vps_relay"
                + "\nrelay.enabled=1\nrelay.host=relay.example.com\nrelay.port=443"
                + "\nrelay.tls=1\nrelay.path=%2Fapiws\nrelay.token=bad+token";
        assertRejected(() -> SettingsTransfer.parse(invalidRelay, ""), "invalid VPS Relay");
    }

    @Test
    public void duplicateMalformedAndCrlfFieldsAreHandledStrictly() throws Exception {
        String relay = SettingsTransfer.exportVpsRelay(sampleData().relayConfig());
        SettingsTransfer.Imported crlf = SettingsTransfer.parse(relay.replace("\n", "\r\n"), "");
        assertEquals(SettingsTransfer.Kind.VPS_RELAY, crlf.kind());

        assertRejected(() -> SettingsTransfer.parse(
                relay + "\nkind=vps_relay", ""), "damaged");
        assertRejected(() -> SettingsTransfer.parse(
                relay.replace("relay.name=Work+Relay", "relay.name=%ZZ"), ""), "damaged");
        assertRejected(() -> SettingsTransfer.parse(
                relay + "\nmissing-separator", ""), "damaged");
    }

    private static void assertTooLarge(ThrowingRunnable action) throws Exception {
        try {
            action.run();
            throw new AssertionError("oversized profile accepted");
        } catch (SettingsTransferException expected) {
            assertTrue(expected.getMessage().contains("too large"));
        }
    }

    private static void assertRejected(ThrowingRunnable action, String messagePart)
            throws Exception {
        try {
            action.run();
            throw new AssertionError("damaged profile accepted");
        } catch (SettingsTransferException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
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
