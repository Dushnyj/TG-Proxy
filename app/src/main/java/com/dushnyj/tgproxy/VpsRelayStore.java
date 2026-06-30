package com.dushnyj.tgproxy;

import android.content.SharedPreferences;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class VpsRelayStore {
    private static final String KEY_RELAYS = "vps_relays.v1";
    private static final String KEY_PROFILE_BINDINGS = "vps_relay_profile_bindings.v1";
    private static final String GLOBAL_PROFILE = "*";

    interface KeyValueStore {
        String getString(String key, String fallback);
        void putString(String key, String value);
    }

    private final KeyValueStore keyValueStore;
    private final LinkedHashMap<String, Record> relays = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> profileBindings = new LinkedHashMap<>();

    private VpsRelayStore(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
        relays.putAll(parseRelays(keyValueStore.getString(KEY_RELAYS, "")));
        profileBindings.putAll(parseBindings(keyValueStore.getString(KEY_PROFILE_BINDINGS, "")));
        cleanupBindings();
    }

    static VpsRelayStore fromPreferences(SharedPreferences prefs) {
        return new VpsRelayStore(new SharedPreferencesKeyValueStore(prefs));
    }

    static VpsRelayStore inMemory() {
        return new VpsRelayStore(new MemoryKeyValueStore());
    }

    synchronized Record saveRelay(VpsRelayConfig relay, String profileKey) {
        if (relay == null) relay = VpsRelayConfig.disabled();
        String id = idFor(relay);
        Record record = new Record(id, relay.withProfileKey(""));
        relays.put(id, record);
        String key = normalize(profileKey);
        profileBindings.put(bindingKey(key), id);
        persist();
        return record;
    }

    synchronized Record saveUsableRelay(VpsRelayConfig relay, String profileKey) {
        if (relay == null || !relay.isUsable()) return null;
        return saveRelay(relay, profileKey);
    }

    synchronized boolean deleteRelay(String relayId) {
        String id = normalize(relayId);
        if (id.isEmpty() || !relays.containsKey(id)) return false;
        relays.remove(id);
        ArrayList<String> removeBindings = new ArrayList<>();
        for (Map.Entry<String, String> entry : profileBindings.entrySet()) {
            if (id.equals(entry.getValue())) removeBindings.add(entry.getKey());
        }
        for (String key : removeBindings) profileBindings.remove(key);
        persist();
        return true;
    }

    synchronized void bindProfile(String profileKey, String relayId) {
        String key = normalize(profileKey);
        String id = normalize(relayId);
        String bindingKey = bindingKey(key);
        if (id.isEmpty() || !relays.containsKey(id)) profileBindings.remove(bindingKey);
        else profileBindings.put(bindingKey, id);
        persistBindings();
    }

    synchronized String selectedRelayId(String profileKey) {
        String key = normalize(profileKey);
        String selected = key.isEmpty() ? null : profileBindings.get(key);
        return selected == null ? profileBindings.get(GLOBAL_PROFILE) : selected;
    }

    synchronized VpsRelayConfig selectedRelay(String profileKey) {
        String key = normalize(profileKey);
        String relayId = selectedRelayId(key);
        Record record = relayId == null ? null : relays.get(relayId);
        if (record == null) return null;
        return record.config().withProfileKey(key);
    }

    synchronized Record relay(String relayId) {
        return relays.get(normalize(relayId));
    }

    synchronized List<Record> relays() {
        return Collections.unmodifiableList(new ArrayList<>(relays.values()));
    }

    synchronized void importLegacyIfNeeded(VpsRelayConfig relay, String profileKey) {
        if (relay == null || !relay.isUsable()) return;
        String existing = selectedRelayId(profileKey);
        if (existing != null && relays.containsKey(existing)) return;
        saveRelay(relay, profileKey);
    }

    private void cleanupBindings() {
        ArrayList<String> remove = new ArrayList<>();
        for (Map.Entry<String, String> entry : profileBindings.entrySet()) {
            if (!relays.containsKey(entry.getValue())) remove.add(entry.getKey());
        }
        for (String key : remove) profileBindings.remove(key);
        if (!remove.isEmpty()) persistBindings();
    }

    private void persist() {
        keyValueStore.putString(KEY_RELAYS, serializeRelays(relays));
        persistBindings();
    }

    private void persistBindings() {
        keyValueStore.putString(KEY_PROFILE_BINDINGS, serializeBindings(profileBindings));
    }

    private static String idFor(VpsRelayConfig relay) {
        String base = relay.host() + ":" + relay.port() + relay.path();
        return "relay_" + Integer.toHexString(base.toLowerCase(Locale.US).hashCode());
    }

    private static String serializeRelays(Map<String, Record> source) {
        StringBuilder out = new StringBuilder();
        for (Record record : source.values()) {
            VpsRelayConfig c = record.config();
            if (out.length() > 0) out.append('\n');
            out.append(encoded(record.id())).append('\t')
                    .append(c.isEnabled() ? "1" : "0").append('\t')
                    .append(encoded(c.name())).append('\t')
                    .append(encoded(c.host())).append('\t')
                    .append(c.port()).append('\t')
                    .append(c.tls() ? "1" : "0").append('\t')
                    .append(encoded(c.path())).append('\t')
                    .append(encoded(c.token()));
        }
        return out.toString();
    }

    private static LinkedHashMap<String, Record> parseRelays(String raw) {
        LinkedHashMap<String, Record> result = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        for (String line : raw.split("\\n")) {
            String[] parts = line.split("\\t", -1);
            if (parts.length < 8) continue;
            try {
                String id = decoded(parts[0]);
                VpsRelayConfig config = VpsRelayConfig.manual(
                        "1".equals(parts[1]),
                        decoded(parts[2]),
                        decoded(parts[3]),
                        intValue(parts[4], 443),
                        "1".equals(parts[5]),
                        decoded(parts[6]),
                        decoded(parts[7]),
                        "");
                if (!id.isEmpty()) result.put(id, new Record(id, config));
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static String serializeBindings(Map<String, String> source) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(encoded(entry.getKey())).append('\t').append(encoded(entry.getValue()));
        }
        return out.toString();
    }

    private static LinkedHashMap<String, String> parseBindings(String raw) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        for (String line : raw.split("\\n")) {
            String[] parts = line.split("\\t", -1);
            if (parts.length < 2) continue;
            String profile = decoded(parts[0]);
            String relay = decoded(parts[1]);
            if (!profile.isEmpty() && !relay.isEmpty()) result.put(profile, relay);
        }
        return result;
    }

    private static int intValue(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 && parsed <= 65535 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String bindingKey(String profileKey) {
        String key = normalize(profileKey);
        return key.isEmpty() ? GLOBAL_PROFILE : key;
    }

    private static String encoded(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }

    private static String decoded(String value) {
        try { return URLDecoder.decode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }

    static final class Record {
        private final String id;
        private final VpsRelayConfig config;

        Record(String id, VpsRelayConfig config) {
            this.id = id == null ? "" : id;
            this.config = config == null ? VpsRelayConfig.disabled() : config;
        }

        String id() {
            return id;
        }

        VpsRelayConfig config() {
            return config;
        }

        String displayName() {
            return config.name() + " • " + config.host() + ":" + config.port();
        }
    }

    private static final class SharedPreferencesKeyValueStore implements KeyValueStore {
        private final SharedPreferences prefs;

        SharedPreferencesKeyValueStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public String getString(String key, String fallback) {
            return prefs == null ? fallback : prefs.getString(key, fallback);
        }

        @Override
        public void putString(String key, String value) {
            if (prefs != null) prefs.edit().putString(key, value == null ? "" : value).apply();
        }
    }

    private static final class MemoryKeyValueStore implements KeyValueStore {
        private final LinkedHashMap<String, String> values = new LinkedHashMap<>();

        @Override
        public String getString(String key, String fallback) {
            String value = values.get(key);
            return value == null ? fallback : value;
        }

        @Override
        public void putString(String key, String value) {
            values.put(key, value == null ? "" : value);
        }
    }
}
