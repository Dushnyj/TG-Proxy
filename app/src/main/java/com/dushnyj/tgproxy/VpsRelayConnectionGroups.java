package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Pure presentation model: one Relay installation, several independent credentials. */
final class VpsRelayConnectionGroups {
    private VpsRelayConnectionGroups() {}

    static List<Group> build(List<VpsRelayStore.Record> records, VpsRelayStore store,
                             String profileKey) {
        LinkedHashMap<String, ArrayList<VpsRelayStore.Record>> grouped = new LinkedHashMap<>();
        if (records != null) {
            for (VpsRelayStore.Record record : records) {
                if (record == null || !record.config().hasValidConnection()) continue;
                String key = record.config().serverIdentityKey();
                ArrayList<VpsRelayStore.Record> group = grouped.get(key);
                if (group == null) {
                    group = new ArrayList<>();
                    grouped.put(key, group);
                }
                group.add(record);
            }
        }
        String primary = store == null ? "" : clean(store.selectedRelayId(profileKey));
        ArrayList<Group> result = new ArrayList<>();
        for (ArrayList<VpsRelayStore.Record> group : grouped.values()) {
            Collections.sort(group,
                    Comparator.comparingInt(record -> rank(record, store, profileKey, primary)));
            result.add(new Group(group));
        }
        return Collections.unmodifiableList(result);
    }

    private static int rank(VpsRelayStore.Record record, VpsRelayStore store,
                            String profileKey, String primary) {
        if (record.id().equals(primary)) return 0;
        return store != null && store.relayEnabledForProfile(profileKey, record.id()) ? 1 : 2;
    }

    static final class Group {
        private final List<VpsRelayStore.Record> connections;

        Group(List<VpsRelayStore.Record> connections) {
            this.connections = Collections.unmodifiableList(new ArrayList<>(connections));
        }

        List<VpsRelayStore.Record> connections() { return connections; }

        VpsRelayConfig server() {
            return connections.isEmpty() ? VpsRelayConfig.disabled() : connections.get(0).config();
        }

        String key() { return server().serverIdentityKey(); }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
