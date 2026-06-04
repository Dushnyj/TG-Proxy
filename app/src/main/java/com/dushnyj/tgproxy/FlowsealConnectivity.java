package com.dushnyj.tgproxy;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
            for (int i = pool.size() - 1; i >= 0; i--) {
                String domain = pool.get(i);
                Result current = testCfProxyDomain(domain);
                if (current.allOk()) {
                    callback.onResult(current);
                    return;
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
                results.put(domain, testCfProxyDomain(domain));
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
            result.add(new Probe(dc, host, host, host, "/apiws"));
        }
        return result;
    }

    static List<Probe> workerCases(String domain) {
        ArrayList<Probe> result = new ArrayList<>();
        for (int dc : TEST_DCS) {
            String dst = DEFAULT_DC_IPS.get(dc);
            String path = "/apiws?dst=" + url(dst) + "&dc=" + dc + "&media=0";
            result.add(new Probe(dc, domain, domain, domain, path));
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
        for (Probe probe : probes) {
            try {
                RawWebSocket ws = RawWebSocket.connect(
                        probe.connectHost,
                        probe.sniHost,
                        5000,
                        probe.path,
                        true);
                ws.close();
                result.statuses.put(probe.dc, "OK");
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                result.statuses.put(probe.dc, trim(msg));
            }
        }
        return result;
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

        Probe(int dc, String connectHost, String sniHost, String requestHost, String path) {
            this.dc = dc;
            this.connectHost = connectHost;
            this.sniHost = sniHost;
            this.requestHost = requestHost;
            this.path = path;
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
