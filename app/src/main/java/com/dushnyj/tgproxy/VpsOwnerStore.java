package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

final class VpsOwnerStore {
    static final String KEY_OWNERS = "vps_owner_credentials.v1";

    private final SecureValueStore secure;

    VpsOwnerStore(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        SharedPreferences prefs = app == null ? null
                : PreferenceManager.getDefaultSharedPreferences(app);
        secure = new SecureValueStore(app, prefs);
    }

    synchronized List<VpsOwnerRecord> records() {
        return Collections.unmodifiableList(new ArrayList<>(load().values()));
    }

    synchronized VpsOwnerRecord forRelay(VpsRelayConfig relay) {
        if (relay == null) return null;
        LinkedHashMap<String, VpsOwnerRecord> records = load();
        VpsOwnerRecord byId = records.get(VpsOwnerRecord.idFor(relay));
        if (byId != null && byId.matches(relay)) return byId;
        for (VpsOwnerRecord record : records.values()) {
            if (record.matches(relay)) return record;
        }
        return null;
    }

    synchronized VpsOwnerRecord forSsh(VpsSshCredentials credentials) {
        if (credentials == null) return null;
        VpsOwnerRecord newest = null;
        for (VpsOwnerRecord record : load().values()) {
            if (!record.matchesSsh(credentials)) continue;
            if (newest == null || record.updatedAtMs() > newest.updatedAtMs()) newest = record;
        }
        return newest;
    }

    synchronized boolean saveSetup(VpsSetupRequest request, VpsRelayConfig relay) {
        if (request == null || relay == null || !relay.isUsable()
                || request.adminToken().isEmpty()) return false;
        LinkedHashMap<String, VpsOwnerRecord> records = load();
        VpsOwnerRecord next = VpsOwnerRecord.fromSetup(request, relay);
        VpsOwnerRecord previous = records.get(next.id());
        if (previous == null) {
            for (VpsOwnerRecord record : records.values()) {
                if (next.sameSshEndpoint(record)) {
                    previous = record;
                    break;
                }
            }
        }
        next = next.mergedWith(previous);
        // A VPS can legitimately expose the same Relay through several public aliases. Keep
        // the old exact-endpoint owner mapping instead of destructively moving it to the newest
        // endpoint. This also makes a failed second setup unable to erase the first one.
        if (previous != null && !previous.id().equals(next.id())) {
            records.put(previous.id(), previous.withMergedManagedTokens(next));
        }
        records.put(next.id(), next);
        return persist(records);
    }

    synchronized boolean saveManagedToken(VpsRelayConfig relay, String tokenId,
                                          String name, String secret) {
        LinkedHashMap<String, VpsOwnerRecord> records = load();
        VpsOwnerRecord owner = find(records, relay);
        if (owner == null || !owner.canManage()) return false;
        for (VpsOwnerRecord record : new ArrayList<>(records.values())) {
            if (record.id().equals(owner.id()) || record.sameSshEndpoint(owner)) {
                records.put(record.id(), record.withManagedToken(tokenId, name, secret));
            }
        }
        return persist(records);
    }

    synchronized boolean removeManagedToken(VpsRelayConfig relay, String tokenId) {
        LinkedHashMap<String, VpsOwnerRecord> records = load();
        VpsOwnerRecord owner = find(records, relay);
        if (owner == null) return false;
        for (VpsOwnerRecord record : new ArrayList<>(records.values())) {
            if (record.id().equals(owner.id()) || record.sameSshEndpoint(owner)) {
                records.put(record.id(), record.withoutManagedToken(tokenId));
            }
        }
        return persist(records);
    }

    synchronized boolean forget(VpsRelayConfig relay) {
        LinkedHashMap<String, VpsOwnerRecord> records = load();
        VpsOwnerRecord owner = find(records, relay);
        if (owner == null) return false;
        records.remove(owner.id());
        return persist(records);
    }

    private VpsOwnerRecord find(LinkedHashMap<String, VpsOwnerRecord> records,
                                VpsRelayConfig relay) {
        if (relay == null) return null;
        VpsOwnerRecord direct = records.get(VpsOwnerRecord.idFor(relay));
        if (direct != null && direct.matches(relay)) return direct;
        for (VpsOwnerRecord record : records.values()) if (record.matches(relay)) return record;
        return null;
    }

    private LinkedHashMap<String, VpsOwnerRecord> load() {
        LinkedHashMap<String, VpsOwnerRecord> records = new LinkedHashMap<>();
        String raw = secure.get(KEY_OWNERS, "");
        if (raw == null || raw.trim().isEmpty()) return records;
        for (String line : raw.split("\\n", -1)) {
            VpsOwnerRecord record = VpsOwnerRecord.parse(line);
            if (record != null && record.canManage()) records.put(record.id(), record);
        }
        return records;
    }

    private boolean persist(LinkedHashMap<String, VpsOwnerRecord> records) {
        StringBuilder out = new StringBuilder();
        for (VpsOwnerRecord record : records.values()) {
            if (out.length() > 0) out.append('\n');
            out.append(record.serialize());
        }
        return secure.put(KEY_OWNERS, out.toString());
    }
}
