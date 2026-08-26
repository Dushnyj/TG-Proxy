package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable configuration consumed atomically by the running proxy engine. */
final class RuntimeConfigSnapshot {
    final String secretHex;
    final String dcRules;
    final String cfMode;
    final List<String> cfDomains;
    final boolean cfCustomDomains;
    final boolean cfWarmupEnabled;
    final List<String> workerDomains;
    final VpsRelayConfig relay;
    final boolean verbose;
    final NetworkProfile networkProfile;
    final RoutePreference routePreference;
    final Map<String, RouteStats> routeStats;

    private RuntimeConfigSnapshot(Builder builder) {
        secretHex = builder.secretHex;
        dcRules = builder.dcRules;
        cfMode = builder.cfMode;
        cfDomains = Collections.unmodifiableList(new ArrayList<>(builder.cfDomains));
        cfCustomDomains = builder.cfCustomDomains;
        cfWarmupEnabled = builder.cfWarmupEnabled;
        workerDomains = Collections.unmodifiableList(new ArrayList<>(builder.workerDomains));
        relay = builder.relay == null ? VpsRelayConfig.disabled() : builder.relay;
        verbose = builder.verbose;
        networkProfile = builder.networkProfile == null
                ? NetworkProfile.defaultProfile() : builder.networkProfile;
        routePreference = builder.routePreference == null
                ? RoutePreference.AUTO : builder.routePreference;
        LinkedHashMap<String, RouteStats> stats = new LinkedHashMap<>();
        for (Map.Entry<String, RouteStats> entry : builder.routeStats.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                stats.put(entry.getKey(), entry.getValue().copy());
            }
        }
        routeStats = Collections.unmodifiableMap(stats);
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private String secretHex = MtProtoConfig.generateSecretHex();
        private String dcRules = MtProtoConfig.DEFAULT_DC_RULES;
        private String cfMode = MtProtoProxyEngine.CF_MODE_AUTO;
        private List<String> cfDomains = Collections.emptyList();
        private boolean cfCustomDomains;
        private boolean cfWarmupEnabled = true;
        private List<String> workerDomains = Collections.emptyList();
        private VpsRelayConfig relay = VpsRelayConfig.disabled();
        private boolean verbose;
        private NetworkProfile networkProfile = NetworkProfile.defaultProfile();
        private RoutePreference routePreference = RoutePreference.AUTO;
        private Map<String, RouteStats> routeStats = Collections.emptyMap();

        Builder secretHex(String value) { secretHex = value; return this; }
        Builder dcRules(String value) { dcRules = value; return this; }
        Builder cfMode(String value) { cfMode = value; return this; }
        Builder cfDomains(List<String> value) {
            cfDomains = value == null ? Collections.emptyList() : value;
            return this;
        }
        Builder cfCustomDomains(boolean value) { cfCustomDomains = value; return this; }
        Builder cfWarmupEnabled(boolean value) { cfWarmupEnabled = value; return this; }
        Builder workerDomains(List<String> value) {
            workerDomains = value == null ? Collections.emptyList() : value;
            return this;
        }
        Builder relay(VpsRelayConfig value) { relay = value; return this; }
        Builder verbose(boolean value) { verbose = value; return this; }
        Builder networkProfile(NetworkProfile value) { networkProfile = value; return this; }
        Builder routePreference(RoutePreference value) { routePreference = value; return this; }
        Builder routeStats(Map<String, RouteStats> value) {
            routeStats = value == null ? Collections.emptyMap() : value;
            return this;
        }

        RuntimeConfigSnapshot build() { return new RuntimeConfigSnapshot(this); }
    }
}
