package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ActiveRoutePingPlanner {
    private ActiveRoutePingPlanner() {}

    static List<RoutePingTarget> targetsFor(RouteState routeState) {
        if (routeState == null || !routeState.active() || routeState.candidate() == null) {
            return Collections.emptyList();
        }
        RouteCandidate route = routeState.candidate();
        ArrayList<RoutePingTarget> targets = new ArrayList<>();
        switch (route.type()) {
            case DIRECT_WS:
                String[] domains = TgConstants.wsDomains(route.dc(), route.media());
                if (domains.length > 0) {
                    targets.add(RoutePingTarget.websocket(route.endpoint(), domains[0], "/apiws"));
                }
                break;
            case PUBLIC_CLOUDFLARE:
            case CUSTOM_CLOUDFLARE:
                String activeDomain = routeState.activeEndpoint().isEmpty()
                        ? route.endpoint() : routeState.activeEndpoint();
                if (looksLikeDomain(activeDomain)) {
                    String host = "kws" + route.dc() + "." + activeDomain;
                    targets.add(RoutePingTarget.websocket(host, host, "/apiws"));
                }
                break;
            case WORKER:
                if (!route.endpoint().isEmpty()) {
                    targets.add(RoutePingTarget.websocket(route.endpoint(), route.endpoint(), "/apiws"));
                }
                break;
            case VPS_RELAY:
                targets.add(RoutePingTarget.tcp(route.endpoint(), route.port()));
                break;
            case TCP_FALLBACK:
                targets.add(RoutePingTarget.tcp(route.endpoint(), route.port()));
                break;
            default:
                break;
        }
        return targets;
    }

    private static boolean looksLikeDomain(String value) {
        if (value == null) return false;
        String domain = value.trim();
        return domain.contains(".") && !domain.contains(" ");
    }
}
