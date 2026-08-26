package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GithubReleaseUpdaterTest {
    @Test
    public void detectsNewerSemanticVersion() {
        assertTrue(GithubReleaseUpdater.isNewerVersion("1.0.1", "1.0.0"));
        assertTrue(GithubReleaseUpdater.isNewerVersion("v1.2.0", "1.1.9"));
        assertFalse(GithubReleaseUpdater.isNewerVersion("1.0.0", "1.0.0"));
        assertFalse(GithubReleaseUpdater.isNewerVersion("0.9.9", "1.0.0"));
    }

    @Test
    public void prefersUniversalReleaseApkForSelfUpdate() throws Exception {
        String[] names = {
                "TG-Proxy-v1.0.1-android-arm64-v8a-release.apk",
                "TG-Proxy-v1.0.1-android-universal-release.apk",
                "TG-Proxy-v1.0.1-android-x86_64-release.apk"
        };
        String[] urls = {"arm64", "universal", "x86"};

        assertEquals("universal", GithubReleaseUpdater.selectReleaseApkUrl(names, urls));
    }

    @Test
    public void selfUpdateUsesPackageInstallerAction() {
        assertEquals("android.intent.action.INSTALL_PACKAGE",
                GithubReleaseUpdater.installIntentAction());
    }

    @Test
    public void extractsExactApkChecksumWithoutAcceptingPrefixMatch() {
        String expected = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String manifest = expected + "  TG-Proxy-v1.0.7-android-universal-release.apk\n"
                + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff  other.apk\n";

        assertEquals(expected, GithubReleaseUpdater.checksumForAsset(manifest,
                "TG-Proxy-v1.0.7-android-universal-release.apk"));
        assertEquals("", GithubReleaseUpdater.checksumForAsset(manifest,
                "TG-Proxy-v1.0.7-android-universal-release.apk.extra"));
    }

    @Test
    public void rejectsUnsafeReleaseAssetNames() {
        assertEquals("TG-Proxy-v1.0.7-release.apk",
                GithubReleaseUpdater.requireSafeApkName("TG-Proxy-v1.0.7-release.apk"));
        assertUnsafeApkName("../update.apk");
        assertUnsafeApkName("folder/update.apk");
        assertUnsafeApkName("folder\\update.apk");
        assertUnsafeApkName("update.zip");
    }

    private static void assertUnsafeApkName(String value) {
        try {
            GithubReleaseUpdater.requireSafeApkName(value);
            throw new AssertionError("unsafe APK name accepted: " + value);
        } catch (SecurityException expected) {
            assertTrue(expected.getMessage().contains("APK name"));
        }
    }
}
