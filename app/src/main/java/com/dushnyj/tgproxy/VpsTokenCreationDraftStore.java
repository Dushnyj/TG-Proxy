package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Keeps one encrypted idempotent token request until the owner API confirms it. If a response is
 * lost, pressing Create again replays the same request instead of adding a second server token.
 */
final class VpsTokenCreationDraftStore {
    private static final String KEY = "vps_owner_token_creation_draft.v1";
    private static final long MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecureValueStore secure;

    VpsTokenCreationDraftStore(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        secure = new SecureValueStore(context, prefs);
    }

    Draft loadOrCreate(VpsRelayConfig relay, String name) {
        String identity = relay == null ? "" : relay.serverIdentityKey();
        String cleanName = clean(name);
        long now = System.currentTimeMillis();
        try {
            String raw = secure.get(KEY, "");
            if (!raw.isEmpty()) {
                JSONObject json = new JSONObject(raw);
                Draft existing = new Draft(json.optString("server"), json.optString("name"),
                        json.optString("secret"), json.optString("key"),
                        json.optLong("createdAt", 0L));
                if (existing.valid() && existing.serverIdentity.equals(identity)
                        && existing.name.equals(cleanName)
                        && now - existing.createdAt >= 0L
                        && now - existing.createdAt <= MAX_AGE_MS) return existing;
            }
        } catch (Exception ignored) {
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = "tgpr_" + Base64.encodeToString(bytes,
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        Draft created = new Draft(identity, cleanName, secret,
                "req_" + UUID.randomUUID().toString().replace("-", ""), now);
        try {
            JSONObject json = new JSONObject();
            json.put("server", created.serverIdentity);
            json.put("name", created.name);
            json.put("secret", created.secret);
            json.put("key", created.idempotencyKey);
            json.put("createdAt", created.createdAt);
            if (!secure.put(KEY, json.toString())) return null;
        } catch (Exception error) {
            return null;
        }
        return created;
    }

    boolean clear() { return secure.remove(KEY); }

    static final class Draft {
        final String serverIdentity, name, secret, idempotencyKey;
        final long createdAt;

        Draft(String serverIdentity, String name, String secret,
              String idempotencyKey, long createdAt) {
            this.serverIdentity = clean(serverIdentity);
            this.name = clean(name);
            this.secret = clean(secret);
            this.idempotencyKey = clean(idempotencyKey);
            this.createdAt = createdAt;
        }

        boolean valid() {
            return !serverIdentity.isEmpty() && !name.isEmpty()
                    && secret.matches("tgpr_[A-Za-z0-9_-]{40,120}")
                    && idempotencyKey.matches("[A-Za-z0-9._-]{8,96}");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
