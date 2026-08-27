package com.dushnyj.tgproxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Negotiated Relay transport/topology contract. Unknown keeps legacy interoperability. */
final class VpsRelayCapabilities {
    private static final int MAX_DCS = 32;
    private final boolean known;
    private final int minProtocol;
    private final int maxProtocol;
    private final boolean dynamicTopology;
    private final long topologyRevision;
    private final Set<Integer> productionDcs;
    private final Set<Integer> testDcs;

    private VpsRelayCapabilities(boolean known, int minProtocol, int maxProtocol,
                                  boolean dynamicTopology, long topologyRevision,
                                  Set<Integer> productionDcs, Set<Integer> testDcs) {
        this.known = known;
        this.minProtocol = Math.max(1, minProtocol);
        this.maxProtocol = Math.max(this.minProtocol, maxProtocol);
        this.dynamicTopology = dynamicTopology;
        this.topologyRevision = Math.max(0L, topologyRevision);
        this.productionDcs = immutable(productionDcs);
        this.testDcs = immutable(testDcs);
    }

    static VpsRelayCapabilities unknown() {
        return new VpsRelayCapabilities(false, 1, 1, false, 0L,
                Collections.emptySet(), Collections.emptySet());
    }

    static VpsRelayCapabilities parse(String json) throws Exception {
        JSONObject root = new JSONObject(json == null ? "" : json);
        if (!"tgproxy-relay".equals(root.optString("name"))) {
            throw new IllegalArgumentException("not a TG Proxy Relay capability response");
        }
        JSONObject protocol = root.getJSONObject("protocol");
        JSONObject topology = root.getJSONObject("topology");
        int min = protocol.getInt("min");
        int max = protocol.getInt("max");
        if (min <= 0 || max < min || max > 100) {
            throw new IllegalArgumentException("invalid Relay protocol range");
        }
        Set<Integer> production = integers(topology.optJSONArray("productionDcs"));
        if (production.isEmpty()) {
            throw new IllegalArgumentException("Relay production DC list is empty");
        }
        return new VpsRelayCapabilities(true, min, max,
                topology.optBoolean("dynamic", false),
                Math.max(0L, topology.optLong("revision", 0L)),
                production,
                integers(topology.optJSONArray("testDcs")));
    }

    static VpsRelayCapabilities fromStored(String stored) {
        if (stored == null || stored.trim().isEmpty()) return unknown();
        try {
            String[] groups = stored.split(";", -1);
            if (groups.length != 3 && groups.length != 5) return unknown();
            String[] range = groups[0].split("-", -1);
            if (range.length != 2) return unknown();
            int min = Integer.parseInt(range[0]);
            int max = Integer.parseInt(range[1]);
            if (min <= 0 || max < min || max > 100) return unknown();
            if (groups.length == 3) {
                Set<Integer> production = csv(groups[1]);
                if (production.isEmpty()) return unknown();
                return new VpsRelayCapabilities(true, min, max, false, 0L,
                        production, csv(groups[2]));
            }
            if (!"0".equals(groups[1]) && !"1".equals(groups[1])) return unknown();
            boolean dynamic = "1".equals(groups[1]);
            long revision = Long.parseLong(groups[2]);
            if (revision < 0L) return unknown();
            Set<Integer> production = csv(groups[3]);
            if (production.isEmpty()) return unknown();
            return new VpsRelayCapabilities(true, min, max, dynamic, revision,
                    production, csv(groups[4]));
        } catch (Exception ignored) {
            return unknown();
        }
    }

    boolean known() { return known; }
    int minProtocol() { return minProtocol; }
    int maxProtocol() { return maxProtocol; }
    boolean dynamicTopology() { return dynamicTopology; }
    long topologyRevision() { return topologyRevision; }
    Set<Integer> productionDcs() { return productionDcs; }
    Set<Integer> testDcs() { return testDcs; }

    boolean compatible(int appMin, int appMax) {
        return !known || (minProtocol <= appMax && maxProtocol >= appMin);
    }

    boolean supports(int dc, boolean test) {
        // A Relay without /capabilities is a legacy static-map server. Treating it as able to
        // route every future DC produced a candidate that was guaranteed to end in "unknown dc".
        if (!known) {
            return (test ? MtProtoConfig.testDcRules() : MtProtoConfig.relayDcRules())
                    .containsKey(dc);
        }
        return dynamicTopology || (test ? testDcs : productionDcs).contains(dc);
    }

    String toStored() {
        if (!known) return "";
        return minProtocol + "-" + maxProtocol + ";" + (dynamicTopology ? "1" : "0")
                + ";" + topologyRevision + ";" + join(productionDcs) + ";" + join(testDcs);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof VpsRelayCapabilities)) return false;
        VpsRelayCapabilities value = (VpsRelayCapabilities) other;
        return known == value.known && minProtocol == value.minProtocol
                && maxProtocol == value.maxProtocol && dynamicTopology == value.dynamicTopology
                && topologyRevision == value.topologyRevision
                && productionDcs.equals(value.productionDcs) && testDcs.equals(value.testDcs);
    }

    @Override public int hashCode() {
        int value = known ? 1 : 0;
        value = 31 * value + minProtocol;
        value = 31 * value + maxProtocol;
        value = 31 * value + (dynamicTopology ? 1 : 0);
        value = 31 * value + (int) (topologyRevision ^ (topologyRevision >>> 32));
        value = 31 * value + productionDcs.hashCode();
        return 31 * value + testDcs.hashCode();
    }

    private static Set<Integer> integers(JSONArray array) {
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        if (array == null) return values;
        if (array.length() > MAX_DCS) throw new IllegalArgumentException("too many Relay DCs");
        for (int i = 0; i < array.length(); i++) {
            int value = array.optInt(i, 0);
            if (value <= 0 || value > 32767 || !values.add(value)) {
                throw new IllegalArgumentException("invalid Relay DC list");
            }
        }
        return values;
    }

    private static Set<Integer> csv(String raw) {
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return values;
        for (String part : raw.split(",")) {
            int value = Integer.parseInt(part);
            if (value <= 0 || value > 32767 || !values.add(value)
                    || values.size() > MAX_DCS) throw new IllegalArgumentException();
        }
        return values;
    }

    private static Set<Integer> immutable(Set<Integer> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                source == null ? Collections.emptySet() : source));
    }

    private static String join(Set<Integer> values) {
        StringBuilder out = new StringBuilder();
        for (Integer value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }
}
