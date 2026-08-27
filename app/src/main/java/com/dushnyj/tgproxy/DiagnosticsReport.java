package com.dushnyj.tgproxy;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

final class DiagnosticsReport {
    private DiagnosticsReport() {
    }

    static String build(AppInfo appInfo, DiagnosticsSnapshot snapshot, AppSettings settings,
                        List<String> logs, long generatedAtMs) {
        return build(appInfo, snapshot, settings, Collections.emptyList(), logs, generatedAtMs);
    }

    static String build(AppInfo appInfo, DiagnosticsSnapshot snapshot, AppSettings settings,
                        List<DiagnosticsRouteMatrix.Row> routeMatrix,
                        List<String> logs, long generatedAtMs) {
        DiagnosticsSnapshot safeSnapshot = snapshot == null
                ? new DiagnosticsSnapshot(ServiceState.stopped(), NetworkProfile.defaultProfile(),
                Collections.emptyMap())
                : snapshot;
        AppSettings safeSettings = settings == null ? AppSettings.builder().build() : settings;
        AppInfo safeAppInfo = appInfo == null ? new AppInfo("", "", 0) : appInfo;

        StringBuilder out = new StringBuilder(4096);
        out.append("TG Proxy Diagnostics\n");
        line(out, "Generated", formatTimestamp(generatedAtMs));
        line(out, "Package", safeAppInfo.packageName);
        line(out, "Version", safeAppInfo.versionName + " (" + safeAppInfo.versionCode + ")");
        out.append('\n');

        appendDevice(out, safeAppInfo);
        appendStatus(out, safeSnapshot);
        appendNetwork(out, safeSnapshot, safeSettings);
        appendSettings(out, safeSettings);
        appendRoutes(out, safeSnapshot, generatedAtMs);
        appendRouteChecks(out, routeMatrix);
        appendErrors(out, safeSnapshot);
        appendLogs(out, logs);
        return out.toString().trim();
    }

    private static void appendStatus(StringBuilder out, DiagnosticsSnapshot snapshot) {
        ServiceState state = snapshot.serviceState();
        section(out, "Status");
        line(out, "Service", state.status().name());
        line(out, "Service started", yesNo(state.serviceStarted()));
        line(out, "Engine running", yesNo(state.engineRunning()));
        line(out, "Local port listening", yesNo(state.localPortListening()));
        line(out, "Paused", yesNo(state.paused()));
        line(out, "Uptime", formatDuration(snapshot.uptimeMs()));
        line(out, "Active connections", String.valueOf(snapshot.activeConnections()));
        line(out, "Total connections", String.valueOf(snapshot.totalConnections()));
        line(out, "Traffic up", TgConstants.humanBytes(snapshot.bytesUp()));
        line(out, "Traffic down", TgConstants.humanBytes(snapshot.bytesDown()));
        out.append('\n');
    }

    private static void appendDevice(StringBuilder out, AppInfo appInfo) {
        section(out, "Device");
        line(out, "Model", appInfo.deviceLabel());
        line(out, "Android", appInfo.androidLabel());
        out.append('\n');
    }

    private static void appendRouteChecks(StringBuilder out,
                                          List<DiagnosticsRouteMatrix.Row> routeMatrix) {
        section(out, "Route checks");
        out.append(DiagnosticsRouteMatrix.toReportText(routeMatrix)).append('\n');
        out.append('\n');
    }

    private static void appendNetwork(StringBuilder out, DiagnosticsSnapshot snapshot,
                                      AppSettings settings) {
        NetworkProfile profile = snapshot.networkProfile();
        section(out, "Network");
        line(out, "Kind", profile.kind().name());
        line(out, "Key", profile.key());
        line(out, "Name", valueOrDash(profile.defaultDisplayName()));
        line(out, "Profile name", valueOrDash(settings.profileName));
        line(out, "Route preference", valueOrDash(settings.routePreference));
        out.append('\n');
    }

