package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Builds an independent Telegram DC probe before Telegram has used the local proxy. */
final class BootstrapPingPlanner {
    private BootstrapPingPlanner() {}

    static Plan plan(RouteEngine.Settings settings, int dc, Map<Integer, String> dcRules) {
        List<RouteCandidate> candidates = new RouteEngine().buildCandidates(settings, dc, false);
        if (candidates.isEmpty()) return Plan.empty();
        ArrayList<RoutePingTarget> targets = new ArrayList<>();
        StringBuilder identity = new StringBuilder("bootstrap");
        for (RouteCandidate candidate : candidates) {
            if (candidate == null || !candidate.enabled()) continue;
            identity.append('|').append(candidate.key()).append('@').append(candidate.endpoint());
            RouteState synthetic = RouteState.active(candidate, candidate.endpoint(), -1, "probe");
            targets.addAll(ActiveRoutePingPlanner.targetsFor(
                    synthetic, candidate.relayConfig(), dcRules));
        }
        return targets.isEmpty() ? Plan.empty()
                : new Plan(identity.toString(), targets);
    }

    static final class Plan {
        private final String identity;
        private final List<RoutePingTarget> targets;

        private Plan(String identity, List<RoutePingTarget> targets) {
            this.identity = identity == null ? "" : identity;
            this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
        }

        static Plan empty() {
            return new Plan("", Collections.emptyList());
        }

        String identity() { return identity; }
        List<RoutePingTarget> targets() { return targets; }
        boolean isEmpty() { return identity.isEmpty() || targets.isEmpty(); }
    }
}
