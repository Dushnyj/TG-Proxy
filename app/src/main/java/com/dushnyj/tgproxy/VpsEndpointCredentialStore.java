package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owner-local endpoint credentials. Never included in Relay links, QR codes, or exports. */
final class VpsEndpointCredentialStore {
    private static final String KEY_DUCKDNS = "vps_duckdns_credentials.v1";
    private final SecureValueStore secure;

    VpsEndpointCredentialStore(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        SharedPreferences preferences = app == null ? null
                : PreferenceManager.getDefaultSharedPreferences(app);
        secure = new SecureValueStore(app, preferences);
    }

    synchronized String duckDnsToken(String domain) {
        String normalized = VpsEndpointPolicy.normalizeHost(domain);
        String value = load().get(normalized);
        return value == null ? "" : value;
    }

    synchronized boolean saveDuckDnsToken(String domain, String token) {
        String normalized = VpsEndpointPolicy.normalizeHost(domain);
        String secret = token == null ? "" : token.trim();
        if (!VpsEndpointPolicy.isDuckDnsDomain(normalized) || secret.isEmpty()) return false;
        LinkedHashMap<String, String> values = load();
        values.put(normalized, secret);
        return persist(values);
    }

    synchronized boolean removeDuckDnsToken(String domain) {
        String normalized = VpsEndpointPolicy.normalizeHost(domain);
        LinkedHashMap<String, String> values = load();
        if (values.remove(normalized) == null) return true;
        return persist(values);
    }

    private LinkedHashMap<String, String> load() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        String raw = secure.get(KEY_DUCKDNS, "");
        if (raw == null || raw.isEmpty()) return values;
        for (String line : raw.split("\\n", -1)) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 2) continue;
            String domain = decode(fields[0]);
            String token = decode(fields[1]);
            if (VpsEndpointPolicy.isDuckDnsDomain(domain) && !token.isEmpty()) {
                values.put(domain, token);
            }
        }
        return values;
    }

    private boolean persist(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(encode(value.getKey())).append('\t').append(encode(value.getValue()));
        }
        return secure.put(KEY_DUCKDNS, out.toString());
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
