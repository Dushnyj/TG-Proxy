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
    static final String KEY_PROFILE_POOLS = "vps_relay_profile_pools.v2";
    private static final String GLOBAL_PROFILE = "*";
    /** Explicit profile override meaning that no Relay is selected for this profile. */
    private static final String NO_RELAY = "!";
    private static final String POOL_VERSION = "v2";
    private static final Object CONTEXT_STORE_LOCK = new Object();
    private static VpsRelayStore contextStore;

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
    private final LinkedHashMap<String, ArrayList<String>> profilePools = new LinkedHashMap<>();

    VpsRelayStore(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
        reloadFromStore();
    }

    private synchronized void reloadFromStore() {
        relays.clear();
        profileBindings.clear();
        profilePools.clear();
        relays.putAll(parseRelays(keyValueStore.getString(KEY_RELAYS, "")));
        profileBindings.putAll(parseBindings(keyValueStore.getString(KEY_PROFILE_BINDINGS, "")));
        String rawPools = keyValueStore.getString(KEY_PROFILE_POOLS, "");
        if (rawPools.startsWith(POOL_VERSION)) {
            profilePools.putAll(parsePools(rawPools));
        } else {
            migrateLegacyPools();
        }
        cleanupBindings();
    }

    static VpsRelayStore fromPreferences(SharedPreferences prefs) {
        return new VpsRelayStore(new SharedPreferencesKeyValueStore(prefs));
    }

    static VpsRelayStore fromContext(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        SharedPreferences prefs = app == null ? null
                : PreferenceManager.getDefaultSharedPreferences(app);
        synchronized (CONTEXT_STORE_LOCK) {
            if (contextStore == null) {
                contextStore = new VpsRelayStore(new SecureSharedPreferencesKeyValueStore(app, prefs));
            } else {
                // All Android callers share one synchronized in-process snapshot. Reloading on
                // acquisition also observes preference restoration/test cleanup and prevents a
                // stale Activity or Service instance from overwriting a newer Relay/token list.
                contextStore.reloadFromStore();
            }
            return contextStore;
        }
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
        LinkedHashMap<String, ArrayList<String>> previousPools = copyPools(profilePools);
        String id = existingEndpointId(relay);
        boolean isNew = id.isEmpty();
        if (isNew) id = idFor(relay);
        VpsRelayConfig stored = relay.withProfileKey("")
                .withName(uniqueName(relay.name(), isNew ? "" : id));
        Record record = new Record(id, stored);
        relays.put(id, record);
        String key = normalize(profileKey);
        if (relay.hasValidConnection() && relay.isEnabled()) {
            addToPool(key, id);
            profileBindings.put(bindingKey(key), id);
        }
        if (editor == null) {
            if (!persist()) {
                relays.clear();
                relays.putAll(previousRelays);
                profileBindings.clear();
                profileBindings.putAll(previousBindings);
                restorePools(previousPools);
                return null;
            }
        } else if (!writeAll(editor)) {
            relays.clear();
            relays.putAll(previousRelays);
            profileBindings.clear();
            profileBindings.putAll(previousBindings);
            restorePools(previousPools);
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
        LinkedHashMap<String, ArrayList<String>> previousPools = copyPools(profilePools);
        VpsRelayConfig stored = relay.withProfileKey("")
                .withName(uniqueName(relay.name(), id));
        Record record = new Record(id, stored);
        relays.put(id, record);
        if (relay.hasValidConnection() && relay.isEnabled()) {
            addToPool(profileKey, id);
            profileBindings.put(bindingKey(profileKey), id);
        }
        boolean written = editor == null ? persist() : writeAll(editor);
        if (!written) {
            relays.clear();
            relays.putAll(previousRelays);
            profileBindings.clear();
            profileBindings.putAll(previousBindings);
            restorePools(previousPools);
            return null;
        }
        return record;
    }

    synchronized Record updateRelayMetadata(String relayId, VpsRelayConfig relay) {
        String id = normalize(relayId);
        Record current = relays.get(id);
        if (current == null || relay == null || !relay.hasValidConnection()) return null;
        LinkedHashMap<String, Record> previous = new LinkedHashMap<>(relays);
        VpsRelayConfig stored = relay.withProfileKey("")
                .withName(uniqueName(relay.name(), id));
        Record updated = new Record(id, stored);
        relays.put(id, updated);
        if (persist()) return updated;
        relays.clear();
        relays.putAll(previous);
        return null;
    }

    /**
     * Updates an existing local connection without changing any profile policy. Editing a label,
     * endpoint or token must not silently promote the connection or enable it in other networks.
     */
    synchronized Record updateConnection(String relayId, VpsRelayConfig relay) {
        String id = normalize(relayId);
        Record current = relays.get(id);
        if (current == null || relay == null || !relay.hasValidConnection()) return null;
        LinkedHashMap<String, Record> previous = new LinkedHashMap<>(relays);
        VpsRelayConfig stored = relay.withProfileKey("")
                .withEnabled(current.config().isEnabled())
                .withName(uniqueName(relay.name(), id));
        Record updated = new Record(id, stored);
        relays.put(id, updated);
        if (persist()) return updated;
        relays.clear();
        relays.putAll(previous);
        return null;
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
        LinkedHashMap<String, ArrayList<String>> previousPools = copyPools(profilePools);
        relays.remove(id);
        removeFromAllPools(id);
        repairBindings(id);
        if (editor == null) {
            if (!persist()) {
                relays.clear();
                relays.putAll(previousRelays);
                profileBindings.clear();
                profileBindings.putAll(previousBindings);
                restorePools(previousPools);
                return false;
            }
        } else if (!writeAll(editor)) {
            relays.clear();
            relays.putAll(previousRelays);
            profileBindings.clear();
            profileBindings.putAll(previousBindings);
            restorePools(previousPools);
            return false;
        }
        return true;
    }

    synchronized boolean setRelayEnabled(String relayId, boolean enabled) {
        String id = normalize(relayId);
        Record current = relays.get(id);
        if (current == null || !current.config().hasValidConnection()) return false;
        LinkedHashMap<String, Record> previousRelays = new LinkedHashMap<>(relays);
        LinkedHashMap<String, String> previousBindings = new LinkedHashMap<>(profileBindings);
        LinkedHashMap<String, ArrayList<String>> previousPools = copyPools(profilePools);
        relays.put(id, new Record(id, current.config().withEnabled(enabled)));
        if (enabled) {
            addToPool("", id);
        } else {
            removeFromAllPools(id);
            repairBindings(id);
        }
        if (persist()) return true;
        relays.clear();
        relays.putAll(previousRelays);
        profileBindings.clear();
        profileBindings.putAll(previousBindings);
        restorePools(previousPools);
        return false;
    }

    synchronized boolean setRelayEnabledForProfile(String profileKey, String relayId,
                                                    boolean enabled) {
        String id = normalize(relayId);
        Record current = relays.get(id);
        if (current == null || !current.config().hasValidConnection()) return false;
        LinkedHashMap<String, String> previousBindings = new LinkedHashMap<>(profileBindings);
        LinkedHashMap<String, ArrayList<String>> previousPools = copyPools(profilePools);
        String key = bindingKey(profileKey);
        String inheritedPrimary = selectedRelayId(profileKey);
        boolean inheritedPolicy = !GLOBAL_PROFILE.equals(key)
                && specificRelayId(normalize(profileKey)) == null;
        boolean wasEffectivePrimary = id.equals(selectedRelayId(profileKey));
        if (enabled) {
            addToPool(profileKey, id);
            if (inheritedPolicy && inheritedPrimary != null && !inheritedPrimary.isEmpty()) {
                profileBindings.put(key, inheritedPrimary);
            } else if (selectedRelayId(profileKey) == null || selectedRelayId(profileKey).isEmpty()) {
                profileBindings.put(key, id);
            }
        } else {
            removeFromPool(profileKey, id);
            if (wasEffectivePrimary || id.equals(profileBindings.get(key))) {
                repairBindingForProfile(key, id);
            }
        }
        if (persist()) return true;
        profileBindings.clear();
        profileBindings.putAll(previousBindings);
        restorePools(previousPools);
        return false;
    }

    synchronized boolean relayEnabledForProfile(String profileKey, String relayId) {
        return poolFor(profileKey).contains(normalize(relayId));
    }

    synchronized boolean makePrimary(String profileKey, String relayId) {
        String id = normalize(relayId);
        Record current = relays.get(id);
        if (current == null || !current.config().hasValidConnection()) return false;
        LinkedHashMap<String, Record> previousRelays = new LinkedHashMap<>(relays);
        LinkedHashMap<String, String> previousBindings = new LinkedHashMap<>(profileBindings);
        LinkedHashMap<String, ArrayList<String>> previousPools = copyPools(profilePools);
        relays.put(id, new Record(id, current.config().withEnabled(true)));
        addToPool(profileKey, id);
        profileBindings.put(bindingKey(profileKey), id);
        if (persist()) return true;
        relays.clear();
        relays.putAll(previousRelays);
        profileBindings.clear();
        profileBindings.putAll(previousBindings);
        restorePools(previousPools);
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
        LinkedHashMap<String, ArrayList<String>> previousPools = copyPools(profilePools);
        for (String id : ids) {
            relays.remove(id);
            removeFromAllPools(id);
            repairBindings(id);
        }
        if (persist()) return true;
        relays.clear();
        relays.putAll(previousRelays);
        profileBindings.clear();
        profileBindings.putAll(previousBindings);
        restorePools(previousPools);
        return false;
    }

    synchronized boolean bindProfile(String profileKey, String relayId) {
        return bindProfileInto(profileKey, relayId, null);
    }

    synchronized boolean bindProfileInto(String profileKey, String relayId,
                                         SharedPreferences.Editor editor) {
        LinkedHashMap<String, String> previousBindings = new LinkedHashMap<>(profileBindings);
        LinkedHashMap<String, ArrayList<String>> previousPools = copyPools(profilePools);
        String key = normalize(profileKey);
        String id = normalize(relayId);
        String bindingKey = bindingKey(key);
        if (id.isEmpty() || !relays.containsKey(id)) profileBindings.remove(bindingKey);
        else {
            addToPool(profileKey, id);
            profileBindings.put(bindingKey, id);
        }
        if (editor == null) {
            if (!persistBindings()) {
                profileBindings.clear();
                profileBindings.putAll(previousBindings);
                restorePools(previousPools);
                return false;
            }
        } else {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            values.put(KEY_PROFILE_BINDINGS, serializeBindings(profileBindings));
            values.put(KEY_PROFILE_POOLS, serializePools(profilePools));
            if (!keyValueStore.putStringsInto(editor, values)) {
                profileBindings.clear();
                profileBindings.putAll(previousBindings);
                restorePools(previousPools);
                return false;
            }
        }
        return true;
    }

    synchronized String selectedRelayId(String profileKey) {
        String key = normalize(profileKey);
        String selected = specificRelayId(key);
        selected = selected == null ? profileBindings.get(GLOBAL_PROFILE) : selected;
        return NO_RELAY.equals(selected) ? "" : selected;
    }

    synchronized VpsRelayConfig selectedRelay(String profileKey) {
        String key = normalize(profileKey);
        String relayId = specificRelayId(key);
        boolean profileSpecific = relayId != null;
        if (relayId == null) relayId = profileBindings.get(GLOBAL_PROFILE);
        Record record = relayId == null ? null : relays.get(relayId);
        if (record == null) return null;
        return record.config().withEnabled(relayEnabledForProfile(profileKey, relayId))
                .withProfileKey(profileSpecific ? key : "");
    }

    synchronized Record relay(String relayId) {
        return relays.get(normalize(relayId));
    }

    synchronized List<Record> relays() {
        return Collections.unmodifiableList(new ArrayList<>(relays.values()));
    }

    /** Primary Relay followed by this profile's explicitly enabled automatic fallbacks. */
    synchronized List<VpsRelayConfig> relayPool(String profileKey) {
        String selectedId = selectedRelayId(profileKey);
        if (selectedId == null || selectedId.isEmpty()) return Collections.emptyList();
        ArrayList<VpsRelayConfig> result = new ArrayList<>();
        Record primary = relays.get(selectedId);
        if (primary != null && primary.config().hasValidConnection()) {
            result.add(primary.config().withEnabled(true).withProfileKey(normalize(profileKey)));
        }
        for (String id : poolFor(profileKey)) {
            if (id.equals(selectedId)) continue;
            Record record = relays.get(id);
            if (record == null || !record.config().hasValidConnection()) continue;
            result.add(record.config().withEnabled(true).withProfileKey(normalize(profileKey)));
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
            if (!NO_RELAY.equals(entry.getValue()) && !relays.containsKey(entry.getValue())) {
                remove.add(entry.getKey());
            }
        }
        for (String key : remove) profileBindings.remove(key);
        boolean poolChanged = cleanupPools();
        if (!remove.isEmpty() || poolChanged) persist();
    }

    private void migrateLegacyPools() {
        ArrayList<String> global = new ArrayList<>();
        for (Record record : relays.values()) {
            if (record.config().isUsable()) global.add(record.id());
        }
        if (!global.isEmpty()) profilePools.put(GLOBAL_PROFILE, global);
        for (Map.Entry<String, String> binding : profileBindings.entrySet()) {
            String id = binding.getValue();
            if (!relays.containsKey(id)) continue;
            ArrayList<String> pool = profilePools.get(binding.getKey());
            if (pool == null) {
                pool = new ArrayList<>(global);
                profilePools.put(binding.getKey(), pool);
            }
            if (!pool.contains(id)) pool.add(0, id);
        }
        // The next atomic Relay write stores the v2 marker too. Avoiding a constructor-time
        // write keeps reads side-effect free and prevents a half-migrated state on failure.
    }

    private boolean cleanupPools() {
        boolean changed = false;
        for (Map.Entry<String, ArrayList<String>> entry : profilePools.entrySet()) {
            ArrayList<String> ids = entry.getValue();
            for (int index = ids.size() - 1; index >= 0; index--) {
                if (!relays.containsKey(ids.get(index))) {
                    ids.remove(index);
                    changed = true;
                }
            }
        }
        // Keep explicit empty profile pools. They mean "no Relay on this network" and must
        // not silently inherit the global fallback list.
        return changed;
    }

    private void addToPool(String profileKey, String relayId) {
        String key = bindingKey(profileKey);
        ArrayList<String> pool = profilePools.get(key);
        if (pool == null) {
            // The first profile-specific change creates an explicit copy of the currently
            // inherited global policy. Otherwise enabling one additional backup would silently
            // discard every inherited primary/fallback for this network.
            ArrayList<String> global = profilePools.get(GLOBAL_PROFILE);
            pool = !GLOBAL_PROFILE.equals(key) && global != null
                    ? new ArrayList<>(global) : new ArrayList<>();
            profilePools.put(key, pool);
        }
        if (!pool.contains(relayId)) pool.add(relayId);
    }

    private void removeFromPool(String profileKey, String relayId) {
        String key = bindingKey(profileKey);
        ArrayList<String> pool = profilePools.get(key);
        if (pool == null) {
            // Creating an empty explicit pool prevents an inherited global connection from
            // being re-enabled for this network after the user turned it off.
            pool = new ArrayList<>(poolFor(profileKey));
            profilePools.put(key, pool);
        }
        pool.remove(relayId);
    }

    private void removeFromAllPools(String relayId) {
        for (ArrayList<String> pool : profilePools.values()) pool.remove(relayId);
    }

    private List<String> poolFor(String profileKey) {
        String key = bindingKey(profileKey);
        ArrayList<String> exact = profilePools.get(key);
        if (exact != null) return exact;
        ArrayList<String> global = profilePools.get(GLOBAL_PROFILE);
        return global == null ? Collections.emptyList() : global;
    }

    private List<String> poolForBindingKey(String key) {
        ArrayList<String> exact = profilePools.get(key);
        if (exact != null) return exact;
        ArrayList<String> global = profilePools.get(GLOBAL_PROFILE);
        return global == null ? Collections.emptyList() : global;
    }

    private static LinkedHashMap<String, ArrayList<String>> copyPools(
            Map<String, ArrayList<String>> source) {
        LinkedHashMap<String, ArrayList<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ArrayList<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    private void restorePools(Map<String, ArrayList<String>> snapshot) {
        profilePools.clear();
        for (Map.Entry<String, ArrayList<String>> entry : snapshot.entrySet()) {
            profilePools.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
    }

    private boolean persist() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(KEY_RELAYS, serializeRelays(relays));
        values.put(KEY_PROFILE_BINDINGS, serializeBindings(profileBindings));
        values.put(KEY_PROFILE_POOLS, serializePools(profilePools));
        return keyValueStore.putStrings(values);
    }

    private void repairBindings(String unavailableId) {
        ArrayList<String> affected = new ArrayList<>();
        for (Map.Entry<String, String> entry : profileBindings.entrySet()) {
            if (unavailableId.equals(entry.getValue())) affected.add(entry.getKey());
        }
        for (String key : affected) {
            repairBindingForProfile(key, unavailableId);
        }
    }

    private void repairBindingForProfile(String key, String exceptId) {
        for (String candidate : poolForBindingKey(key)) {
            Record record = relays.get(candidate);
            if (!candidate.equals(exceptId) && record != null
                    && record.config().hasValidConnection()) {
                profileBindings.put(key, candidate);
                return;
            }
        }
        // Keep an explicit empty override. Removing the key here would immediately inherit the
        // global primary again, so a user could never disable Relay for only one network profile.
        profileBindings.put(key, NO_RELAY);
    }

    private boolean persistBindings() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(KEY_PROFILE_BINDINGS, serializeBindings(profileBindings));
        values.put(KEY_PROFILE_POOLS, serializePools(profilePools));
        return keyValueStore.putStrings(values);
    }

    private boolean writeAll(SharedPreferences.Editor editor) {
        if (editor == null) return false;
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(KEY_RELAYS, serializeRelays(relays));
        values.put(KEY_PROFILE_BINDINGS, serializeBindings(profileBindings));
        values.put(KEY_PROFILE_POOLS, serializePools(profilePools));
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
                    .append(encoded(c.capabilities().toStored())).append('\t')
                    .append(encoded(c.instanceId()));
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
                                : VpsRelayCapabilities.unknown())
                        .withInstanceId(parts.length >= 10 ? decoded(parts[9]) : "");
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

    private static String serializePools(Map<String, ArrayList<String>> source) {
        StringBuilder out = new StringBuilder(POOL_VERSION);
        for (Map.Entry<String, ArrayList<String>> entry : source.entrySet()) {
            out.append('\n').append(encoded(entry.getKey()));
            for (String relayId : entry.getValue()) out.append('\t').append(encoded(relayId));
        }
        return out.toString();
    }

    private static LinkedHashMap<String, ArrayList<String>> parsePools(String raw) {
        LinkedHashMap<String, ArrayList<String>> result = new LinkedHashMap<>();
        if (raw == null || !raw.startsWith(POOL_VERSION)) return result;
        String[] lines = raw.split("\\n", -1);
        for (int lineIndex = 1; lineIndex < lines.length; lineIndex++) {
            String[] parts = lines[lineIndex].split("\\t", -1);
            if (parts.length == 0) continue;
            String profile = decoded(parts[0]);
            if (profile.isEmpty()) continue;
            ArrayList<String> ids = new ArrayList<>();
            for (int index = 1; index < parts.length; index++) {
                String id = decoded(parts[index]);
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            }
            result.put(profile, ids);
        }
        return result;
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
        String selected = profileBindings.get(key);
        return NO_RELAY.equals(selected) ? "" : selected;
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
