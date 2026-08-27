package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class RouteEngine {
    private static final int HYSTERESIS_SCORE = 120;
    private final ConcurrentHashMap<String, AtomicLong> halfOpenLeaseUntil =
            new ConcurrentHashMap<>();

    RoutePlan plan(Settings settings, int dc, boolean media, String currentRouteKey,
                   Map<String, RouteStats> statsByRoute, long nowMs) {
        return plan(buildCandidates(settings, dc, media), currentRouteKey, statsByRoute, nowMs);
    }

    RoutePlan plan(List<RouteCandidate> candidates, String currentRouteKey,
                   Map<String, RouteStats> statsByRoute, long nowMs) {
        List<RouteCandidate> available = availableCandidates(candidates, statsByRoute, nowMs);
        if (available.isEmpty()) return new RoutePlan(Collections.emptyList(), null, "");

        Map<String, Integer> order = orderIndex(available);
        Comparator<RouteCandidate> scoreComparator = (left, right) -> Integer.compare(
                score(left, order, statsByRoute), score(right, order, statsByRoute));
        RouteCandidate best = Collections.max(available, scoreComparator);
        RouteCandidate current = findByKey(available, currentRouteKey);
        RouteCandidate selected = chooseWithHysteresis(best, current, order, statsByRoute);

        ArrayList<RouteCandidate> ordered = new ArrayList<>(available);
        Collections.sort(ordered, (left, right) -> {
            if (left.key().equals(right.key())) return 0;
            if (left.key().equals(selected.key())) return -1;
            if (right.key().equals(selected.key())) return 1;
            return Integer.compare(score(right, order, statsByRoute),
                    score(left, order, statsByRoute));
        });

        String warmup = "";
        if (currentRouteKey != null && !currentRouteKey.isEmpty()
                && !selected.key().equals(currentRouteKey)
                && selected.requiresWarmup()) {
            warmup = selected.key();
        }
        return new RoutePlan(ordered, selected, warmup);
    }

    List<RouteCandidate> buildCandidates(Settings settings, int dc, boolean media) {
        Settings s = settings == null ? Settings.builder().build() : settings;
        if (!MtProtoConfig.isValidDc(dc)) return Collections.emptyList();
        boolean knownRawTelegramDc = (s.testDc
                ? MtProtoConfig.testDcRules() : MtProtoConfig.relayDcRules()).containsKey(dc);
        boolean hasDirectMapping = s.dcRedirects.containsKey(dc);
        if (!knownRawTelegramDc && !hasDirectMapping && !s.vpsRelayEnabled) {
            return Collections.emptyList();
        }

        ArrayList<RouteCandidate> direct = new ArrayList<>();
        String targetIp = s.dcRedirects.get(dc);
        if (targetIp != null && TgRoutePolicy.shouldUseDirectWs(dc, media, s.dcRedirects)) {
            direct.add(RouteCandidate.directWs(dc, media, s.testDc, targetIp));
        }

        ArrayList<RouteCandidate> vps = new ArrayList<>();
        if (s.vpsRelayEnabled) {
            vps.add(RouteCandidate.vpsRelay(s.vpsRelayName, s.vpsRelayHost, s.vpsRelayPort,
                    dc, media, s.testDc));
        }

        ArrayList<RouteCandidate> worker = new ArrayList<>();
        if (!s.workerDomains.isEmpty() && knownRawTelegramDc) {
            worker.add(RouteCandidate.worker(dc, media, s.testDc, s.workerDomains.get(0)));
        }

        ArrayList<RouteCandidate> customCf = new ArrayList<>();
        if (!s.testDc && !MtProtoProxyEngine.CF_MODE_OFF.equals(s.cfMode)
                && !s.customCfDomains.isEmpty()
                && knownRawTelegramDc) {
            customCf.add(RouteCandidate.customCloudflare(dc, media, s.customCfDomains.get(0)));
        }

        ArrayList<RouteCandidate> publicCf = new ArrayList<>();
        if (!s.testDc && !MtProtoProxyEngine.CF_MODE_OFF.equals(s.cfMode)
                && !s.publicCfDomains.isEmpty()
                && knownRawTelegramDc) {
            publicCf.add(RouteCandidate.publicCloudflare(dc, media, "public-cf"));
        }

        ArrayList<RouteCandidate> result = new ArrayList<>();
        if (s.routePreference == RoutePreference.DIRECT_FIRST) {
            result.addAll(direct);
            result.addAll(vps);
            result.addAll(worker);
            result.addAll(customCf);
            result.addAll(publicCf);
        } else if (s.routePreference == RoutePreference.CLOUDFLARE_FIRST) {
            result.addAll(customCf);
            result.addAll(worker);
            result.addAll(publicCf);
            result.addAll(vps);
            result.addAll(direct);
        } else if (s.routePreference == RoutePreference.RELAY_FIRST) {
            result.addAll(vps);
            result.addAll(worker);
            result.addAll(customCf);
            result.addAll(publicCf);
            result.addAll(direct);
        } else if (MtProtoProxyEngine.CF_MODE_ON.equals(s.cfMode)) {
            result.addAll(vps);
            result.addAll(worker);
            result.addAll(customCf);
            result.addAll(publicCf);
            result.addAll(direct);
        } else if (s.networkProfile.isMobile()) {
            result.addAll(vps);
            result.addAll(worker);
            result.addAll(customCf);
            result.addAll(publicCf);
            result.addAll(direct);
        } else {
            result.addAll(direct);
            result.addAll(vps);
            result.addAll(worker);
            result.addAll(customCf);
            result.addAll(publicCf);
        }
        return result;
    }

    private RouteCandidate chooseWithHysteresis(RouteCandidate best, RouteCandidate current,
                                                Map<String, Integer> order,
                                                Map<String, RouteStats> statsByRoute) {
        if (current == null || current.key().equals(best.key())) return best;
        RouteStats bestStats = statsByRoute.get(best.key());
        RouteStats currentStats = statsByRoute.get(current.key());
        if (bestStats != null && !bestStats.hasStableEvidence()) return current;
        int bestScore = score(best, order, statsByRoute);
        int currentScore = score(current, order, statsByRoute);
        if (bestScore - currentScore < HYSTERESIS_SCORE) return current;
        return best;
    }

    private List<RouteCandidate> availableCandidates(List<RouteCandidate> candidates,
                                                     Map<String, RouteStats> statsByRoute,
                                                     long nowMs) {
        ArrayList<RouteCandidate> result = new ArrayList<>();
        RouteCandidate earliestHalfOpen = null;
        long earliestCooldown = Long.MAX_VALUE;
        if (candidates == null) return result;
        for (RouteCandidate candidate : candidates) {
            if (candidate == null || !candidate.enabled()) continue;
            RouteStats stats = statsByRoute.get(candidate.key());
            if (stats != null) stats.pruneExpired(nowMs);
            if (stats != null && stats.isCoolingDown(nowMs)) {
                long until = stats.cooldownUntilMs();
                if (until < earliestCooldown) {
                    earliestCooldown = until;
                    earliestHalfOpen = candidate;
                }
                continue;
            }
            result.add(candidate);
        }
        // During a complete blackout allow exactly one early probe. Its lease lasts until the
        // selected route's cooldown expiry, so a Telegram reconnect burst cannot hammer the
        // same failed endpoint from dozens of simultaneous local sessions.
        if (result.isEmpty() && earliestHalfOpen != null
                && claimHalfOpenLease(earliestHalfOpen.key(), nowMs, earliestCooldown)) {
            result.add(earliestHalfOpen);
        }
        return result;
    }

    private boolean claimHalfOpenLease(String routeKey, long nowMs, long cooldownUntilMs) {
        AtomicLong lease = halfOpenLeaseUntil.get(routeKey);
        if (lease == null) {
            AtomicLong created = new AtomicLong();
            AtomicLong existing = halfOpenLeaseUntil.putIfAbsent(routeKey, created);
            lease = existing == null ? created : existing;
        }
        while (true) {
            long current = lease.get();
            if (current > nowMs) return false;
            long next = Math.max(nowMs + 1_000L, cooldownUntilMs);
            if (lease.compareAndSet(current, next)) return true;
        }
    }

    private int score(RouteCandidate candidate, Map<String, Integer> order,
                      Map<String, RouteStats> statsByRoute) {
        Integer index = order.get(candidate.key());
        int score = 10_000 - (index == null ? 500 : index * 100);
        RouteStats stats = statsByRoute.get(candidate.key());
        if (stats != null) score += stats.scoreAdjustment();
        return score;
    }

    private static RouteCandidate findByKey(List<RouteCandidate> candidates, String key) {
        if (key == null || key.isEmpty()) return null;
        for (RouteCandidate candidate : candidates) {
            if (candidate.key().equals(key)) return candidate;
        }
        return null;
    }

    private static Map<String, Integer> orderIndex(List<RouteCandidate> candidates) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            result.put(candidates.get(i).key(), i);
        }
        return result;
    }

    static final class Settings {
        private final NetworkProfile networkProfile;
        private final RoutePreference routePreference;
        private final String cfMode;
        private final Map<Integer, String> dcRedirects;
        private final List<String> workerDomains;
        private final List<String> customCfDomains;
        private final List<String> publicCfDomains;
        private final boolean vpsRelayEnabled;
        private final String vpsRelayName;
        private final String vpsRelayHost;
        private final int vpsRelayPort;
        private final boolean testDc;

        private Settings(Builder builder) {
            this.networkProfile = builder.networkProfile == null
                    ? NetworkProfile.defaultProfile() : builder.networkProfile;
            this.routePreference = builder.routePreference == null
                    ? RoutePreference.AUTO : builder.routePreference;
            this.cfMode = MtProtoProxyEngine.normalizeCfProxyMode(builder.cfMode);
            this.dcRedirects = new LinkedHashMap<>(builder.dcRedirects);
            this.workerDomains = copy(builder.workerDomains);
            this.customCfDomains = copy(builder.customCfDomains);
            this.publicCfDomains = copy(builder.publicCfDomains);
            this.vpsRelayEnabled = builder.vpsRelayEnabled;
            this.vpsRelayName = builder.vpsRelayName;
            this.vpsRelayHost = builder.vpsRelayHost;
            this.vpsRelayPort = builder.vpsRelayPort;
            this.testDc = builder.testDc;
        }

        static Builder builder() {
            return new Builder();
        }

        NetworkProfile networkProfile() {
            return networkProfile;
        }

        RoutePreference routePreference() {
            return routePreference;
        }

        String cfMode() {
            return cfMode;
        }

        Map<Integer, String> dcRedirects() {
            return Collections.unmodifiableMap(dcRedirects);
        }

        List<String> workerDomains() {
            return Collections.unmodifiableList(workerDomains);
        }

        List<String> customCfDomains() {
            return Collections.unmodifiableList(customCfDomains);
        }

        List<String> publicCfDomains() {
            return Collections.unmodifiableList(publicCfDomains);
        }

        boolean vpsRelayEnabled() {
            return vpsRelayEnabled;
        }

        private static List<String> copy(List<String> source) {
            return source == null ? Collections.emptyList() : new ArrayList<>(source);
        }

        static final class Builder {
            private NetworkProfile networkProfile = NetworkProfile.defaultProfile();
            private RoutePreference routePreference = RoutePreference.AUTO;
            private String cfMode = MtProtoProxyEngine.CF_MODE_AUTO;
            private Map<Integer, String> dcRedirects = new LinkedHashMap<>();
            private List<String> workerDomains = Collections.emptyList();
            private List<String> customCfDomains = Collections.emptyList();
            private List<String> publicCfDomains = Collections.emptyList();
            private boolean vpsRelayEnabled;
            private String vpsRelayName = "";
            private String vpsRelayHost = "";
            private int vpsRelayPort;
            private boolean testDc;

            Builder networkProfile(NetworkProfile networkProfile) {
                this.networkProfile = networkProfile;
                return this;
            }

            Builder routePreference(RoutePreference routePreference) {
                this.routePreference = routePreference == null
                        ? RoutePreference.AUTO : routePreference;
                return this;
            }

            Builder cfMode(String cfMode) {
                this.cfMode = cfMode;
                return this;
            }

            Builder dcRedirects(Map<Integer, String> dcRedirects) {
                this.dcRedirects = dcRedirects == null
                        ? new LinkedHashMap<>() : new LinkedHashMap<>(dcRedirects);
                return this;
            }

            Builder workerDomains(List<String> workerDomains) {
                this.workerDomains = workerDomains;
                return this;
            }

            Builder customCfDomains(List<String> customCfDomains) {
                this.customCfDomains = customCfDomains;
                return this;
            }

            Builder publicCfDomains(List<String> publicCfDomains) {
                this.publicCfDomains = publicCfDomains;
                return this;
            }

            Builder vpsRelay(String name, String host, int port) {
                this.vpsRelayEnabled = true;
                this.vpsRelayName = name;
                this.vpsRelayHost = host;
                this.vpsRelayPort = port;
                return this;
            }

            Builder testDc(boolean value) {
                this.testDc = value;
                return this;
            }

            Settings build() {
                return new Settings(this);
            }
        }
    }
}
