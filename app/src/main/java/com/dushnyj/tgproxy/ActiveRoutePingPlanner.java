package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class ActiveRoutePingPlanner {
    private ActiveRoutePingPlanner() {}

    static List<RoutePingTarget> targetsFor(RouteState routeState) {
        return targetsFor(routeState, VpsRelayConfig.disabled(), Collections.emptyMap());
    }

    static List<RoutePingTarget> targetsFor(RouteState routeState, VpsRelayConfig relayConfig,
                                            Map<Integer, String> dcRules) {
        if (routeState == null || !routeState.active() || routeState.candidate() == null) {
            return Collections.emptyList();
        }
        RouteCandidate route = routeState.candidate();
        int dc = route.dc() > 0 ? route.dc() : firstDc(dcRules);
        boolean media = route.media();
        ArrayList<RoutePingTarget> targets = new ArrayList<>();
        switch (route.type()) {
            case DIRECT_WS:
                String[] domains = TgConstants.wsDomains(route.dc(), route.media());
                if (domains.length > 0) {
                    targets.add(RoutePingTarget.websocket(route.endpoint(), domains[0],
                            route.test() ? "/apiws_test" : "/apiws", dc, media,
                            route.test()));
                }
                break;
            case PUBLIC_CLOUDFLARE:
            case CUSTOM_CLOUDFLARE:
                String activeDomain = routeState.activeEndpoint().isEmpty()
                        ? route.endpoint() : routeState.activeEndpoint();
                if (looksLikeDomain(activeDomain)) {
                    String host = "kws" + route.dc() + "." + activeDomain;
                    targets.add(RoutePingTarget.websocket(host, host,
                            route.test() ? "/apiws_test" : "/apiws", dc, media,
                            route.test()));
                }
                break;
            case WORKER:
                String workerEndpoint = routeState.activeEndpoint().isEmpty()
                        ? route.endpoint() : routeState.activeEndpoint();
                if (!workerEndpoint.isEmpty()) {
                    String dst = route.test()
                            ? MtProtoConfig.testDcRules().get(dc)
                            : (dcRules == null ? "" : dcRules.get(dc));
                    String path = dst == null || dst.trim().isEmpty()
                            ? "/apiws"
                            : "/apiws?dst=" + dst.trim() + "&dc=" + dc;
                    targets.add(RoutePingTarget.websocket(workerEndpoint, workerEndpoint,
                            path, dc, media, route.test()));
                }
                break;
            case VPS_RELAY:
                VpsRelayConfig relay = relayConfig == null
                        ? VpsRelayConfig.disabled() : relayConfig;
                if (relay.isUsable()) {
                    targets.add(RoutePingTarget.relay(relay, dc, media, route.test()));
                }
                break;
            case TCP_FALLBACK:
                targets.add(RoutePingTarget.tcp(route.endpoint(), route.port()));
                break;
            default:
                break;
        }
        return targets;
    }

    private static int firstDc(Map<Integer, String> dcRules) {
        if (dcRules != null) {
            for (Integer dc : dcRules.keySet()) {
                if (dc != null && dc > 0) return dc;
            }
        }
        return 2;
    }

    private static boolean looksLikeDomain(String value) {
        if (value == null) return false;
        String domain = value.trim();
        return domain.contains(".") && !domain.contains(" ");
    }
}
