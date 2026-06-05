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
}
