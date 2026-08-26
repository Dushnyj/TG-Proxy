package com.dushnyj.tgproxy;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class FlowsealConnectivity {
    public static final int[] TEST_DCS = {1, 2, 3, 4, 5, 203};

    private static final Map<Integer, String> DEFAULT_DC_IPS = new LinkedHashMap<>();
    static {
        DEFAULT_DC_IPS.put(1, "149.154.175.50");
        DEFAULT_DC_IPS.put(2, "149.154.167.51");
        DEFAULT_DC_IPS.put(3, "149.154.175.100");
        DEFAULT_DC_IPS.put(4, "149.154.167.91");
        DEFAULT_DC_IPS.put(5, "149.154.171.5");
        DEFAULT_DC_IPS.put(203, "91.105.192.100");
    }

    public interface Callback {
        void onResult(Result result);
    }

    public static void testCfProxyAuto(List<String> domains, Callback callback) {
        new Thread(() -> {
            Result merged = new Result("");
            String bestDomain = "";
            List<String> pool = domains == null || domains.isEmpty()
                    ? FlowsealCfDomains.defaults() : domains;
            CfProxyDomainState domainState = CfProxyDomainState.shared();
            for (String domain : domainState.orderedDomains(pool, System.currentTimeMillis())) {
                Result current = testCfProxyDomain(domain);
                if (current.allOk()) {
                    domainState.markSuccess(domain, System.currentTimeMillis());
                    callback.onResult(current);
                    return;
                }
                if (current.anyOk()) {
                    domainState.markSuccess(domain, System.currentTimeMillis());
                }
                for (Map.Entry<Integer, String> entry : current.statuses.entrySet()) {
                    if ("OK".equals(entry.getValue())) {
                        merged.statuses.put(entry.getKey(), "OK");
                        bestDomain = domain;
                    } else if (!merged.statuses.containsKey(entry.getKey())) {
                        merged.statuses.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            merged.domain = bestDomain;
            callback.onResult(merged);
        }, "tg-cfproxy-test").start();
    }

    public static void testCfProxyDomains(List<String> domains, MultiCallback callback) {
        new Thread(() -> {
            LinkedHashMap<String, Result> results = new LinkedHashMap<>();
            for (String domain : domains) {
                Result result = testCfProxyDomain(domain);
                if (result.anyOk()) {
                    CfProxyDomainState.shared().markSuccess(domain, System.currentTimeMillis());
                }
                results.put(domain, result);
            }
            callback.onResult(results);
        }, "tg-cfproxy-multi-test").start();
    }

    public static void testWorkerDomains(List<String> domains, MultiCallback callback) {
        new Thread(() -> {
            LinkedHashMap<String, Result> results = new LinkedHashMap<>();
            for (String domain : domains) {
                results.put(domain, testWorkerDomain(domain));
            }
            callback.onResult(results);
        }, "tg-cfworker-test").start();
    }

    public interface MultiCallback {
        void onResult(Map<String, Result> results);
    }

    static List<Probe> cfProxyCases(String domain) {
        ArrayList<Probe> result = new ArrayList<>();
        for (int dc : TEST_DCS) {
            String host = "kws" + dc + "." + domain;
            String mediaHost = host;
            result.add(new Probe(dc, host, host, host, "/apiws",
                    mediaHost, mediaHost, "/apiws"));
        }
        return result;
    }

    static List<Probe> workerCases(String domain) {
        ArrayList<Probe> result = new ArrayList<>();
        for (int dc : TEST_DCS) {
            String dst = DEFAULT_DC_IPS.get(dc);
            String path = "/apiws?dst=" + url(dst) + "&dc=" + dc + "&media=0";
            String mediaPath = "/apiws?dst=" + url(dst) + "&dc=" + dc + "&media=1";
            result.add(new Probe(dc, domain, domain, domain, path,
                    domain, domain, mediaPath));
        }
        return result;
    }

    private static Result testCfProxyDomain(String domain) {
        return runCases(domain, cfProxyCases(domain));
    }

    private static Result testWorkerDomain(String domain) {
        return runCases(domain, workerCases(domain));
    }

    private static Result runCases(String domain, List<Probe> probes) {
        Result result = new Result(domain);
        if (probes == null || probes.isEmpty()) return result;
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, probes.size()));
        ArrayList<Future<ProbeResult>> futures = new ArrayList<>();
        for (Probe probe : probes) {
            futures.add(executor.submit((Callable<ProbeResult>) () -> testProbe(domain, probe)));
        }
        for (Future<ProbeResult> future : futures) {
            try {
                ProbeResult checked = future.get();
                result.statuses.put(checked.dc, checked.status);
            } catch (Exception e) {
                result.statuses.put(0, trim(e.getClass().getSimpleName()));
            }
        }
        executor.shutdownNow();
        return result;
    }

    private static ProbeResult testProbe(String baseDomain, Probe probe) {
        String main = testProbeScope(baseDomain, probe, false);
        String media = testProbeScope(baseDomain, probe, true);
        if ("OK".equals(main) && "OK".equals(media)) return new ProbeResult(probe.dc, "OK");
        if (!"OK".equals(main) && !"OK".equals(media)) {
            return new ProbeResult(probe.dc, "main: " + main + "; media: " + media);
        }
        return new ProbeResult(probe.dc, "OK".equals(main)
                ? "media: " + media : "main: " + main);
    }

    private static String testProbeScope(String baseDomain, Probe probe, boolean media) {
        RawWebSocket ws = null;
        try {
            ws = RawWebSocket.connect(
                    media ? probe.mediaConnectHost : probe.connectHost,
                    media ? probe.mediaSniHost : probe.sniHost,
                    5000,
                    media ? probe.mediaPath : probe.path);
            RouteProbeClient.verifyTelegramDcResponse(ws, probe.dc, media, 5000);
            return "OK";
        } catch (Exception error) {
            if (CfProxyDomainState.isTooManyRequests(error)) {
                CfProxyDomainState.shared().markTooManyRequests(
                        baseDomain, System.currentTimeMillis());
            }
            String message = error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage();
            return trim(message);
        } finally {
            if (ws != null) try { ws.abort(); } catch (Exception ignored) {}
        }
    }

    private static String trim(String value) {
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() > 60 ? normalized.substring(0, 60) : normalized;
    }

    private static String url(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    public static final class Probe {
        public final int dc;
        public final String connectHost;
        public final String sniHost;
        public final String requestHost;
        public final String path;
        public final String mediaConnectHost;
        public final String mediaSniHost;
        public final String mediaPath;

        Probe(int dc, String connectHost, String sniHost, String requestHost, String path,
              String mediaConnectHost, String mediaSniHost, String mediaPath) {
            this.dc = dc;
            this.connectHost = connectHost;
            this.sniHost = sniHost;
            this.requestHost = requestHost;
            this.path = path;
            this.mediaConnectHost = mediaConnectHost;
            this.mediaSniHost = mediaSniHost;
            this.mediaPath = mediaPath;
        }
    }

    private static final class ProbeResult {
        final int dc;
        final String status;

        ProbeResult(int dc, String status) {
            this.dc = dc;
            this.status = status == null ? "failed" : status;
        }
    }

    public static final class Result {
        public String domain;
        public final LinkedHashMap<Integer, String> statuses = new LinkedHashMap<>();

        Result(String domain) {
            this.domain = domain == null ? "" : domain;
        }

        public int okCount() {
            int count = 0;
            for (String status : statuses.values()) {
                if ("OK".equals(status)) count++;
            }
            return count;
        }

        public boolean anyOk() {
            return okCount() > 0;
        }

        public boolean allOk() {
            return okCount() == TEST_DCS.length;
        }

        public String okLabels(String prefix) {
            ArrayList<String> labels = new ArrayList<>();
            for (Map.Entry<Integer, String> entry : statuses.entrySet()) {
                if ("OK".equals(entry.getValue())) labels.add(prefix + entry.getKey());
            }
            return join(labels);
        }

        public String failDetails(String prefix) {
            ArrayList<String> lines = new ArrayList<>();
            for (Map.Entry<Integer, String> entry : statuses.entrySet()) {
                if (!"OK".equals(entry.getValue())) {
                    lines.add(String.format(Locale.US, "%s%d: %s",
                            prefix, entry.getKey(), entry.getValue()));
                }
            }
            return join(lines);
        }
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(value);
        }
        return sb.toString();
    }

    private FlowsealConnectivity() {}
}
