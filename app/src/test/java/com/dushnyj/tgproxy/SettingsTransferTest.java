package com.dushnyj.tgproxy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

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
    public void clickableRelayLinkKeepsCredentialInFragmentAndRoundTrips() throws Exception {
        VpsRelayConfig relay = sampleData().relayConfig();
        String exported = SettingsTransfer.exportVpsRelay(relay);

        String link = SettingsTransfer.toRelayShareLink(relay, exported);

        assertTrue(link.startsWith("http://relay.example.com:18080/apiws/connect#data=b64_"));
        assertFalse(link.contains("?data="));
        assertFalse(link.contains("relay-token"));
        assertTrue(SettingsTransfer.isImportLink(link));
        SettingsTransfer.Imported imported = SettingsTransfer.parseDeepLink(link, "");
        assertEquals(SettingsTransfer.Kind.VPS_RELAY, imported.kind());
        assertEquals("relay-token", imported.data().relayConfig().token());
    }

    @Test
    public void relayQrEncodesAndDecodesTheClickableImportLink() throws Exception {
        VpsRelayConfig relay = sampleData().relayConfig();
        String link = SettingsTransfer.toRelayShareLink(
                relay, SettingsTransfer.exportVpsRelay(relay));
        BitMatrix matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 640, 640);
        int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                pixels[y * matrix.getWidth() + x] = matrix.get(x, y)
                        ? 0xff000000 : 0xffffffff;
            }
        }

        String decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(matrix.getWidth(), matrix.getHeight(), pixels))))
                .getText();

        assertEquals(link, decoded);
        assertEquals("relay-token",
                SettingsTransfer.parseDeepLink(decoded, "").data().relayConfig().token());
    }

    @Test
    public void legacyQueryRelayLinkRemainsImportable() throws Exception {
        VpsRelayConfig relay = sampleData().relayConfig();
        String exported = SettingsTransfer.exportVpsRelay(relay);
        String compact = SettingsTransfer.toCompactDeepLink(exported)
                .substring("tgproxy://import?data=".length());
        String legacy = "https://relay.example.com/apiws/connect?data=" + compact;

        assertTrue(SettingsTransfer.isImportLink(legacy));
        assertEquals("relay-token",
                SettingsTransfer.parseDeepLink(legacy, "").data().relayConfig().token());
    }

    @Test
    public void sharedTextSkipsUnrelatedUrlBeforeRelayLink() throws Exception {
        VpsRelayConfig relay = sampleData().relayConfig();
        String link = SettingsTransfer.toRelayShareLink(
                relay, SettingsTransfer.exportVpsRelay(relay));

        SettingsTransfer.Imported imported = SettingsTransfer.parse(
                "Инструкция: https://example.org/help\nПодключение: " + link + ".", "");

        assertEquals("relay-token", imported.data().relayConfig().token());
    }

    @Test
    public void hostileOrIncompleteHttpLinksAreNotAcceptedAsImports() throws Exception {
        assertFalse(SettingsTransfer.isImportLink(
                "https://user@example.org/apiws/connect#data=b64_YQ"));
        assertFalse(SettingsTransfer.isImportLink(
                "https://example.org/apiws/connect"));
        assertFalse(SettingsTransfer.isImportLink(
                "javascript://example.org/apiws/connect#data=b64_YQ"));
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
    public void recursivelyNestedDeepLinksAreRejectedWithABoundedError() throws Exception {
        String nested = SettingsTransfer.exportVpsRelay(sampleData().relayConfig());
        for (int index = 0; index < 6; index++) nested = SettingsTransfer.toDeepLink(nested);
        String deeplyNested = nested;

        assertRejected(() -> SettingsTransfer.parseDeepLink(deeplyNested, ""), "nesting");
    }

    @Test
    public void compactLinksRejectInvalidAlphabetLengthAndNonCanonicalEncoding() throws Exception {
        assertRejected(() -> SettingsTransfer.parseDeepLink(
                "tgproxy://import?data=b64_%%%", ""), "decode");
        assertRejected(() -> SettingsTransfer.parseDeepLink(
                "tgproxy://import?data=b64_A", ""), "decode share link");
        assertRejected(() -> SettingsTransfer.parseDeepLink(
                "tgproxy://import?data=b64_AB", ""), "decode share link");

        StringBuilder oversized = new StringBuilder("tgproxy://import?data=b64_");
        while (oversized.length() < 17_000) oversized.append('A');
        assertRejected(() -> SettingsTransfer.parseDeepLink(
                oversized.toString(), ""), "decode share link");
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
