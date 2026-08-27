package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SecureStorageInstrumentedTest {
    private Context context;
    private SharedPreferences isolated;
    private SharedPreferences defaults;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        isolated = context.getSharedPreferences("secure-storage-test", Context.MODE_PRIVATE);
        defaults = PreferenceManager.getDefaultSharedPreferences(context);
        isolated.edit().clear().commit();
        defaults.edit()
                .remove(VpsRelayStore.KEY_RELAYS)
                .remove(VpsRelayStore.KEY_PROFILE_BINDINGS)
                .remove(VpsOwnerStore.KEY_OWNERS)
                .commit();
    }

    @After
    public void tearDown() {
        isolated.edit().clear().commit();
        defaults.edit()
                .remove(VpsRelayStore.KEY_RELAYS)
                .remove(VpsRelayStore.KEY_PROFILE_BINDINGS)
                .remove(VpsOwnerStore.KEY_OWNERS)
                .commit();
    }

    @Test
    public void keystoreValueIsAuthenticatedAndPlaintextIsNotPersisted() {
        SecureValueStore secure = new SecureValueStore(context, isolated);

        assertTrue(secure.put("owner", "sensitive-value"));
        String raw = isolated.getString("owner", "");

        assertTrue(raw.startsWith("tgproxy-secure-v1:"));
        assertFalse(raw.contains("sensitive-value"));
        assertEquals("sensitive-value", secure.get("owner", ""));
        isolated.edit().putString("different-key", raw).commit();
        assertEquals("fallback", secure.get("different-key", "fallback"));
    }

    @Test
    public void legacyPlaintextIsMigratedWithoutDataLoss() {
        isolated.edit().putString("legacy", "old-private-value").commit();
        SecureValueStore secure = new SecureValueStore(context, isolated);

        assertEquals("old-private-value", secure.get("legacy", ""));
        String migrated = isolated.getString("legacy", "");
        assertTrue(migrated.startsWith("tgproxy-secure-v1:"));
        assertFalse(migrated.contains("old-private-value"));
    }

    @Test
    public void relayAndOwnerRecordsSurviveReloadButRemainEncryptedAtRest() {
        VpsRelayConfig relay = VpsRelayConfig.manual(true, "Test Relay",
                "relay.example.com", 443, true, "/apiws", "client-secret", "wifi:test");
        assertNotNull(VpsRelayStore.fromContext(context).saveRelay(relay, "wifi:test"));

        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("vps.example.com")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("client-secret")
                .adminToken("owner-secret")
                .releaseVersion("1.1.0")
                .rememberSshPassword(true)
                .build();
        assertTrue(new VpsOwnerStore(context).saveSetup(request, relay));

        String rawRelays = defaults.getString(VpsRelayStore.KEY_RELAYS, "");
        String rawOwners = defaults.getString(VpsOwnerStore.KEY_OWNERS, "");
        assertTrue(rawRelays.startsWith("tgproxy-secure-v1:"));
        assertTrue(rawOwners.startsWith("tgproxy-secure-v1:"));
        assertFalse(rawRelays.contains("client-secret"));
        assertFalse(rawOwners.contains("owner-secret"));
        assertFalse(rawOwners.contains("ssh-secret"));

        assertEquals("client-secret", VpsRelayStore.fromContext(context)
                .selectedRelay("wifi:test").token());
        VpsOwnerRecord owner = new VpsOwnerStore(context).forRelay(relay);
        assertNotNull(owner);
        assertEquals("owner-secret", owner.adminToken());
        assertEquals("ssh-secret", owner.sshPassword());
    }
}
