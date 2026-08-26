package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class RouteStats {
    static final long STATS_RETENTION_MS = TimeUnit.HOURS.toMillis(12);

    private static final long COOLDOWN_429_MS = 45_000L;
    private static final long COOLDOWN_TIMEOUT_MS = 20_000L;
    private static final long COOLDOWN_RESET_MS = 20_000L;
    private static final long COOLDOWN_DNS_TLS_MS = 30_000L;
    private static final long COOLDOWN_IO_MS = 10_000L;
    private static final int MAX_LATENCY_SAMPLES = 15;

    private final EnumMap<RouteError, Integer> failures = new EnumMap<>(RouteError.class);
    private final ArrayList<Integer> latencySamples = new ArrayList<>();
    private int successCount;
    private long lastSuccessMs;
    private long lastUpdateMs;
    private long cooldownUntilMs;
    private RouteError lastError = RouteError.NONE;

    synchronized void recordSuccess(long nowMs, int latencyMs) {
        pruneExpired(nowMs);
        successCount++;
        lastSuccessMs = nowMs;
        lastUpdateMs = nowMs;
        lastError = RouteError.NONE;
        cooldownUntilMs = 0L;
        if (latencyMs >= 0) {
            latencySamples.add(latencyMs);
            while (latencySamples.size() > MAX_LATENCY_SAMPLES) {
                latencySamples.remove(0);
            }
        }
    }

    synchronized void recordFailure(RouteError error, long nowMs) {
        pruneExpired(nowMs);
        RouteError normalized = error == null ? RouteError.UNKNOWN : error;
        failures.put(normalized, failureCount(normalized) + 1);
        lastError = normalized;
        lastUpdateMs = nowMs;
        long duration = cooldownDuration(normalized);
        if (duration > 0L) {
            cooldownUntilMs = Math.max(cooldownUntilMs, nowMs + duration);
        }
    }

    synchronized boolean isCoolingDown(long nowMs) {
        pruneExpired(nowMs);
        return cooldownUntilMs > nowMs;
    }

    synchronized boolean pruneExpired(long nowMs) {
        if (lastUpdateMs <= 0L) return false;
        if (nowMs - lastUpdateMs <= STATS_RETENTION_MS) return false;
        failures.clear();
        latencySamples.clear();
        successCount = 0;
        lastSuccessMs = 0L;
        lastUpdateMs = 0L;
        cooldownUntilMs = 0L;
        lastError = RouteError.NONE;
        return true;
    }

    synchronized int successCount() {
        return successCount;
    }

    synchronized int failureCount(RouteError error) {
        Integer count = failures.get(error);
        return count == null ? 0 : count;
    }

    synchronized int totalFailures() {
        int total = 0;
        for (Integer count : failures.values()) total += count;
        return total;
    }

    synchronized int medianLatencyMs() {
        if (latencySamples.isEmpty()) return -1;
        List<Integer> copy = new ArrayList<>(latencySamples);
        Collections.sort(copy);
        return copy.get(copy.size() / 2);
    }

    synchronized RouteError lastError() {
        return lastError;
    }

    synchronized long lastSuccessMs() {
        return lastSuccessMs;
    }

    synchronized long lastUpdateMs() {
        return lastUpdateMs;
    }

    synchronized void recordVerifiedTraffic(long nowMs) {
        pruneExpired(nowMs);
        if (successCount <= 0) successCount = 1;
        lastSuccessMs = nowMs;
        lastUpdateMs = nowMs;
        lastError = RouteError.NONE;
        cooldownUntilMs = 0L;
    }

    synchronized long cooldownUntilMs() {
        return cooldownUntilMs;
    }

    synchronized int scoreAdjustment() {
        int score = 0;
        score += Math.min(4, successCount) * 20;
        score -= totalFailures() * 35;
        int median = medianLatencyMs();
        if (median >= 0) {
            score += Math.max(-80, 80 - median / 5);
        }
        if (failureCount(RouteError.TOO_MANY_REQUESTS) > 0) score -= 60;
        if (failureCount(RouteError.TIMEOUT) > 0) score -= 50;
        if (failureCount(RouteError.RESET) > 0) score -= 45;
        return score;
    }

    synchronized boolean hasStableEvidence() {
        return successCount >= 3;
    }

    synchronized RouteStats copy() {
        RouteStats copy = new RouteStats();
        copy.successCount = successCount;
        copy.lastSuccessMs = lastSuccessMs;
        copy.lastUpdateMs = lastUpdateMs;
        copy.cooldownUntilMs = cooldownUntilMs;
        copy.lastError = lastError;
        copy.latencySamples.addAll(latencySamples);
        copy.failures.putAll(failures);
        return copy;
    }

    synchronized String toPersistedString() {
        StringBuilder out = new StringBuilder();
        out.append(successCount).append('|')
                .append(lastSuccessMs).append('|')
                .append(lastUpdateMs).append('|')
                .append(cooldownUntilMs).append('|')
                .append(lastError.name()).append('|');
        for (int i = 0; i < latencySamples.size(); i++) {
            if (i > 0) out.append(',');
            out.append(latencySamples.get(i));
        }
        out.append('|');
        boolean first = true;
        for (Map.Entry<RouteError, Integer> entry : failures.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(entry.getKey().name()).append(':').append(entry.getValue());
        }
        return out.toString();
    }

    static RouteStats fromPersistedString(String raw) {
        RouteStats stats = new RouteStats();
        if (raw == null || raw.trim().isEmpty()) return stats;
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 7) return stats;
        try {
            stats.successCount = intValue(parts[0]);
            stats.lastSuccessMs = longValue(parts[1]);
            stats.lastUpdateMs = longValue(parts[2]);
            stats.cooldownUntilMs = longValue(parts[3]);
            stats.lastError = RouteError.valueOf(parts[4]);
            if (!parts[5].isEmpty()) {
                for (String sample : parts[5].split(",")) {
                    int latency = intValue(sample);
                    if (latency >= 0) stats.latencySamples.add(latency);
                }
            }
            if (!parts[6].isEmpty()) {
                for (String item : parts[6].split(",")) {
                    String[] pair = item.split(":", -1);
                    if (pair.length != 2) continue;
                    RouteError error = RouteError.valueOf(pair[0]);
                    int count = intValue(pair[1]);
                    if (count > 0) stats.failures.put(error, count);
                }
            }
        } catch (Exception ignored) {
            return new RouteStats();
        }
        return stats;
    }

    private static long longValue(String value) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return 0L; }
    }

    private static int intValue(String value) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return 0; }
    }

    private static long cooldownDuration(RouteError error) {
        switch (error) {
            case TOO_MANY_REQUESTS:
                return COOLDOWN_429_MS;
            case HTTP_FORBIDDEN:
            case RELAY_AUTH:
                return COOLDOWN_DNS_TLS_MS;
            case HTTP_UNAVAILABLE:
            case WS_PROTOCOL:
            case RELAY_INIT:
            case FIRST_BYTE_TIMEOUT:
            case REMOTE_EOF:
                return COOLDOWN_IO_MS;
            case TIMEOUT:
                return COOLDOWN_TIMEOUT_MS;
            case RESET:
                return COOLDOWN_RESET_MS;
            case DNS:
            case TLS:
                return COOLDOWN_DNS_TLS_MS;
            case IO:
            case UNKNOWN:
                return COOLDOWN_IO_MS;
            case CANCELLED:
            case NONE:
            default:
                return 0L;
        }
    }
}
