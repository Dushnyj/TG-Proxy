package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure token-choice policy used after the read-only VPS audit. */
final class VpsTokenReusePolicy {
    private VpsTokenReusePolicy() {}

    static boolean isPresentOnAuditedServer(String rawToken,
                                            boolean existingRelay,
                                            boolean tokenInventoryKnown,
                                            List<String> activeTokenIds) {
        if (!existingRelay || !tokenInventoryKnown) return true;
        String tokenId = VpsOwnerRecord.clientTokenId(rawToken);
        return !tokenId.isEmpty() && activeTokenIds != null && activeTokenIds.contains(tokenId);
    }

    static List<VpsRelayConfig> choices(List<VpsRelayConfig> endpoints,
                                        List<VpsRelayStore.Record> savedRelays,
                                        List<VpsOwnerRecord> owners,
                                        VpsOwnerRecord sshOwner,
                                        boolean existingRelay,
                                        boolean tokenInventoryKnown,
                                        List<String> activeTokenIds,
                                        String profileKey) {
        if (endpoints == null || endpoints.isEmpty()) return Collections.emptyList();
        LinkedHashMap<String, VpsRelayConfig> result = new LinkedHashMap<>();
        Set<String> allowedTokenIds = new LinkedHashSet<>();
        if (activeTokenIds != null) allowedTokenIds.addAll(activeTokenIds);
        boolean filterServerTokens = existingRelay && tokenInventoryKnown;

        if (savedRelays != null) {
            for (VpsRelayStore.Record record : savedRelays) {
                VpsRelayConfig relay = record == null ? null : record.config();
                if (relay == null || !relay.hasValidConnection()
                        || !matchesAnyEndpoint(relay, endpoints)) continue;
                put(result, relay.withEnabled(true).withProfileKey(profileKey),
                        filterServerTokens, allowedTokenIds);
            }
        }

        if (owners != null) {
            for (VpsOwnerRecord owner : owners) {
                if (owner == null) continue;
                VpsRelayConfig ownerEndpoint = owner.relayConfig(
                        "VPS Relay", "probe-token", profileKey);
                if (!matchesAnyEndpoint(ownerEndpoint, endpoints)) continue;
                addManaged(result, owner, ownerEndpoint, profileKey,
                        filterServerTokens, allowedTokenIds);
            }
        }

        // The read-only SSH audit is stronger evidence than a public-host alias. If it confirms
        // an already installed Relay, offer every raw secret retained for that managed VPS even
        // when the user entered its IP, another domain alias or a changed public path.
        if (existingRelay && sshOwner != null) {
            VpsRelayConfig effective = endpoints.get(endpoints.size() - 1);
            addManaged(result, sshOwner, effective, profileKey,
                    filterServerTokens, allowedTokenIds);
        }
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }

    private static void addManaged(LinkedHashMap<String, VpsRelayConfig> result,
                                   VpsOwnerRecord owner,
                                   VpsRelayConfig endpoint,
                                   String profileKey,
                                   boolean filterServerTokens,
                                   Set<String> allowedTokenIds) {
        for (VpsOwnerRecord.ManagedToken token : owner.managedTokens()) {
            if (token == null || token.secret().isEmpty()) continue;
            String name = token.name().isEmpty() ? "VPS Relay" : token.name();
            put(result, endpoint.withTokenAndName(token.secret(), name)
                            .withProfileKey(profileKey),
                    filterServerTokens, allowedTokenIds);
        }
    }

    private static void put(LinkedHashMap<String, VpsRelayConfig> result,
                            VpsRelayConfig relay,
                            boolean filterServerTokens,
                            Set<String> allowedTokenIds) {
        String tokenId = relay == null ? "" : VpsOwnerRecord.clientTokenId(relay.token());
        if (filterServerTokens && !allowedTokenIds.contains(tokenId)) return;
        if (!tokenId.isEmpty() && !result.containsKey(tokenId)) result.put(tokenId, relay);
    }

    private static boolean matchesAnyEndpoint(VpsRelayConfig relay,
                                              List<VpsRelayConfig> endpoints) {
        if (relay == null) return false;
        for (VpsRelayConfig endpoint : endpoints) {
            if (endpoint != null && relay.sameEndpoint(endpoint)) return true;
        }
        return false;
    }
}
