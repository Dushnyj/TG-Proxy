package com.dushnyj.tgproxy;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Persisted per-network allow-list of transports. Preference only orders enabled routes. */
final class RouteAvailability {
    private static final int DIRECT = 1;
    private static final int RELAY = 1 << 1;
    private static final int WORKER = 1 << 2;
    private static final int CUSTOM_CF = 1 << 3;
    private static final int PUBLIC_CF = 1 << 4;
    private static final int ALL = DIRECT | RELAY | WORKER | CUSTOM_CF | PUBLIC_CF;

    private final int mask;

    private RouteAvailability(int mask) {
        this.mask = mask & ALL;
    }

    static RouteAvailability all() {
        return new RouteAvailability(ALL);
    }

    static RouteAvailability directOnly() {
        return new RouteAvailability(DIRECT);
    }

    static RouteAvailability none() {
        return new RouteAvailability(0);
    }

    static RouteAvailability of(boolean direct, boolean relay, boolean worker,
                                boolean customCloudflare, boolean publicCloudflare) {
        int value = 0;
        if (direct) value |= DIRECT;
        if (relay) value |= RELAY;
        if (worker) value |= WORKER;
        if (customCloudflare) value |= CUSTOM_CF;
        if (publicCloudflare) value |= PUBLIC_CF;
        return new RouteAvailability(value);
    }

    static RouteAvailability fromStored(String stored) {
        if (stored == null || stored.trim().isEmpty()) return all();
        String value = stored.trim().toLowerCase(Locale.US);
        if ("all".equals(value)) return all();
        if ("direct".equals(value)) return directOnly();
        try {
            return new RouteAvailability(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return all();
        }
    }

    boolean isEnabled(RouteType type) {
        if (type == null) return false;
        switch (type) {
            case DIRECT_WS: return (mask & DIRECT) != 0;
            case VPS_RELAY: return (mask & RELAY) != 0;
            case WORKER: return (mask & WORKER) != 0;
            case CUSTOM_CLOUDFLARE: return (mask & CUSTOM_CF) != 0;
            case PUBLIC_CLOUDFLARE: return (mask & PUBLIC_CF) != 0;
            default: return false;
        }
    }

    boolean hasAny() {
        return mask != 0;
    }

    RouteAvailability with(RouteType type, boolean enabled) {
        int bit = bit(type);
        return new RouteAvailability(enabled ? mask | bit : mask & ~bit);
    }

    Set<RouteType> disabledComparedTo(RouteAvailability next) {
        RouteAvailability normalized = next == null ? all() : next;
        EnumSet<RouteType> result = EnumSet.noneOf(RouteType.class);
        for (RouteType type : RouteType.values()) {
            if (isEnabled(type) && !normalized.isEnabled(type)) result.add(type);
        }
        return result;
    }

    String toStored() {
        return Integer.toString(mask);
    }

    @Override public boolean equals(Object other) {
        return other instanceof RouteAvailability && ((RouteAvailability) other).mask == mask;
    }

    @Override public int hashCode() {
        return mask;
    }

    private static int bit(RouteType type) {
        if (type == null) return 0;
        switch (type) {
            case DIRECT_WS: return DIRECT;
            case VPS_RELAY: return RELAY;
            case WORKER: return WORKER;
            case CUSTOM_CLOUDFLARE: return CUSTOM_CF;
            case PUBLIC_CLOUDFLARE: return PUBLIC_CF;
            default: return 0;
        }
    }
}