    private static void appendSettings(StringBuilder out, AppSettings settings) {
        section(out, "Settings");
        line(out, "Local endpoint", valueOrDash(settings.localIp) + ":" + settings.localPort);
        line(out, "Secret", settings.secretConfigured ? "configured" : "not configured");
        line(out, "Telegram DC rules", compactMultiline(settings.dcRules));
        line(out, "Cloudflare CDN mode", valueOrDash(settings.cfMode));
        line(out, "Cloudflare custom domains", yesNo(settings.cfCustomDomains));
        line(out, "Cloudflare domains", joinOrDash(settings.cfDomains));
        line(out, "Cloudflare Worker domains", joinOrDash(settings.workerDomains));
        line(out, "VPS Relay", settings.vpsRelayEnabled ? "enabled" : "disabled");
        if (settings.vpsRelayEnabled || !settings.vpsRelayHost.isEmpty()) {
            line(out, "Relay name", valueOrDash(settings.vpsRelayName));
            line(out, "Relay endpoint", settings.relayEndpoint());
            line(out, "Relay token", settings.vpsRelayMaskedToken);
            line(out, "Relay profile", settings.vpsRelayProfileKey.isEmpty()
                    ? "all profiles" : settings.vpsRelayProfileKey);
        }
        line(out, "Connection warmup", yesNo(settings.cfWarmup));
        line(out, "Recheck on network change", yesNo(settings.recheckOnNetworkChange));
        line(out, "Smart sleep", yesNo(settings.smartSleep));
        line(out, "Autostart on app open", yesNo(settings.autostartOpen));
        line(out, "Autostart on boot", yesNo(settings.autostartBoot));
        line(out, "Theme", valueOrDash(settings.theme));
        line(out, "Language", valueOrDash(settings.language));
        line(out, "Updates", settings.checkUpdates ? "check on launch" : "manual");
        line(out, "Verbose logging", yesNo(settings.verboseLogging));
        out.append('\n');
    }

    private static void appendRoutes(StringBuilder out, DiagnosticsSnapshot snapshot, long nowMs) {
        RouteState route = snapshot.serviceState().routeState();
        section(out, "Routes");
        line(out, "Active", route != null && route.active() ? route.displayName() : "no active route");
        if (route != null && route.active()) {
            RouteCandidate candidate = route.candidate();
            line(out, "Key", route.key());
            line(out, "Type", route.type() == null ? "-" : route.type().name());
            line(out, "Endpoint", valueOrDash(route.activeEndpoint()));
            if (!route.activeSni().isEmpty()) line(out, "SNI", route.activeSni());
            if (candidate != null) {
                line(out, "Candidate endpoint", valueOrDash(candidate.endpoint()));
                line(out, "DC", String.valueOf(candidate.dc()));
                line(out, "Media", yesNo(candidate.media()));
                line(out, "Enabled", yesNo(candidate.enabled()));
                if (!candidate.disabledReason().isEmpty()) {
                    line(out, "Disabled reason", candidate.disabledReason());
                }
            }
            line(out, "Ping", route.pingMs() >= 0 ? route.pingMs() + " ms" : "-");
            line(out, "Quality", valueOrDash(route.quality()));
        } else if (route != null && !route.reason().isEmpty()) {
            line(out, "Reason", route.reason());
        }

        out.append("Route statistics\n");
        if (snapshot.routeStats().isEmpty()) {
            out.append("- no route statistics\n");
        } else {
            for (Map.Entry<String, RouteStats> entry : snapshot.routeStats().entrySet()) {
                RouteStats stats = entry.getValue();
                StringBuilder row = new StringBuilder();
                row.append("success=").append(stats.successCount())
                        .append(", failures=").append(stats.totalFailures())
                        .append(", median=").append(formatLatency(stats.medianLatencyMs()))
                        .append(", lastError=").append(stats.lastError().name())
                        .append(", coolingDown=").append(yesNo(stats.isCoolingDown(nowMs)))
                        .append(", lastUpdate=").append(formatOptionalTimestamp(stats.lastUpdateMs()));
                String failures = failureBreakdown(stats);
                if (!failures.isEmpty()) row.append(", failureTypes=").append(failures);
                line(out, entry.getKey(), row.toString());
            }
        }
        out.append('\n');
    }

