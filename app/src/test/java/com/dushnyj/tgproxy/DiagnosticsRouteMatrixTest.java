package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosticsRouteMatrixTest {
    @Test
    public void matrixShowsConfiguredDcMainMediaAndEveryRouteType() {
        long nowMs = 1_700_000_000_000L;
        Map<Integer, String> dcRules = new LinkedHashMap<>();
        dcRules.put(2, "149.154.167.220");
        dcRules.put(4, "149.154.167.91");
        dcRules.put(203, "91.108.56.167");

        RouteEngine.Settings settings = RouteEngine.Settings.builder()
                .networkProfile(NetworkProfile.mobile("25020", "t2 Black"))
                .routePreference(RoutePreference.CLOUDFLARE_FIRST)
                .cfMode(MtProtoProxyEngine.CF_MODE_AUTO)
                .dcRedirects(dcRules)
                .workerDomains(Arrays.asList("worker.example"))
                .customCfDomains(Arrays.asList("custom.example"))
                .publicCfDomains(Arrays.asList("public.example"))
                .vpsRelay("Relay", "relay.example", 8443)
                .build();

        RouteStats workerStats = new RouteStats();
        workerStats.recordSuccess(nowMs - 1_000L, 84);
        RouteStats publicCfStats = new RouteStats();
        publicCfStats.recordFailure(RouteError.TOO_MANY_REQUESTS, nowMs - 500L);
        RouteStats directMediaStats = new RouteStats();
        directMediaStats.recordFailure(RouteError.TIMEOUT, nowMs - 250L);

        Map<String, RouteStats> stats = new LinkedHashMap<>();
        stats.put("worker:dc2", workerStats);
        stats.put("public_cf:dc2", publicCfStats);
        stats.put("direct_ws:dc2:media", directMediaStats);

        List<DiagnosticsRouteMatrix.Row> rows =
                DiagnosticsRouteMatrix.build(settings, stats, nowMs);
        String reportText = DiagnosticsRouteMatrix.toReportText(rows);

        assertTrue(reportText.contains("DC2 main"));
        assertTrue(reportText.contains("DC2 media"));
        assertTrue(reportText.contains("DC4 main"));
        assertTrue(reportText.contains("DC4 media"));
        assertTrue(reportText.contains("DC203 media"));
        assertFalse(reportText.contains("DC203 main"));

        assertTrue(reportText.contains("Direct WS"));
        assertTrue(reportText.contains("VPS Relay"));
        assertTrue(reportText.contains("Cloudflare Worker"));
        assertTrue(reportText.contains("Custom Cloudflare"));
        assertTrue(reportText.contains("Cloudflare CDN"));
        assertTrue(reportText.contains("TCP fallback"));

        assertTrue(reportText.contains("Cloudflare Worker: OK 84 ms"));
        assertTrue(reportText.contains("Cloudflare CDN: HTTP 429"));
        assertTrue(reportText.contains("Direct WS: timeout"));
        assertTrue(reportText.contains("VPS Relay: not checked"));
        assertTrue(reportText.contains("TCP fallback: not implemented"));
    }
}
