package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CfProxyDomainState {
    static final String PROFILE_DEFAULT = "default";
    static final String PROFILE_WIFI = "wifi";
    static final String PROFILE_MOBILE = "mobile";

    private static final long DEFAULT_429_COOLDOWN_MS = 45_000L;
    private static final CfProxyDomainState SHARED =
            new CfProxyDomainState(DEFAULT_429_COOLDOWN_MS);

    private final long tooManyRequestsCooldownMs;
    private final Map<String, Long> tooManyRequestsUntil = new HashMap<>();
    private final Map<String, String> activeDomainByProfile = new HashMap<>();

    CfProxyDomainState(long tooManyRequestsCooldownMs) {
        this.tooManyRequestsCooldownMs = tooManyRequestsCooldownMs;
    }

    static CfProxyDomainState shared() {
        return SHARED;
    }

    synchronized List<String> orderedDomains(List<String> domains, long nowMs) {
        return orderedDomains(domains, PROFILE_DEFAULT, nowMs);
    }

    synchronized List<String> orderedDomains(List<String> domains, String profile, long nowMs) {
        ArrayList<String> normalized = new ArrayList<>();
        if (domains == null) return normalized;

        for (String raw : domains) {
            String domain = normalize(raw);
            if (domain.isEmpty() || normalized.contains(domain)) continue;
            if (isCoolingDown(domain, nowMs)) continue;
            normalized.add(domain);
        }

        String activeDomain = activeDomainByProfile.get(normalizeProfile(profile));
        if (activeDomain == null) activeDomain = "";
        if (!activeDomain.isEmpty() && normalized.remove(activeDomain)) {
            normalized.add(0, activeDomain);
        }
        return normalized;
    }

    synchronized void markSuccess(String domain, long nowMs) {
        markSuccess(domain, PROFILE_DEFAULT, nowMs);
    }

    synchronized void markSuccess(String domain, String profile, long nowMs) {
        String normalized = normalize(domain);
        if (normalized.isEmpty()) return;
        activeDomainByProfile.put(normalizeProfile(profile), normalized);
        tooManyRequestsUntil.remove(normalized);
    }

    synchronized String activeDomain(String profile) {
        String active = activeDomainByProfile.get(normalizeProfile(profile));
        return active == null ? "" : active;
    }

    synchronized void markTooManyRequests(String domain, long nowMs) {
        markTooManyRequests(domain, PROFILE_DEFAULT, nowMs);
    }

    synchronized void markTooManyRequests(String domain, String profile, long nowMs) {
        String normalized = normalize(domain);
        if (normalized.isEmpty()) return;
        tooManyRequestsUntil.put(normalized, nowMs + tooManyRequestsCooldownMs);
        String normalizedProfile = normalizeProfile(profile);
        if (normalized.equals(activeDomainByProfile.get(normalizedProfile))) {
            activeDomainByProfile.remove(normalizedProfile);
        }
    }

    static boolean isTooManyRequests(Exception e) {
        String message = e == null ? "" : e.getMessage();
        return message != null && message.contains("429");
    }

    private boolean isCoolingDown(String domain, long nowMs) {
        Long until = tooManyRequestsUntil.get(domain);
        if (until == null) return false;
        if (until <= nowMs) {
            tooManyRequestsUntil.remove(domain);
            return false;
        }
        return true;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.US);
    }

    private static String normalizeProfile(String raw) {
        String profile = normalize(raw);
        return profile.isEmpty() ? PROFILE_DEFAULT : profile;
    }
}
