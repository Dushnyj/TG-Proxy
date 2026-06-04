package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosticsReportTest {
    @Test
    public void reportIncludesRuntimeSettingsRoutesErrorsAndLogs() {
        RouteCandidate route = RouteCandidate.publicCloudflare(2, "cloudflare");
        ServiceState serviceState = ServiceState.from(true, true, true, false,
                RouteState.active(route, "lovetrue.co.uk", 261, "stable"));
        RouteStats stats = new RouteStats();
        stats.recordSuccess(1_700_000_000_000L, 250);
        stats.recordSuccess(1_700_000_001_000L, 270);
        stats.recordFailure(RouteError.TIMEOUT, 1_700_000_002_000L);
        Map<String, RouteStats> routeStats = new LinkedHashMap<>();
        routeStats.put(route.key(), stats);

        DiagnosticsSnapshot snapshot = new DiagnosticsSnapshot(
                serviceState,
                NetworkProfile.mobile("25020", "t2 Black"),
                routeStats,
                7L,
                2L,
                1024L,
                2048L,
                3L,
                4L,
                65_000L);
        DiagnosticsReport.AppSettings settings = DiagnosticsReport.AppSettings.builder()
                .localEndpoint("127.0.0.1", 1443)
                .secretConfigured(true)
                .dcRules("2:149.154.167.220")
                .cfMode(MtProtoProxyEngine.CF_MODE_AUTO)
                .cfCustomDomains(false)
                .cfDomains(Arrays.asList("cloudflare.example"))
                .workerDomains(Arrays.asList("worker.example"))
                .profileName("t2 Black")
                .routePreference("Cloudflare first")
                .cfWarmup(true)
                .recheckOnNetworkChange(true)
                .smartSleep(false)
                .autostartOpen(true)
                .autostartBoot(false)
                .theme("dark")
                .language("en")
                .checkUpdates(true)
                .verboseLogging(true)
                .vpsRelay(true, "Work Relay", "relay.example.com", 8443,
                        true, "/apiws", "tgpr_****_3456", "wifi:ssid:work")
                .build();

        String report = DiagnosticsReport.build(
                new DiagnosticsReport.AppInfo("com.dushnyj.tgproxy", "1.0.1", 10001,
                        "Xiaomi", "Redmi Note 8 Pro", "11", 30),
                snapshot,
                settings,
                Arrays.asList("service started", "route failure public_cf:dc2 TIMEOUT"),
                1_700_000_003_000L);

        assertTrue(report.contains("TG Proxy Diagnostics"));
        assertTrue(report.contains("Package: com.dushnyj.tgproxy"));
        assertTrue(report.contains("Status"));
        assertTrue(report.contains("Device"));
        assertTrue(report.contains("Xiaomi Redmi Note 8 Pro"));
        assertTrue(report.contains("Android: 11 (SDK 30)"));
        assertTrue(report.contains("ACTIVE"));
        assertTrue(report.contains("Network"));
        assertTrue(report.contains("mobile:mccmnc:25020"));
        assertTrue(report.contains("Settings"));
        assertTrue(report.contains("127.0.0.1:1443"));
        assertTrue(report.contains("Secret: configured"));
        assertTrue(report.contains("VPS Relay: enabled"));
        assertTrue(report.contains("relay.example.com:8443"));
        assertTrue(report.contains("Relay token: tgpr_****_3456"));
        assertTrue(report.contains("Routes"));
        assertTrue(report.contains("public_cf:dc2"));
        assertTrue(report.contains("lovetrue.co.uk"));
        assertTrue(report.contains("Errors"));
        assertTrue(report.contains("TIMEOUT"));
        assertTrue(report.contains("Logs"));
        assertTrue(report.contains("service started"));
        assertFalse(report.contains("0123456789abcdef0123456789abcdef"));
        assertFalse(report.contains("tgpr_abcdef123456"));
    }

    @Test
    public void reportIncludesRouteCheckMatrixWhenProvided() {
        long nowMs = 1_700_000_003_000L;
        Map<Integer, String> dcRules = new LinkedHashMap<>();
        dcRules.put(2, "149.154.167.220");
        RouteEngine.Settings routeSettings = RouteEngine.Settings.builder()
                .dcRedirects(dcRules)
                .publicCfDomains(Arrays.asList("cloudflare.example"))
                .build();
        RouteStats publicCfStats = new RouteStats();
        publicCfStats.recordFailure(RouteError.TOO_MANY_REQUESTS, nowMs - 100L);
        Map<String, RouteStats> routeStats = new LinkedHashMap<>();
        routeStats.put("public_cf:dc2", publicCfStats);

        List<DiagnosticsRouteMatrix.Row> routeMatrix =
                DiagnosticsRouteMatrix.build(routeSettings, routeStats, nowMs);
        DiagnosticsSnapshot snapshot = new DiagnosticsSnapshot(
                ServiceState.stopped(),
                NetworkProfile.defaultProfile(),
                routeStats);

        String report = DiagnosticsReport.build(
                new DiagnosticsReport.AppInfo("com.dushnyj.tgproxy", "1.0.1", 10001),
                snapshot,
                DiagnosticsReport.AppSettings.builder().build(),
                routeMatrix,
                Arrays.asList("route failure public_cf:dc2 TOO_MANY_REQUESTS"),
                nowMs);

        assertTrue(report.contains("Route checks"));
        assertTrue(report.contains("DC2 main"));
        assertTrue(report.contains("Cloudflare CDN: HTTP 429"));
        assertTrue(report.contains("TCP fallback: not implemented"));
    }
}
