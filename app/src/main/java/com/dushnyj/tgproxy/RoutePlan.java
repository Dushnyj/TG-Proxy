package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RoutePlan {
    private final List<RouteCandidate> routes;
    private final RouteCandidate selected;
    private final String requiresWarmupBeforeSwitch;

    RoutePlan(List<RouteCandidate> routes, RouteCandidate selected,
              String requiresWarmupBeforeSwitch) {
        this.routes = Collections.unmodifiableList(new ArrayList<>(routes));
        this.selected = selected;
        this.requiresWarmupBeforeSwitch =
                requiresWarmupBeforeSwitch == null ? "" : requiresWarmupBeforeSwitch;
    }

    List<RouteCandidate> routes() {
        return routes;
    }

    List<RouteType> routeTypes() {
        ArrayList<RouteType> types = new ArrayList<>();
        for (RouteCandidate route : routes) {
            if (!types.contains(route.type())) types.add(route.type());
        }
        return types;
    }

    RouteCandidate selected() {
        return selected;
    }

    String requiresWarmupBeforeSwitch() {
        return requiresWarmupBeforeSwitch;
    }

    boolean isEmpty() {
        return selected == null;
    }
}
