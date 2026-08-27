package com.dushnyj.tgproxy;

import android.content.SharedPreferences;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class NetworkProfileStore {
    static final String KEY_PROFILES = "network_profiles.v1";
    private static final String KEY_STATS_PREFIX = "network_profile_stats.v1.";

    interface KeyValueStore {
        String getString(String key, String fallback);
        void putString(String key, String value);
        void removeString(String key);
    }

    private final KeyValueStore keyValueStore;
    private final LinkedHashMap<String, NetworkProfileRecord> profiles = new LinkedHashMap<>();

    private NetworkProfileStore(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
        profiles.putAll(parseProfiles(keyValueStore.getString(KEY_PROFILES, "")));
        if (cleanupProfiles()) persistProfiles();
    }

    static NetworkProfileStore fromPreferences(SharedPreferences prefs) {
        return new NetworkProfileStore(new SharedPreferencesKeyValueStore(prefs));
    }

    static NetworkProfileStore inMemory() {
        return new NetworkProfileStore(new MemoryKeyValueStore());
    }

    static NetworkProfileStore inMemory(String persistedProfiles) {
        MemoryKeyValueStore store = new MemoryKeyValueStore();
        store.putString(KEY_PROFILES, persistedProfiles);
        return new NetworkProfileStore(store);
    }

    synchronized NetworkProfileRecord ensureProfile(NetworkProfile profile, long nowMs) {
        NetworkProfile normalized = profile == null ? NetworkProfile.defaultProfile() : profile;
        mergeLegacyProfilesInto(normalized);
        NetworkProfileRecord existing = profiles.get(normalized.key());
        NetworkProfileRecord next = existing == null
                ? NetworkProfileRecord.create(normalized, nowMs)
                : existing.seen(nowMs);
        profiles.put(next.key(), next);
        persistProfiles();
        return next;
    }

    /**
     * Returns the persisted profile without recording another network activation.
     *
     * UI refreshes use this method because {@link #ensureProfile(NetworkProfile, long)} also
     * advances lastSeen/seenCount and writes preferences. Calling ensureProfile from a periodic
     * renderer would therefore turn every repaint into a runtime-configuration change.
     */
    synchronized NetworkProfileRecord profileOrCreate(NetworkProfile profile, long nowMs) {
        NetworkProfile normalized = profile == null ? NetworkProfile.defaultProfile() : profile;
        mergeLegacyProfilesInto(normalized);
        NetworkProfileRecord existing = profiles.get(normalized.key());
        if (existing != null) return existing;
        NetworkProfileRecord created = NetworkProfileRecord.create(normalized, nowMs);
        profiles.put(created.key(), created);
        persistProfiles();
        return created;
    }

    synchronized NetworkProfileRecord profile(String key) {
        return profiles.get(key);
    }

    synchronized NetworkProfileRecord createManualProfile(String name, long nowMs) {
        String id = "manual_" + Math.max(1L, nowMs);
        while (profiles.containsKey(NetworkProfile.manual(id, name).key())) {
            id = id + "_1";
        }
        NetworkProfileRecord record = NetworkProfileRecord.create(NetworkProfile.manual(id, name), nowMs);
        profiles.put(record.key(), record);
        persistProfiles();
        return record;
    }

    synchronized void renameProfile(String key, String name) {
        NetworkProfileRecord existing = profiles.get(key);
        if (existing == null) return;
        profiles.put(key, existing.renamed(name));
        persistProfiles();
    }

    synchronized void setRoutePreference(String key, RoutePreference preference) {
        NetworkProfileRecord existing = profiles.get(key);
        if (existing == null) return;
        profiles.put(key, existing.withRoutePreference(preference));
        persistProfiles();
    }

    synchronized boolean deleteProfile(String key) {
        if (key == null || key.trim().isEmpty()) return false;
        NetworkProfileRecord removed = profiles.remove(key);
        if (removed == null) return false;
        keyValueStore.removeString(statsKeyForProfileKey(key));
        persistProfiles();
        return true;
    }

    synchronized String exportProfiles() {
        return serializeProfiles(profiles);
    }

    synchronized Map<String, NetworkProfileRecord> profilesSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    synchronized Map<String, RouteStats> routeStats(NetworkProfile profile) {
        return routeStats(profile, System.currentTimeMillis());
    }

    synchronized Map<String, RouteStats> routeStats(NetworkProfile profile, long nowMs) {
        LinkedHashMap<String, RouteStats> result = parseRouteStats(
                keyValueStore.getString(statsKey(profile), ""));
        boolean changed = false;
        for (RouteStats stats : result.values()) {
            if (stats.pruneExpired(nowMs)) changed = true;
        }
        if (changed) saveRouteStats(profile, result);
        return result;
    }

    synchronized void saveRouteStats(NetworkProfile profile, String routeKey, RouteStats stats) {
        LinkedHashMap<String, RouteStats> all = new LinkedHashMap<>(routeStats(profile));
        all.put(routeKey, stats == null ? new RouteStats() : stats.copy());
        saveRouteStats(profile, all);
    }

    synchronized void saveRouteStats(NetworkProfile profile, Map<String, RouteStats> statsByRoute) {
        keyValueStore.putString(statsKey(profile), serializeRouteStats(statsByRoute));
    }

    private void persistProfiles() {
        keyValueStore.putString(KEY_PROFILES, serializeProfiles(profiles));
    }

    private static String statsKey(NetworkProfile profile) {
        NetworkProfile normalized = profile == null ? NetworkProfile.defaultProfile() : profile;
        return statsKeyForProfileKey(normalized.key());
    }

    static String statsKeyForProfileKey(String profileKey) {
        return KEY_STATS_PREFIX + encoded(profileKey);
    }

    private static String serializeProfiles(Map<String, NetworkProfileRecord> profiles) {
        StringBuilder out = new StringBuilder();
        for (NetworkProfileRecord record : profiles.values()) {
            if (out.length() > 0) out.append('\n');
            out.append(encoded(record.key())).append('\t')
                    .append(record.profile().kind().name()).append('\t')
                    .append(encoded(record.profile().id())).append('\t')
                    .append(encoded(record.displayName())).append('\t')
                    .append(record.routePreference().name()).append('\t')
                    .append(record.createdMs()).append('\t')
                    .append(record.lastSeenMs()).append('\t')
                    .append(record.seenCount());
        }
        return out.toString();
    }

    private static LinkedHashMap<String, NetworkProfileRecord> parseProfiles(String raw) {
        LinkedHashMap<String, NetworkProfileRecord> result = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        for (String line : raw.split("\\n")) {
            String[] parts = line.split("\\t", -1);
            if (parts.length < 8) continue;
            try {
                NetworkProfile.Kind kind = NetworkProfile.Kind.valueOf(parts[1]);
                NetworkProfile profile = profile(kind, decoded(parts[2]), decoded(parts[3]));
                if (profile == null) continue;
                NetworkProfileRecord record = new NetworkProfileRecord(
                        profile,
                        decoded(parts[3]),
                        RoutePreference.fromStored(parts[4]),
                        longValue(parts[5]),
                        longValue(parts[6]),
                        intValue(parts[7]));
                result.put(record.key(), record);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static String serializeRouteStats(Map<String, RouteStats> statsByRoute) {
        if (statsByRoute == null || statsByRoute.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, RouteStats> entry : statsByRoute.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            RouteStats stats = entry.getValue() == null ? new RouteStats() : entry.getValue();
            out.append(encoded(entry.getKey())).append('\t')
                    .append(encoded(stats.toPersistedString()));
        }
        return out.toString();
    }

    private static LinkedHashMap<String, RouteStats> parseRouteStats(String raw) {
        LinkedHashMap<String, RouteStats> result = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        for (String line : raw.split("\\n")) {
            String[] parts = line.split("\\t", -1);
            if (parts.length < 2) continue;
            String key = decoded(parts[0]);
            if (key.isEmpty()) continue;
            result.put(key, RouteStats.fromPersistedString(decoded(parts[1])));
        }
        return result;
    }

    private void mergeLegacyProfilesInto(NetworkProfile target) {
        if (target == null || target.kind() == NetworkProfile.Kind.DEFAULT) return;
        ArrayList<String> keys = legacyKeysFor(target);
        NetworkProfileRecord current = profiles.get(target.key());
        boolean changed = false;
        for (String legacyKey : keys) {
            if (legacyKey == null || legacyKey.equals(target.key())) continue;
            NetworkProfileRecord legacy = profiles.remove(legacyKey);
            if (legacy == null) continue;
            current = mergeRecords(target, current, legacy);
            mergeRouteStats(legacyKey, target.key());
            changed = true;
        }
        if (current != null) profiles.put(target.key(), current);
        if (cleanupProfiles()) changed = true;
        if (changed) persistProfiles();
    }

    private boolean cleanupProfiles() {
        boolean hasStableWifi = false;
        for (NetworkProfileRecord record : profiles.values()) {
            if (record.profile().isWifi() && !record.profile().isHiddenWifi()) {
                hasStableWifi = true;
                break;
            }
        }

        boolean changed = false;
        ArrayList<String> removeKeys = new ArrayList<>();
        for (NetworkProfileRecord record : profiles.values()) {
            if (record.profile().isWifi()
                    && record.profile().isHiddenWifi()
                    && hasStableWifi
                    && isLegacyHiddenWifiDisplayName(record.displayName())) {
                removeKeys.add(record.key());
                continue;
            }
            if (isLegacyGeneratedWifiDisplayName(record.displayName())) {
                profiles.put(record.key(), record.renamed(""));
                changed = true;
            }
        }
        for (String key : removeKeys) {
            profiles.remove(key);
            keyValueStore.removeString(statsKeyForProfileKey(key));
            changed = true;
        }
        return changed;
    }

    private static boolean isLegacyGatewayDisplayName(String name) {
        String value = name == null ? "" : name.trim();
        return value.startsWith("Wi-Fi 192.168.")
                || value.startsWith("Wi-Fi 10.")
                || value.startsWith("Wi-Fi 172.");
    }

    private static boolean isLegacyGeneratedWifiDisplayName(String name) {
        if (NetworkProfileRecord.isLegacyGeneratedWifiLabel(name)) return true;
        if (isLegacyGatewayDisplayName(name)) return true;
        String value = name == null ? "" : name.trim().toLowerCase(Locale.US);
        value = value.replace('\u2013', '-').replace('\u2014', '-');
        return (value.contains("wifi") || value.contains("wi-fi"))
                && (value.contains("auto") || value.contains("авто"));
    }

    private static boolean isLegacyHiddenWifiDisplayName(String name) {
        if (isLegacyGeneratedWifiDisplayName(name)) return true;
        String value = name == null ? "" : name.trim().toLowerCase(Locale.US);
        value = value.replace('\u2010', '-')
                .replace('\u2011', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-');
        return "wifi".equals(value)
                || "wi-fi".equals(value)
                || "wi fi".equals(value)
                || "wi-fi (имя недоступно)".equals(value)
                || "wi-fi (имя скрыто)".equals(value)
                || "wi-fi (ssid unavailable)".equals(value)
                || "wi-fi (ssid hidden)".equals(value);
    }

    private void mergeRouteStats(String oldProfileKey, String newProfileKey) {
        String oldStatsKey = statsKeyForProfileKey(oldProfileKey);
        String newStatsKey = statsKeyForProfileKey(newProfileKey);
        LinkedHashMap<String, RouteStats> oldStats =
                parseRouteStats(keyValueStore.getString(oldStatsKey, ""));
        if (!oldStats.isEmpty()) {
            LinkedHashMap<String, RouteStats> merged =
                    parseRouteStats(keyValueStore.getString(newStatsKey, ""));
            for (Map.Entry<String, RouteStats> entry : oldStats.entrySet()) {
                if (!merged.containsKey(entry.getKey())) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
            keyValueStore.putString(newStatsKey, serializeRouteStats(merged));
        }
        keyValueStore.removeString(oldStatsKey);
    }

    private static NetworkProfileRecord mergeRecords(NetworkProfile target,
                                                     NetworkProfileRecord current,
                                                     NetworkProfileRecord legacy) {
        if (legacy == null) return current;
        if (current == null) {
            return new NetworkProfileRecord(target, legacy.displayName(),
                    legacy.routePreference(), legacy.createdMs(),
                    legacy.lastSeenMs(), legacy.seenCount());
        }
        RoutePreference preference = current.routePreference();
        if (preference == RoutePreference.AUTO
                || (legacy.routePreference() != RoutePreference.AUTO
                && legacy.seenCount() > current.seenCount())) {
            preference = legacy.routePreference();
        }
        long created = Math.min(nonZero(current.createdMs(), legacy.createdMs()),
                nonZero(legacy.createdMs(), current.createdMs()));
        long lastSeen = Math.max(current.lastSeenMs(), legacy.lastSeenMs());
        return new NetworkProfileRecord(target, current.displayName(), preference,
                created, lastSeen, current.seenCount() + legacy.seenCount());
    }

    private static long nonZero(long value, long fallback) {
        return value <= 0L ? fallback : value;
    }

    private static ArrayList<String> legacyKeysFor(NetworkProfile profile) {
        ArrayList<String> keys = new ArrayList<>();
        keys.add(profile.legacyKey());
        keys.add(profile.key());
        if (profile.isWifi()) {
            keys.add("wifi:" + profile.id());
            if (profile.isHiddenWifi()) {
                keys.add("wifi:default_wifi");
                keys.add("wifi:unknown_ssid");
                keys.add("wifi:hidden");
            }
        } else if (profile.isMobile()) {
            keys.add("mobile:" + profile.id());
            keys.add("mobile:name:" + profile.id());
            String labelId = NetworkProfileIdentifier.normalizeMobileId(profile.defaultDisplayName());
            if (!labelId.isEmpty()) {
                keys.add("mobile:" + labelId);
                keys.add("mobile:name:" + labelId);
                if (labelId.contains("tele2") || labelId.startsWith("t2") || labelId.contains("_t2")) {
                    keys.add("mobile:tele2");
                    keys.add("mobile:name:tele2");
                    keys.add("mobile:t2_black");
                    keys.add("mobile:name:t2_black");
                    keys.add("mobile:tele2_russia");
                    keys.add("mobile:name:tele2_russia");
                }
            }
        }
        return keys;
    }

    private static NetworkProfile profile(NetworkProfile.Kind kind, String id, String displayName) {
        if (kind == NetworkProfile.Kind.WIFI) {
            if (id != null && id.startsWith("gw_")) return null;
            return NetworkProfile.wifi(id);
        }
        if (kind == NetworkProfile.Kind.MOBILE) return NetworkProfile.mobile(id);
        if (kind == NetworkProfile.Kind.MANUAL) return NetworkProfile.manual(id, displayName);
        return NetworkProfile.defaultProfile();
    }

    private static long longValue(String value) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return 0L; }
    }

    private static int intValue(String value) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return 0; }
    }

    private static String encoded(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }

    private static String decoded(String value) {
        try { return URLDecoder.decode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
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
            if (prefs != null) prefs.edit().putString(key, value).commit();
        }

        @Override
        public void removeString(String key) {
            if (prefs != null) prefs.edit().remove(key).commit();
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

        @Override
        public void removeString(String key) {
            values.remove(key);
        }
    }
}
