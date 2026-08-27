package com.dushnyj.tgproxy;

import android.content.SharedPreferences;
import android.content.Context;

import androidx.preference.PreferenceManager;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class VpsRelayStore {
    static final String KEY_RELAYS = "vps_relays.v1";
    static final String KEY_PROFILE_BINDINGS = "vps_relay_profile_bindings.v1";
    private static final String GLOBAL_PROFILE = "*";

    interface KeyValueStore {
        String getString(String key, String fallback);
        boolean putString(String key, String value);
        boolean putStrings(Map<String, String> values);
        default boolean putStringsInto(SharedPreferences.Editor editor,
                                       Map<String, String> values) {
            if (editor == null || values == null || values.isEmpty()) return false;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
            return true;
        }
    }

    private final KeyValueStore keyValueStore;
    private final LinkedHashMap<String, Record> relays = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> profileBindings = new LinkedHashMap<>();

    VpsRelayStore(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
        relays.putAll(parseRelays(keyValueStore.getString(KEY_RELAYS, "")));
        profileBindings.putAll(parseBindings(keyValueStore.getString(KEY_PROFILE_BINDINGS, "")));
        cleanupBindings();
    }

    static VpsRelayStore fromPreferences(SharedPreferences prefs) {
        return new VpsRelayStore(new SharedPreferencesKeyValueStore(prefs));
    }

    static VpsRelayStore fromContext(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        SharedPreferences prefs = app == null ? null
                : PreferenceManager.getDefaultSharedPreferences(app);
        return new VpsRelayStore(new SecureSharedPreferencesKeyValueStore(app, prefs));
    }

    static VpsRelayStore inMemory() {
        return new VpsRelayStore(new MemoryKeyValueStore());
    }

    synchronized Record saveRelay(VpsRelayConfig relay, String profileKey) {
        return saveRelayInto(relay, profileKey, null);
    }

    synchronized Record saveRelayInto(VpsRelayConfig relay, String profileKey,
                                       SharedPreferences.Editor editor) {
        if (relay == null) relay = VpsRelayConfig.disabled();
        LinkedHashMap<String, Record> previousRelays = new LinkedHashMap<>(relays);
        LinkedHashMap<String, String> previousBindings = new LinkedHashMap<>(profileBindings);
        String id = existingEndpointId(relay);
        boolean isNew = id.isEmpty();
        if (isNew) id = idFor(relay);
        VpsRelayConfig stored = relay.withProfileKey("")
                .withName(uniqueName(relay.name(), isNew ? "" : id));
        Record record = new Record(id, stored);
        relays.put(id, record);
        String key = normalize(profileKey);
        profileBindings.put(bindingKey(key), id);
        if (editor == null) {
            if (!persist()) {
                relays.clear();
                relays.putAll(previousRelays);
                profileBindings.clear();
                profileBindings.putAll(previousBindings);
                return null;
            }
        } else if (!writeAll(editor)) {
            relays.clear();
            relays.putAll(previousRelays);
            profileBindings.clear();
            profileBindings.putAll(previousBindings);
            return null;
        }
        return record;
    }

    synchronized Record updateRelayInto(String relayId, VpsRelayConfig relay, String profileKey,
                                         SharedPreferences.Editor editor) {
        String id = normalize(relayId);
        if (id.isEmpty() || !relays.containsKey(id) || relay == null) {
            return saveRelayInto(relay, profileKey, editor);
        }
        LinkedHashMap<String, Record> previousRelays = new LinkedHashMap<>(relays);
        LinkedHashMap<String, String> previousBindings = new LinkedHashMap<>(profileBindings);
        VpsRelayConfig stored = relay.withProfileKey("")
                .withName(uniqueName(relay.name(), id));
        Record record = new Record(id, stored);
        relays.put(id, record);
        profileBindings.put(bindingKey(profileKey), id);
        boolean written = editor == null ? persist() : writeAll(editor);
        if (!written) {
            relays.clear();
            relays.putAll(previousRelays);
            profileBindings.clear();
            profileBindings.putAll(previousBindings);
            return null;
        }
        return record;
    }

    synchronized Record saveUsableRelay(VpsRelayConfig relay, String profileKey) {
        if (relay == null || !relay.isUsable()) return null;
        return saveRelay(relay, profileKey);
    }

    synchronized boolean deleteRelay(String relayId) {
        return deleteRelayInto(relayId, null);
    }

    synchronized boolean deleteRelayInto(String relayId, SharedPreferences.Editor editor) {
        String id = normalize(relayId);
        if (id.isEmpty() || !relays.containsKey(id)) return false;
        LinkedHashMap<String, Record> previousRelays = new LinkedHashMap<>(relays);
        LinkedHashMap<String, String> previousBindings = new LinkedHashMap<>(profileBindings);
        relays.remove(id);
        repairBindings(id);
        if (editor == null) {
            if (!persist()) {
                relays.clear();
                relays.putAll(previousRelays);
                profileBindings.clear();
                profileBindings.putAll(previousBindings);
                return false;
            }
        } else if (!writeAll(editor)) {
            relays.clear();
            relays.putAll(previousRelays);
            profileBindings.clear();
            profileBindings.putAll(previousBindings);
            return false;
        }
        return true;
    }

    synchronized boolean setRelayEnabled(String relayId, boolean enabled) {
        String id = normalize(relayId);
        Record current = relays.get(id);
        if (current == null) return false;
        if (current.config().isEnabled() == enabled) return true;
        LinkedHashMap<String, Record> previousRelays = new LinkedHashMap<>(relays);
        LinkedHashMap<String, String> previousBindings = new LinkedHashMap<>(profileBindings);
        relays.put(id, new Record(id, current.config().withEnabled(enabled)));
        if (!enabled) repairBindings(id);
        if (persist()) return true;
        relays.clear();
        relays.putAll(previousRelays);
        profileBindings.clear();
        profileBindings.putAll(previousBindings);
        return false;
    }

    synchronized boolean makePrimary(String profileKey, String relayId) {
        String id = normalize(relayId);
        Record current = relays.get(id);
        if (current == null || !current.config().hasValidConnection()) return false;
        LinkedHashMap<String, Record> previousRelays = new LinkedHashMap<>(relays);
        LinkedHashMap<String, String> previousBindings = new LinkedHashMap<>(profileBindings);
        relays.put(id, new Record(id, current.config().withEnabled(true)));
        profileBindings.put(bindingKey(profileKey), id);
        if (persist()) return true;
        relays.clear();
        relays.putAll(previousRelays);
        profileBindings.clear();
        profileBindings.putAll(previousBindings);
        return false;
    }

    synchronized Record activateConnection(VpsRelayConfig relay, String profileKey) {
        if (relay == null || !relay.hasValidConnection()) return null;
        return saveRelay(relay.withEnabled(true), profileKey);
    }

    synchronized String relayIdFor(VpsRelayConfig relay) {
        return relay == null ? "" : existingEndpointId(relay);
    }

    synchronized boolean deleteConnection(VpsRelayConfig endpoint, String token) {
        if (endpoint == null || normalize(token).isEmpty()) return false;
        ArrayList<String> ids = new ArrayList<>();
        for (Record record : relays.values()) {
            if (record.config().sameEndpoint(endpoint)
                    && record.config().token().equals(token)) ids.add(record.id());
        }
        if (ids.isEmpty()) return true;
        LinkedHashMap<String, Record> previousRelays = new LinkedHashMap<>(relays);
        LinkedHashMap<String, String> previousBindings = new LinkedHashMap<>(profileBindings);
        for (String id : ids) {
            relays.remove(id);
            repairBindings(id);
        }
        if (persist()) return true;
        relays.clear();
        relays.putAll(previousRelays);
        profileBindings.clear();
        profileBindings.putAll(previousBindings);
        return false;
    }

    synchronized boolean bindProfile(String profileKey, String relayId) {
        return bindProfileInto(profileKey, relayId, null);
    }

    synchronized boolean bindProfileInto(String profileKey, String relayId,
                                         SharedPreferences.Editor editor) {
        LinkedHashMap<String, String> previousBindings = new LinkedHashMap<>(profileBindings);
        String key = normalize(profileKey);
        String id = normalize(relayId);
        String bindingKey = bindingKey(key);
        if (id.isEmpty() || !relays.containsKey(id)) profileBindings.remove(bindingKey);
        else profileBindings.put(bindingKey, id);
        if (editor == null) {
            if (!persistBindings()) {
                profileBindings.clear();
                profileBindings.putAll(previousBindings);
                return false;
            }
        } else {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            values.put(KEY_PROFILE_BINDINGS, serializeBindings(profileBindings));
            if (!keyValueStore.putStringsInto(editor, values)) {
                profileBindings.clear();
                profileBindings.putAll(previousBindings);
                return false;
            }
        }
        return true;
    }

    synchronized String selectedRelayId(String profileKey) {
        String key = normalize(profileKey);
        String selected = specificRelayId(key);
        return selected == null ? profileBindings.get(GLOBAL_PROFILE) : selected;
    }

    synchronized VpsRelayConfig selectedRelay(String profileKey) {
        String key = normalize(profileKey);
        String relayId = specificRelayId(key);
        boolean profileSpecific = relayId != null;
        if (relayId == null) relayId = profileBindings.get(GLOBAL_PROFILE);
        Record record = relayId == null ? null : relays.get(relayId);
        if (record == null) return null;
        return record.config().withProfileKey(profileSpecific ? key : "");
    }

    synchronized Record relay(String relayId) {
        return relays.get(normalize(relayId));
    }

    synchronized List<Record> relays() {
        return Collections.unmodifiableList(new ArrayList<>(relays.values()));
    }

    /** Primary Relay followed by every other enabled saved Relay for automatic failover. */
    synchronized List<VpsRelayConfig> relayPool(String profileKey) {
        String selectedId = selectedRelayId(profileKey);
        if (selectedId == null || selectedId.isEmpty()) return Collections.emptyList();
        ArrayList<VpsRelayConfig> result = new ArrayList<>();
        Record primary = relays.get(selectedId);
        if (primary != null && primary.config().isUsable()) {
            result.add(primary.config().withProfileKey(normalize(profileKey)));
        }
        for (Record record : relays.values()) {
            if (record.id().equals(selectedId) || !record.config().isUsable()) continue;
            result.add(record.config().withProfileKey(""));
        }
        return Collections.unmodifiableList(result);
    }

    synchronized boolean importLegacyIfNeeded(VpsRelayConfig relay, String profileKey) {
        if (relay == null || !relay.isUsable()) return true;
        String existing = selectedRelayId(profileKey);
        if (existing != null && relays.containsKey(existing)) return true;
        return saveRelay(relay, profileKey) != null;
    }

    private void cleanupBindings() {
        ArrayList<String> remove = new ArrayList<>();
        for (Map.Entry<String, String> entry : profileBindings.entrySet()) {
            if (!relays.containsKey(entry.getValue())) remove.add(entry.getKey());
        }
        for (String key : remove) profileBindings.remove(key);
        if (!remove.isEmpty()) persistBindings();
    }

    private boolean persist() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(KEY_RELAYS, serializeRelays(relays));
        values.put(KEY_PROFILE_BINDINGS, serializeBindings(profileBindings));
        return keyValueStore.putStrings(values);
    }

    private void repairBindings(String unavailableId) {
        String fallback = firstUsableRelayId(unavailableId);
        ArrayList<String> affected = new ArrayList<>();
        for (Map.Entry<String, String> entry : profileBindings.entrySet()) {
            if (unavailableId.equals(entry.getValue())) affected.add(entry.getKey());
        }
        for (String key : affected) {
            if (fallback.isEmpty()) profileBindings.remove(key);
            else profileBindings.put(key, fallback);
        }
    }

    private String firstUsableRelayId(String exceptId) {
        for (Record record : relays.values()) {
            if (!record.id().equals(exceptId) && record.config().isUsable()) return record.id();
        }
        return "";
    }

    private boolean persistBindings() {
        return keyValueStore.putString(KEY_PROFILE_BINDINGS, serializeBindings(profileBindings));
    }

    private boolean writeAll(SharedPreferences.Editor editor) {
        if (editor == null) return false;
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(KEY_RELAYS, serializeRelays(relays));
        values.put(KEY_PROFILE_BINDINGS, serializeBindings(profileBindings));
        return keyValueStore.putStringsInto(editor, values);
    }

    private static String idFor(VpsRelayConfig relay) {
        String base = (relay.tls() ? "tls" : "plain") + "\n"
                + relay.host().toLowerCase(Locale.US) + "\n"
                + relay.port() + "\n" + relay.path() + "\n" + sha256Hex(relay.token());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(base.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("relay_");
            for (int i = 0; i < 12; i++) out.append(String.format(Locale.US, "%02x", digest[i]));
            return out.toString();
        } catch (Exception ignored) {
            return "relay_" + Integer.toHexString(base.hashCode());
        }
    }

    private String existingEndpointId(VpsRelayConfig relay) {
        for (Record record : relays.values()) {
            if (record.config().sameEndpoint(relay)
                    && record.config().token().equals(relay.token())) return record.id();
        }
        return "";
    }

    private String uniqueName(String requested, String exceptId) {
        String base = normalize(requested);
        if (base.isEmpty()) base = "VPS Relay";
        if (!nameExists(base, exceptId)) return base;
        int suffix = 2;
        while (nameExists(base + " " + suffix, exceptId)) suffix++;
        return base + " " + suffix;
    }

    private boolean nameExists(String name, String exceptId) {
        for (Record record : relays.values()) {
            if (record.id().equals(exceptId)) continue;
            if (record.config().name().equalsIgnoreCase(name)) return true;
        }
        return false;
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
                    .append(encoded(c.token())).append('\t')
                    .append(encoded(c.capabilities().toStored()));
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
                        "").withCapabilities(parts.length >= 9
                                ? VpsRelayCapabilities.fromStored(decoded(parts[8]))
                                : VpsRelayCapabilities.unknown());
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
        public boolean putString(String key, String value) {
            return prefs != null && prefs.edit().putString(
                    key, value == null ? "" : value).commit();
        }

        @Override
        public boolean putStrings(Map<String, String> values) {
            if (prefs == null || values == null || values.isEmpty()) return false;
            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
            return editor.commit();
        }

        @Override
        public boolean putStringsInto(SharedPreferences.Editor editor,
                                      Map<String, String> values) {
            if (editor == null || values == null || values.isEmpty()) return false;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
            return true;
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
        public boolean putString(String key, String value) {
            values.put(key, value == null ? "" : value);
            return true;
        }

        @Override
        public boolean putStrings(Map<String, String> updates) {
            if (updates == null) return false;
            for (Map.Entry<String, String> entry : updates.entrySet()) {
                putString(entry.getKey(), entry.getValue());
            }
            return true;
        }

        @Override
        public boolean putStringsInto(SharedPreferences.Editor editor,
                                      Map<String, String> updates) {
            return putStrings(updates);
        }
    }

    private static final class SecureSharedPreferencesKeyValueStore implements KeyValueStore {
        private final SecureValueStore secure;

        SecureSharedPreferencesKeyValueStore(Context context, SharedPreferences prefs) {
            secure = new SecureValueStore(context, prefs);
        }

        @Override public String getString(String key, String fallback) {
            return secure.get(key, fallback);
        }

        @Override public boolean putString(String key, String value) {
            return secure.put(key, value);
        }

        @Override public boolean putStrings(Map<String, String> values) {
            SharedPreferences prefs = secure.preferences();
            if (prefs == null) return false;
            try {
                LinkedHashMap<String, String> encrypted = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    encrypted.put(entry.getKey(), secure.encryptStored(
                            entry.getKey(), entry.getValue()));
                }
                SharedPreferences.Editor editor = prefs.edit();
                for (Map.Entry<String, String> entry : encrypted.entrySet()) {
                    editor.putString(entry.getKey(), entry.getValue());
                }
                return editor.commit();
            } catch (Exception error) {
                DiagnosticsLog.record("secure Relay store write failed "
                        + error.getClass().getSimpleName());
                return false;
            }
        }

        @Override public boolean putStringsInto(SharedPreferences.Editor editor,
                                                Map<String, String> values) {
            if (editor == null || values == null || values.isEmpty()) return false;
            try {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    editor.putString(entry.getKey(), secure.encryptStored(
                            entry.getKey(), entry.getValue()));
                }
                return true;
            } catch (Exception error) {
                DiagnosticsLog.record("secure Relay transaction staging failed "
                        + error.getClass().getSimpleName());
                return false;
            }
        }
    }

    private String specificRelayId(String profileKey) {
        String key = normalize(profileKey);
        if (key.isEmpty()) return null;
        return profileBindings.get(key);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value == null ? 0 : value.hashCode());
        }
    }
}