    private static void appendErrors(StringBuilder out, DiagnosticsSnapshot snapshot) {
        section(out, "Errors");
        line(out, "Engine errors", String.valueOf(snapshot.engineErrors()));
        line(out, "Route failures", String.valueOf(snapshot.routeFailures()));
        RouteError last = lastRouteError(snapshot.routeStats());
        line(out, "Last route error", last == RouteError.NONE ? "-" : last.name());
        RouteState route = snapshot.serviceState().routeState();
        if (route != null && !route.active() && !route.reason().isEmpty()) {
            line(out, "Route state reason", route.reason());
        }
        out.append('\n');
    }

    private static void appendLogs(StringBuilder out, List<String> logs) {
        section(out, "Logs");
        if (logs == null || logs.isEmpty()) {
            out.append("- no recent app events\n");
            return;
        }
        for (String log : logs) {
            String value = compactMultiline(log);
            if (!value.isEmpty()) out.append("- ").append(value).append('\n');
        }
    }

    private static RouteError lastRouteError(Map<String, RouteStats> routeStats) {
        RouteError last = RouteError.NONE;
        long lastUpdate = 0L;
        for (RouteStats stats : routeStats.values()) {
            RouteError error = stats.lastError();
            if (error == RouteError.NONE) continue;
            long update = stats.lastUpdateMs();
            if (update >= lastUpdate) {
                lastUpdate = update;
                last = error;
            }
        }
        return last;
    }

    private static String failureBreakdown(RouteStats stats) {
        StringBuilder out = new StringBuilder();
        for (RouteError error : RouteError.values()) {
            if (error == RouteError.NONE) continue;
            int count = stats.failureCount(error);
            if (count <= 0) continue;
            if (out.length() > 0) out.append(',');
            out.append(error.name()).append('=').append(count);
        }
        return out.toString();
    }

