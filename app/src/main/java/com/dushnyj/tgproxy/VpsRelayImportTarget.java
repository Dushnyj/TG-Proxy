package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class VpsRelayImportTarget {
    enum Kind {
        ALL_NETWORKS,
        CURRENT_NETWORK,
        SAVED_NETWORK
    }

    private VpsRelayImportTarget() {}

    static List<Option> options(NetworkProfileRecord current,
                                Map<String, NetworkProfileRecord> profiles) {
        ArrayList<Option> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        result.add(new Option(Kind.ALL_NETWORKS, "", ""));
        seen.add("");

        if (current != null && seen.add(current.key())) {
            result.add(new Option(Kind.CURRENT_NETWORK, current.key(), current.displayName()));
        }
        if (profiles != null) {
            for (NetworkProfileRecord record : profiles.values()) {
                if (record == null || !seen.add(record.key())) continue;
                result.add(new Option(Kind.SAVED_NETWORK, record.key(), record.displayName()));
            }
        }
        return result;
    }

    static VpsRelayStore.Record apply(VpsRelayStore store, VpsRelayConfig relay, Option option) {
        if (store == null) throw new IllegalArgumentException("store is required");
        VpsRelayConfig safeRelay = relay == null ? VpsRelayConfig.disabled() : relay;
        String profileKey = option == null ? "" : option.profileKey();
        return store.saveRelay(safeRelay.withProfileKey(profileKey), profileKey);
    }

    static final class Option {
        private final Kind kind;
        private final String profileKey;
        private final String displayName;

        Option(Kind kind, String profileKey, String displayName) {
            this.kind = kind == null ? Kind.ALL_NETWORKS : kind;
            this.profileKey = profileKey == null ? "" : profileKey.trim();
            this.displayName = displayName == null ? "" : displayName.trim();
        }

        Kind kind() {
            return kind;
        }

        String profileKey() {
            return profileKey;
        }

        String displayName() {
            return displayName;
        }
    }
}
