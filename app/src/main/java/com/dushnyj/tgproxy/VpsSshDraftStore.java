package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * Encrypted access draft used only until a new VPS setup has produced an owner record.
 * It survives APK updates, but is never included in diagnostics, exports, links, or QR codes.
 */
final class VpsSshDraftStore {
    static final String KEY_DRAFT = "vps_ssh_setup_draft.v1";
    private final SecureValueStore secure;

    VpsSshDraftStore(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        SharedPreferences preferences = app == null ? null
                : PreferenceManager.getDefaultSharedPreferences(app);
        secure = new SecureValueStore(app, preferences);
    }

    synchronized VpsSshCredentials load() {
        String raw = secure.get(KEY_DRAFT, "");
        if (raw == null || raw.isEmpty()) return null;
        String[] fields = raw.split("\\t", -1);
        if (fields.length != 4) return null;
        try {
            VpsSshCredentials credentials = new VpsSshCredentials(
                    decode(fields[0]), Integer.parseInt(fields[1]),
                    decode(fields[2]), decode(fields[3]));
            return credentials.isValid() && !credentials.password().isEmpty()
                    ? credentials : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    synchronized boolean save(VpsSshCredentials credentials) {
        if (credentials == null || !credentials.isValid() || credentials.password().isEmpty()) {
            return false;
        }
        String raw = encode(credentials.host()) + "\t" + credentials.port() + "\t"
                + encode(credentials.user()) + "\t" + encode(credentials.password());
        return secure.put(KEY_DRAFT, raw);
    }

    synchronized boolean clear() {
        return secure.remove(KEY_DRAFT);
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }
}
