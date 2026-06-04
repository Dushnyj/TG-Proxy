package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class DiagnosticsRouteMatrix {
    private static final RouteType[] ROUTE_ORDER = {
            RouteType.DIRECT_WS,
            RouteType.VPS_RELAY,
            RouteType.WORKER,
            RouteType.CUSTOM_CLOUDFLARE,
            RouteType.PUBLIC_CLOUDFLARE,
            RouteType.TCP_FALLBACK
    };

    private DiagnosticsRouteMatrix() {
    }

    static List<Row> build(RouteEngine.Settings settings, Map<String, RouteStats> statsByRoute,
                           long nowMs) {
        RouteEngine.Settings safeSettings = settings == null
                ? RouteEngine.Settings.builder().build() : settings;
        Map<String, RouteStats> safeStats = statsByRoute == null
                ? Collections.emptyMap() : statsByRoute;
        Map<Integer, String> dcRedirects = safeSettings.dcRedirects();
        ArrayList<Row> rows = new ArrayList<>();
        for (Integer dc : dcRedirects.keySet()) {
            if (dc == null || dc <= 0) continue;
            if (dc == 203) {
                rows.add(buildRow(safeSettings, dc, true, safeStats, nowMs));
            } else {
                rows.add(buildRow(safeSettings, dc, false, safeStats, nowMs));
                rows.add(buildRow(safeSettings, dc, true, safeStats, nowMs));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    static String toReportText(List<Row> rows) {
        if (rows == null || rows.isEmpty()) return "- no route candidates\n";
        StringBuilder out = new StringBuilder();
        for (Row row : rows) {
            if (row == null) continue;
            out.append(row.scopeLabel()).append('\n');
            for (Cell cell : row.cells()) {
                out.append("- ").append(cell.routeLabel()).append(": ").append(cell.status());
                if (!cell.endpoint().isEmpty()) {
                    out.append(" (").append(cell.endpoint()).append(')');
                }
                out.append('\n');
            }
        }
        return out.toString().trim();
    }

    private static Row buildRow(RouteEngine.Settings settings, int dc, boolean media,
                                Map<String, RouteStats> statsByRoute, long nowMs) {
        Map<RouteType, RouteCandidate> candidates = byRouteType(
                new RouteEngine().buildCandidates(settings, dc, media));
        ArrayList<Cell> cells = new ArrayList<>();
        for (RouteType type : ROUTE_ORDER) {
            RouteCandidate candidate = candidates.get(type);
            if (candidate == null || type == RouteType.TCP_FALLBACK) {
                cells.add(Cell.missing(type, missingStatus(settings, type, dc, media)));
            } else if (!candidate.enabled()) {
                cells.add(Cell.from(candidate, valueOr(candidate.disabledReason(), "not configured")));
            } else {
                RouteStats stats = statsByRoute.get(candidate.key());
                cells.add(Cell.from(candidate, status(stats, nowMs)));
            }
        }
        return new Row(dc, media, cells);
    }

    private static Map<RouteType, RouteCandidate> byRouteType(List<RouteCandidate> candidates) {
        EnumMap<RouteType, RouteCandidate> result = new EnumMap<>(RouteType.class);
        if (candidates == null) return result;
        for (RouteCandidate candidate : candidates) {
            if (candidate == null || result.containsKey(candidate.type())) continue;
            result.put(candidate.type(), candidate);
        }
        return result;
    }

    private static String status(RouteStats stats, long nowMs) {
        if (stats == null) return "not checked";
        RouteStats copy = stats.copy();
        copy.pruneExpired(nowMs);
        RouteError lastError = copy.lastError();
        if (lastError != RouteError.NONE) return errorLabel(lastError);
        if (copy.successCount() > 0) {
            int median = copy.medianLatencyMs();
            return median >= 0 ? "OK " + median + " ms" : "OK";
        }
        return "not checked";
    }

    private static String missingStatus(RouteEngine.Settings settings, RouteType type,
                                        int dc, boolean media) {
        switch (type) {
            case DIRECT_WS:
                if (!settings.dcRedirects().containsKey(dc)) return "not configured";
                return TgRoutePolicy.shouldUseDirectWs(dc, media, settings.dcRedirects())
                        ? "not configured" : "not available";
            case VPS_RELAY:
                return settings.vpsRelayEnabled() ? "not configured" : "not configured";
            case WORKER:
                return settings.workerDomains().isEmpty() ? "not configured" : "not configured";
            case CUSTOM_CLOUDFLARE:
                if (MtProtoProxyEngine.CF_MODE_OFF.equals(settings.cfMode())) return "off";
                return settings.customCfDomains().isEmpty() ? "not configured" : "not configured";
            case PUBLIC_CLOUDFLARE:
                if (MtProtoProxyEngine.CF_MODE_OFF.equals(settings.cfMode())) return "off";
                return settings.publicCfDomains().isEmpty() ? "not configured" : "not configured";
            case TCP_FALLBACK:
            default:
                return "not implemented";
        }
    }

    static String errorLabel(RouteError error) {
        switch (error) {
            case TOO_MANY_REQUESTS:
                return "HTTP 429";
            case TIMEOUT:
                return "timeout";
            case RESET:
                return "connection reset";
            case DNS:
                return "DNS error";
            case TLS:
                return "TLS error";
            case IO:
                return "network I/O error";
            case UNKNOWN:
                return "unknown error";
            case NONE:
            default:
                return "not checked";
        }
    }

    private static String valueOr(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    static final class Row {
        private final int dc;
        private final boolean media;
        private final List<Cell> cells;

        private Row(int dc, boolean media, List<Cell> cells) {
            this.dc = dc;
            this.media = media;
            this.cells = Collections.unmodifiableList(new ArrayList<>(cells));
        }

        String scopeLabel() {
            return "DC" + dc + (media ? " media" : " main");
        }

        List<Cell> cells() {
            return cells;
        }
    }

    static final class Cell {
        private final RouteType type;
        private final String endpoint;
        private final String status;

        private Cell(RouteType type, String endpoint, String status) {
            this.type = type;
            this.endpoint = valueOr(endpoint, "");
            this.status = valueOr(status, "not checked");
        }

        static Cell from(RouteCandidate candidate, String status) {
            return new Cell(candidate.type(), candidate.endpoint(), status);
        }

        static Cell missing(RouteType type, String status) {
            return new Cell(type, "", status);
        }

        String routeLabel() {
            return type.displayName();
        }

        String endpoint() {
            return endpoint;
        }

        String status() {
            return status;
        }
    }
}