    private static String formatTimestamp(long timeMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(Math.max(0L, timeMs)));
    }

    private static String formatOptionalTimestamp(long timeMs) {
        return timeMs <= 0L ? "-" : formatTimestamp(timeMs);
    }

    private static String formatDuration(long ms) {
        if (ms <= 0L) return "0s";
        long seconds = ms / 1000L;
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        StringBuilder out = new StringBuilder();
        if (days > 0L) out.append(days).append("d ");
        if (hours > 0L || out.length() > 0) out.append(hours).append("h ");
        if (minutes > 0L || out.length() > 0) out.append(minutes).append("m ");
        out.append(seconds).append('s');
        return out.toString();
    }

    private static String formatLatency(int ms) {
        return ms < 0 ? "-" : ms + "ms";
    }

    private static void section(StringBuilder out, String name) {
        out.append(name).append('\n');
    }

    private static void line(StringBuilder out, String label, String value) {
        out.append("- ").append(label).append(": ").append(valueOrDash(value)).append('\n');
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String valueOrDash(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? "-" : normalized;
    }

    private static String compactMultiline(String value) {
        if (value == null) return "";
        return value.trim().replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ");
    }

    private static String joinOrDash(List<String> values) {
        if (values == null || values.isEmpty()) return "-";
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            String item = compactMultiline(value);
            if (item.isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(item);
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    static final class AppInfo {
        final String packageName;
        final String versionName;
        final int versionCode;
        final String deviceManufacturer;
        final String deviceModel;
        final String androidVersion;
        final int androidSdk;

        AppInfo(String packageName, String versionName, int versionCode) {
            this(packageName, versionName, versionCode, "", "", "", 0);
        }

        AppInfo(String packageName, String versionName, int versionCode,
                String deviceManufacturer, String deviceModel,
                String androidVersion, int androidSdk) {
            this.packageName = packageName == null ? "" : packageName;
            this.versionName = versionName == null ? "" : versionName;
            this.versionCode = Math.max(0, versionCode);
            this.deviceManufacturer = deviceManufacturer == null ? "" : deviceManufacturer.trim();
            this.deviceModel = deviceModel == null ? "" : deviceModel.trim();
            this.androidVersion = androidVersion == null ? "" : androidVersion.trim();
            this.androidSdk = Math.max(0, androidSdk);
        }

        String deviceLabel() {
            String manufacturer = valueOrDash(deviceManufacturer);
            String model = valueOrDash(deviceModel);
            if ("-".equals(manufacturer)) return model;
            if ("-".equals(model)) return manufacturer;
            return model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))
                    ? model : manufacturer + " " + model;
        }

        String androidLabel() {
            String version = valueOrDash(androidVersion);
            return androidSdk > 0 ? version + " (SDK " + androidSdk + ")" : version;
        }
    }

    static final class AppSettings {
        final String localIp;
        final int localPort;
        final boolean secretConfigured;
        final String dcRules;
        final String cfMode;
        final boolean cfCustomDomains;
        final List<String> cfDomains;
        final List<String> workerDomains;
        final boolean vpsRelayEnabled;
        final String vpsRelayName;
        final String vpsRelayHost;
        final int vpsRelayPort;
        final boolean vpsRelayTls;
        final String vpsRelayPath;
        final String vpsRelayMaskedToken;
        final String vpsRelayProfileKey;
        final String profileName;
        final String routePreference;
        final boolean cfWarmup;
        final boolean recheckOnNetworkChange;
        final boolean smartSleep;
        final boolean autostartOpen;
        final boolean autostartBoot;
        final String theme;
        final String language;
        final boolean checkUpdates;
        final boolean verboseLogging;

        private AppSettings(Builder builder) {
            localIp = valueOrDash(builder.localIp);
            localPort = builder.localPort <= 0 ? MtProtoConfig.DEFAULT_PORT : builder.localPort;
            secretConfigured = builder.secretConfigured;
            dcRules = valueOrDash(builder.dcRules);
            cfMode = valueOrDash(builder.cfMode);
            cfCustomDomains = builder.cfCustomDomains;
            cfDomains = immutableList(builder.cfDomains);
            workerDomains = immutableList(builder.workerDomains);
            vpsRelayEnabled = builder.vpsRelayEnabled;
            vpsRelayName = compactMultiline(builder.vpsRelayName);
            vpsRelayHost = compactMultiline(builder.vpsRelayHost);
            vpsRelayPort = builder.vpsRelayPort;
            vpsRelayTls = builder.vpsRelayTls;
            vpsRelayPath = normalizeRelayPath(builder.vpsRelayPath);
            vpsRelayMaskedToken = maskRelayToken(builder.vpsRelayToken);
            vpsRelayProfileKey = compactMultiline(builder.vpsRelayProfileKey);
            profileName = valueOrDash(builder.profileName);
            routePreference = valueOrDash(builder.routePreference);
            cfWarmup = builder.cfWarmup;
            recheckOnNetworkChange = builder.recheckOnNetworkChange;
            smartSleep = builder.smartSleep;
            autostartOpen = builder.autostartOpen;
            autostartBoot = builder.autostartBoot;
            theme = valueOrDash(builder.theme);
            language = valueOrDash(builder.language);
            checkUpdates = builder.checkUpdates;
            verboseLogging = builder.verboseLogging;
        }

        static Builder builder() {
            return new Builder();
        }

        String relayEndpoint() {
            if (vpsRelayHost.isEmpty() || vpsRelayPort <= 0) return "-";
            String transport = vpsRelayTls ? "TLS" : "plain";
            String path = vpsRelayPath.isEmpty() ? "" : vpsRelayPath;
            return transport + " " + vpsRelayHost + ":" + vpsRelayPort
                    + path;
        }

        static final class Builder {
            private String localIp = MtProtoConfig.DEFAULT_HOST;
            private int localPort = MtProtoConfig.DEFAULT_PORT;
            private boolean secretConfigured;
            private String dcRules = MtProtoConfig.DEFAULT_DC_RULES;
            private String cfMode = MtProtoProxyEngine.CF_MODE_AUTO;
            private boolean cfCustomDomains;
            private List<String> cfDomains = Collections.emptyList();
            private List<String> workerDomains = Collections.emptyList();
            private boolean vpsRelayEnabled;
            private String vpsRelayName = "";
            private String vpsRelayHost = "";
            private int vpsRelayPort;
            private boolean vpsRelayTls = true;
            private String vpsRelayPath = "";
            private String vpsRelayToken = "";
            private String vpsRelayProfileKey = "";
            private String profileName = "";
            private String routePreference = "";
            private boolean cfWarmup = true;
            private boolean recheckOnNetworkChange = true;
            private boolean smartSleep = TgRoutePolicy.DEFAULT_SMART_SLEEP;
            private boolean autostartOpen;
            private boolean autostartBoot;
            private String theme = "system";
            private String language = "system";
            private boolean checkUpdates = true;
            private boolean verboseLogging;

            Builder localEndpoint(String ip, int port) {
                localIp = ip;
                localPort = port;
                return this;
            }

            Builder secretConfigured(boolean configured) {
                secretConfigured = configured;
                return this;
            }

            Builder dcRules(String rules) {
                dcRules = rules;
                return this;
            }

            Builder cfMode(String mode) {
                cfMode = mode;
                return this;
            }

            Builder cfCustomDomains(boolean enabled) {
                cfCustomDomains = enabled;
                return this;
            }

            Builder cfDomains(List<String> domains) {
                cfDomains = domains;
                return this;
            }

            Builder workerDomains(List<String> domains) {
                workerDomains = domains;
                return this;
            }

            Builder vpsRelay(boolean enabled, String name, String host, int port,
                             boolean tls, String path, String token, String profileKey) {
                vpsRelayEnabled = enabled;
                vpsRelayName = name;
                vpsRelayHost = host;
                vpsRelayPort = port;
                vpsRelayTls = tls;
                vpsRelayPath = path;
                vpsRelayToken = token;
                vpsRelayProfileKey = profileKey;
                return this;
            }

            Builder profileName(String name) {
                profileName = name;
                return this;
            }

            Builder routePreference(String preference) {
                routePreference = preference;
                return this;
            }

            Builder cfWarmup(boolean enabled) {
                cfWarmup = enabled;
                return this;
            }

            Builder recheckOnNetworkChange(boolean enabled) {
                recheckOnNetworkChange = enabled;
                return this;
            }

            Builder smartSleep(boolean enabled) {
                smartSleep = enabled;
                return this;
            }

            Builder autostartOpen(boolean enabled) {
                autostartOpen = enabled;
                return this;
            }

            Builder autostartBoot(boolean enabled) {
                autostartBoot = enabled;
                return this;
            }

            Builder theme(String value) {
                theme = value;
                return this;
            }

            Builder language(String value) {
                language = value;
                return this;
            }

            Builder checkUpdates(boolean enabled) {
                checkUpdates = enabled;
                return this;
            }

            Builder verboseLogging(boolean enabled) {
                verboseLogging = enabled;
                return this;
            }

            AppSettings build() {
                return new AppSettings(this);
            }
        }

        private static List<String> immutableList(List<String> source) {
            if (source == null || source.isEmpty()) return Collections.emptyList();
            ArrayList<String> copy = new ArrayList<>();
            for (String value : source) {
                String normalized = compactMultiline(value);
                if (!normalized.isEmpty()) copy.add(normalized);
            }
            return Collections.unmodifiableList(copy);
        }

        private static String normalizeRelayPath(String path) {
            String value = compactMultiline(path);
            if (value.isEmpty()) return "";
            return value.startsWith("/") ? value : "/" + value;
        }

        private static String maskRelayToken(String token) {
            String value = compactMultiline(token);
            if (value.isEmpty()) return "";
            if (value.contains("****")) return value;
            if (value.length() <= 8) return "****";
            return value.substring(0, Math.min(5, value.length()))
                    + "****_"
                    + value.substring(value.length() - 4);
        }
    }
}
