package com.dushnyj.tgproxy;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MtProtoProxyEngine {
    static final String ROUTE_WORKER = "worker";
    static final String ROUTE_CF_PROXY = "cf_proxy";
    static final String ROUTE_DIRECT = "direct";
    static final String ROUTE_VPS_RELAY = "vps_relay";
    static final String CF_MODE_AUTO = "auto";
    static final String CF_MODE_ON = "on";
    static final String CF_MODE_OFF = "off";

    private static final int HANDSHAKE_LEN = 64;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int CLIENT_READ_TIMEOUT_MS = 90_000;
    private static final int CF_POOL_SIZE_PER_DC = 1;
    private static final int[] TELEGRAM_DCS = {1, 2, 3, 4, 5};

    private static final Map<Integer, String> DEFAULT_DC_IPS = new LinkedHashMap<>();
    static {
        DEFAULT_DC_IPS.put(1, "149.154.175.50");
        DEFAULT_DC_IPS.put(2, "149.154.167.51");
        DEFAULT_DC_IPS.put(3, "149.154.175.100");
        DEFAULT_DC_IPS.put(4, "149.154.167.91");
        DEFAULT_DC_IPS.put(5, "149.154.171.5");
        DEFAULT_DC_IPS.put(203, "91.105.192.100");
    }

    public final AtomicLong bytesUp = new AtomicLong();
    public final AtomicLong bytesDown = new AtomicLong();
    public final AtomicLong connections = new AtomicLong();
    public final AtomicLong errors = new AtomicLong();

    private final ConcurrentHashMap<Socket, Boolean> activeSockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RouteStats> routeStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeRouteByScope = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeEndpointByScope = new ConcurrentHashMap<>();
    private final RouteEngine routeEngine = new RouteEngine();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final WarmConnectionPool<CfSocket> cfPool =
            new WarmConnectionPool<>(CF_POOL_SIZE_PER_DC, CfSocket::isAlive, CfSocket::close);

    private String boundIp = MtProtoConfig.DEFAULT_HOST;
    private String secretHex = MtProtoConfig.generateSecretHex();
    private byte[] secret = MtProtoConfig.secretBytes(secretHex);
    private Map<Integer, String> dcRedirects = MtProtoConfig.parseDcRules(MtProtoConfig.DEFAULT_DC_RULES);
    private List<String> cfProxyDomains = FlowsealCfDomains.defaults();
    private boolean cfProxyCustomDomains = false;
    private List<String> cfWorkerDomains = Collections.emptyList();
    private String cfProxyMode = CF_MODE_AUTO;
    private boolean cfWarmupEnabled = true;
    private boolean verbose = false;
    private volatile VpsRelayConfig vpsRelayConfig = VpsRelayConfig.disabled();
    private volatile NetworkProfile networkProfile =
            NetworkProfile.wifi(CfProxyDomainState.PROFILE_WIFI);
    private volatile String cfNetworkProfile = networkProfile.cfProfileId();
    private volatile RoutePreference routePreference = RoutePreference.AUTO;
    private volatile Runnable routeStatsChangedListener;

    public void setBoundIp(String boundIp) {
        this.boundIp = boundIp == null || boundIp.trim().isEmpty()
                ? MtProtoConfig.DEFAULT_HOST : boundIp.trim();
    }

    public String getBoundIp() {
        return boundIp;
    }

    public void setSecretHex(String secretHex) {
        this.secretHex = MtProtoConfig.normalizeSecretHex(secretHex);
        this.secret = MtProtoConfig.secretBytes(this.secretHex);
    }

    public String getSecretHex() {
        return secretHex;
    }

    public void setDcRules(String text) {
        this.dcRedirects = MtProtoConfig.parseDcRules(text);
    }

    public void setCfProxyEnabled(boolean enabled) {
        setCfProxyMode(enabled ? CF_MODE_ON : CF_MODE_OFF);
    }

    public void setCfProxyMode(String mode) {
        String normalized = normalizeCfProxyMode(mode);
        this.cfProxyMode = normalized;
        if (CF_MODE_OFF.equals(normalized)) cfPool.clear();
    }

    public void setCfProxyDomains(List<String> domains) {
        ArrayList<String> normalized = normalizeDomains(domains);
        this.cfProxyDomains = normalized.isEmpty() ? FlowsealCfDomains.defaults() : normalized;
        cfPool.clear();
    }

    public void setCfProxyCustomDomains(boolean customDomains) {
        this.cfProxyCustomDomains = customDomains;
    }

    public void setCfWarmupEnabled(boolean enabled) {
        this.cfWarmupEnabled = enabled;
        if (!enabled) cfPool.clear();
    }

    public void setCfWorkerDomains(List<String> domains) {
        this.cfWorkerDomains = normalizeDomains(domains);
    }

    public void setVpsRelayConfig(VpsRelayConfig config) {
        this.vpsRelayConfig = config == null ? VpsRelayConfig.disabled() : config;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void setMobileNetwork(boolean mobileNetwork) {
        setNetworkProfile(mobileNetwork
                ? NetworkProfile.mobile(CfProxyDomainState.PROFILE_MOBILE)
                : NetworkProfile.wifi(CfProxyDomainState.PROFILE_WIFI));
    }

    public void setNetworkProfile(NetworkProfile profile) {
        NetworkProfile next = profile == null ? NetworkProfile.defaultProfile() : profile;
        String nextCfProfile = next.cfProfileId();
        if (!nextCfProfile.equals(cfNetworkProfile)) {
            cfPool.clear();
            activeRouteByScope.clear();
            activeEndpointByScope.clear();
            cfNetworkProfile = nextCfProfile;
        }
        networkProfile = next;
    }

    public void setRoutePreference(RoutePreference preference) {
        this.routePreference = preference == null ? RoutePreference.AUTO : preference;
    }

    void replaceRouteStats(Map<String, RouteStats> statsByRoute) {
        routeStats.clear();
        if (statsByRoute != null) {
            for (Map.Entry<String, RouteStats> entry : statsByRoute.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    routeStats.put(entry.getKey(), entry.getValue().copy());
                }
            }
        }
    }

    void setRouteStatsChangedListener(Runnable listener) {
        this.routeStatsChangedListener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isListening() {
        return running.get()
                && serverSocket != null
                && serverSocket.isBound()
                && !serverSocket.isClosed();
    }

    public void start(int port) throws Exception {
        if (!running.compareAndSet(false, true)) return;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(boundIp, port));
            acceptThread = new Thread(this::acceptLoop, "tg-mtproto-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            warmupCfPool();
        } catch (Exception e) {
            running.set(false);
            closeServerSocket();
            throw e;
        }
    }

    public void stop() {
        running.set(false);
        DiagnosticsLog.record("engine stop requested");
        cfPool.clear();
        activeRouteByScope.clear();
        activeEndpointByScope.clear();
        closeServerSocket();
        for (Socket socket : activeSockets.keySet()) {
            try { socket.close(); } catch (Exception ignored) {}
        }
        activeSockets.clear();
    }

    public void pause() {
        stop();
    }

    public void resume(int port) throws Exception {
        start(port);
    }

    public void reconnectPool() {
        cfPool.clear();
        warmupCfPool();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                Thread thread = new Thread(() -> handleClient(client), "tg-mtproto-client");
                thread.setDaemon(true);
                thread.start();
            } catch (Exception e) {
                if (running.get()) errors.incrementAndGet();
            }
        }
    }

    private void handleClient(Socket client) {
        activeSockets.put(client, Boolean.TRUE);
        RawWebSocket ws = null;
        try {
            connections.incrementAndGet();
            client.setSoTimeout(CLIENT_READ_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            client.setKeepAlive(true);
            client.setReceiveBufferSize(524288);
            client.setSendBufferSize(524288);

            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] handshake = readExactly(in, HANDSHAKE_LEN);

            MtProtoCrypto.ClientHandshake parsed =
                    MtProtoCrypto.parseClientHandshake(handshake, secret);
            if (parsed == null) {
                errors.incrementAndGet();
                return;
            }

            int dcIdx = parsed.media ? -parsed.dc : parsed.dc;
            byte[] relayInit = MtProtoCrypto.generateRelayInit(parsed.protoTag, dcIdx);
            MtProtoCrypto.CryptoContext crypto =
                    MtProtoCrypto.buildCryptoContext(parsed.clientPrekeyIv, secret, relayInit);
            MtProtoPacketSplitter splitter =
                    new MtProtoPacketSplitter(relayInit, MtProtoCrypto.protoInt(parsed.protoTag));

            ws = connectForDc(parsed.dc, parsed.media);
            if (ws == null) {
                errors.incrementAndGet();
                return;
            }

            ws.send(relayInit);
            bridge(client, in, out, ws, crypto, splitter);
        } catch (Exception e) {
            errors.incrementAndGet();
            if (verbose) e.printStackTrace();
        } finally {
            if (ws != null) {
                try { ws.close(); } catch (Exception ignored) {}
            }
            activeSockets.remove(client);
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private RawWebSocket connectForDc(int dc, boolean media) {
        RoutePlan plan = routePlanCandidatesForDc(dc, media);
        String scope = routeScope(dc, media);
        for (RouteCandidate route : plan.routes()) {
            RawWebSocket ws = null;
            if (route.type() == RouteType.WORKER) {
                ws = connectViaWorker(route);
            } else if (route.type() == RouteType.VPS_RELAY) {
                ws = connectViaVpsRelay(route, dc, media);
            } else if (route.type() == RouteType.PUBLIC_CLOUDFLARE
                    || route.type() == RouteType.CUSTOM_CLOUDFLARE) {
                ws = connectViaCfProxy(dc, scope, route);
            } else if (route.type() == RouteType.DIRECT_WS) {
                ws = connectDirectWs(route);
            }
            if (ws != null) {
                recordRouteSuccess(route, -1);
                DiagnosticsLog.record("route connected " + route.key() + " endpoint=" + route.endpoint());
                activeRouteByScope.put(scope, route.key());
                if (route.type() != RouteType.PUBLIC_CLOUDFLARE
                        && route.type() != RouteType.CUSTOM_CLOUDFLARE) {
                    activeEndpointByScope.put(scope, route.endpoint());
                } else if (!activeEndpointByScope.containsKey(scope)) {
                    activeEndpointByScope.put(scope, route.endpoint());
                }
                return ws;
            }
        }
        return null;
    }

    private RawWebSocket connectViaVpsRelay(RouteCandidate route, int dc, boolean media) {
        VpsRelayConfig config = vpsRelayConfig;
        if (config == null || !config.isAllowedForProfile(networkProfile.key())) return null;
        try {
            return RawWebSocket.connectRelay(config, dc, media, CONNECT_TIMEOUT_MS);
        } catch (Exception error) {
            recordRouteFailure(route, RouteError.classify(error));
            return null;
        }
    }

    List<String> routePlanForDc(int dc, boolean media) {
        ArrayList<String> result = new ArrayList<>();
        for (RouteCandidate route : routePlanCandidatesForDc(dc, media).routes()) {
            String legacy = legacyRouteId(route.type());
            if (!legacy.isEmpty() && !result.contains(legacy)) result.add(legacy);
        }
        return result;
    }

    RoutePlan routePlanCandidatesForDc(int dc, boolean media) {
        return routeEngine.plan(routeSettings(), dc, media,
                activeRouteByScope.get(routeScope(dc, media)), routeStats, System.currentTimeMillis());
    }

    private RawWebSocket connectDirectWs(RouteCandidate route) {
        String[] domains = TgConstants.wsDomains(route.dc(), route.media());
        for (String domain : domains) {
            try {
                return RawWebSocket.connect(route.endpoint(), domain, CONNECT_TIMEOUT_MS);
            } catch (Exception error) {
                recordRouteFailure(route, RouteError.classify(error));
            }
        }
        return null;
    }

    private RawWebSocket connectViaWorker(RouteCandidate route) {
        String dst = DEFAULT_DC_IPS.get(route.dc());
        if (dst == null || cfWorkerDomains.isEmpty()) return null;
        for (String workerDomain : cfWorkerDomains) {
            try {
                String path = "/apiws?dst=" + URLEncoder.encode(dst, "UTF-8")
                        + "&dc=" + route.dc();
                return RawWebSocket.connect(workerDomain, workerDomain, CONNECT_TIMEOUT_MS, path, true);
            } catch (Exception error) {
                recordRouteFailure(route, RouteError.classify(error));
            }
        }
        return null;
    }

    private RawWebSocket connectViaCfProxy(int dc, String scope, RouteCandidate route) {
        CfSocket cfSocket = cfPool.acquire(cfPoolKey(dc), ignored -> openCfSocket(dc, route));
        warmupCfPoolForDc(dc);
        if (cfSocket == null) return null;
        activeEndpointByScope.put(scope, cfSocket.baseDomain);
        return cfSocket.socket;
    }

    private CfSocket openCfSocket(int dc) {
        return openCfSocket(dc, RouteCandidate.publicCloudflare(dc, "public-cf"));
    }

    private CfSocket openCfSocket(int dc, RouteCandidate route) {
        CfProxyDomainState domainState = CfProxyDomainState.shared();
        return new ParallelCfConnector<CfSocket>(
                domainState,
                2,
                cfNetworkProfile,
                (baseDomain, error) -> recordRouteFailure(route, RouteError.classify(error))).connect(
                cfProxyDomains,
                baseDomain -> {
                    String domain = "kws" + dc + "." + baseDomain;
                    RawWebSocket socket = RawWebSocket.connect(domain, domain, CONNECT_TIMEOUT_MS, null, true);
                    return new CfSocket(socket, baseDomain);
                },
                CfSocket::close);
    }

    List<String> cfWarmupKeys() {
        ArrayList<String> keys = new ArrayList<>();
        for (Integer dc : dcRedirects.keySet()) {
            if (dc != null && dc > 0) keys.add(cfPoolKey(dc));
        }
        return keys;
    }

    private String cfPoolKey(int dc) {
        return cfNetworkProfile + ":" + dc;
    }

    private int dcFromCfPoolKey(String key) {
        int colon = key == null ? -1 : key.lastIndexOf(':');
        if (colon < 0 || colon == key.length() - 1) return 2;
        try {
            return Integer.parseInt(key.substring(colon + 1));
        } catch (NumberFormatException ignored) {
            return 2;
        }
    }

    private void warmupCfPool() {
        if (!cfWarmupEnabled || !running.get() || CF_MODE_OFF.equals(cfProxyMode) || cfProxyDomains.isEmpty()) return;
        cfPool.warmup(cfWarmupKeys(), key -> {
            int dc = dcFromCfPoolKey(key);
            return openCfSocket(dc, cfRouteCandidateForDc(dc));
        });
    }

    private void warmupCfPoolForDc(int dc) {
        if (!cfWarmupEnabled || !running.get() || CF_MODE_OFF.equals(cfProxyMode) || cfProxyDomains.isEmpty()) return;
        cfPool.warmup(Collections.singletonList(cfPoolKey(dc)),
                key -> openCfSocket(dc, cfRouteCandidateForDc(dc)));
    }

    RouteState currentRouteState() {
        int dc = firstConfiguredDc();
        String scope = routeScope(dc, false);
        RoutePlan plan = routePlanCandidatesForDc(dc, false);
        if (plan.isEmpty()) return RouteState.inactive("no available route");
        RouteCandidate selected = routeForActiveKey(plan, activeRouteByScope.get(scope));
        if (selected == null) selected = plan.selected();
        String endpoint = activeEndpointByScope.get(scope);
        if (endpoint == null || endpoint.isEmpty()) endpoint = selected.endpoint();
        if (selected.type() == RouteType.PUBLIC_CLOUDFLARE
                || selected.type() == RouteType.CUSTOM_CLOUDFLARE) {
            String active = CfProxyDomainState.shared().activeDomain(cfNetworkProfile);
            if (!active.isEmpty()) endpoint = active;
        }
        RouteStats stats = routeStats.get(selected.key());
        int ping = stats == null ? -1 : stats.medianLatencyMs();
        String quality = stats == null || stats.totalFailures() == 0 ? "stable" : stats.lastError().name();
        return RouteState.active(selected, endpoint, ping, quality);
    }

    Map<String, RouteStats> routeStatsSnapshot() {
        return new LinkedHashMap<>(routeStats);
    }

    int activeConnectionCount() {
        return activeSockets.size();
    }

    private RouteEngine.Settings routeSettings() {
        RouteEngine.Settings.Builder builder = RouteEngine.Settings.builder()
                .networkProfile(networkProfile)
                .routePreference(routePreference)
                .cfMode(cfProxyMode)
                .dcRedirects(dcRedirects)
                .workerDomains(cfWorkerDomains);
        if (cfProxyCustomDomains) {
            builder.customCfDomains(cfProxyDomains);
        } else {
            builder.publicCfDomains(cfProxyDomains);
        }
        VpsRelayConfig relay = vpsRelayConfig;
        if (relay != null && relay.isAllowedForProfile(networkProfile.key())) {
            builder.vpsRelay(relay.name(), relay.host(), relay.port());
        }
        return builder.build();
    }

    private void recordRouteSuccess(RouteCandidate route, int latencyMs) {
        if (route == null) return;
        routeStats.computeIfAbsent(route.key(), ignored -> new RouteStats())
                .recordSuccess(System.currentTimeMillis(), latencyMs);
        DiagnosticsLog.record("route success " + route.key()
                + (latencyMs >= 0 ? " latency=" + latencyMs + "ms" : ""));
        notifyRouteStatsChanged();
    }

    private void recordRouteFailure(RouteCandidate route, RouteError error) {
        if (route == null) return;
        RouteError normalized = error == null ? RouteError.UNKNOWN : error;
        routeStats.computeIfAbsent(route.key(), ignored -> new RouteStats())
                .recordFailure(normalized, System.currentTimeMillis());
        DiagnosticsLog.record("route failure " + route.key() + " " + normalized.name());
        notifyRouteStatsChanged();
    }

    private void notifyRouteStatsChanged() {
        Runnable listener = routeStatsChangedListener;
        if (listener != null) listener.run();
    }

    private RouteCandidate cfRouteCandidateForDc(int dc) {
        for (RouteCandidate route : routeEngine.buildCandidates(routeSettings(), dc, false)) {
            if (route.type() == RouteType.PUBLIC_CLOUDFLARE
                    || route.type() == RouteType.CUSTOM_CLOUDFLARE) {
                return route;
            }
        }
        return cfProxyCustomDomains
                ? RouteCandidate.customCloudflare(dc, "custom-cf")
                : RouteCandidate.publicCloudflare(dc, "public-cf");
    }

    private int firstConfiguredDc() {
        for (Integer dc : dcRedirects.keySet()) {
            if (dc != null && dc > 0) return dc;
        }
        return 2;
    }

    private static String routeScope(int dc, boolean media) {
        return dc + (media ? ":media" : ":main");
    }

    private static RouteCandidate routeForActiveKey(RoutePlan plan, String activeKey) {
        if (activeKey == null || activeKey.isEmpty()) return null;
        for (RouteCandidate route : plan.routes()) {
            if (activeKey.equals(route.key())) return route;
        }
        return null;
    }

    private static String legacyRouteId(RouteType type) {
        if (type == RouteType.WORKER) return ROUTE_WORKER;
        if (type == RouteType.PUBLIC_CLOUDFLARE || type == RouteType.CUSTOM_CLOUDFLARE) {
            return ROUTE_CF_PROXY;
        }
        if (type == RouteType.DIRECT_WS) return ROUTE_DIRECT;
        if (type == RouteType.VPS_RELAY) return ROUTE_VPS_RELAY;
        return "";
    }

    private static final class CfSocket {
        final RawWebSocket socket;
        final String baseDomain;

        CfSocket(RawWebSocket socket, String baseDomain) {
            this.socket = socket;
            this.baseDomain = baseDomain == null ? "" : baseDomain;
        }

        boolean isAlive() {
            return socket != null && socket.isAlive();
        }

        void close() {
            if (socket == null) return;
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void bridge(Socket client, InputStream clientIn, OutputStream clientOut,
                        RawWebSocket ws, MtProtoCrypto.CryptoContext crypto,
                        MtProtoPacketSplitter splitter) throws InterruptedException {
        AtomicBoolean done = new AtomicBoolean(false);

        Thread up = new Thread(() -> {
            byte[] buf = new byte[64 * 1024];
            try {
                while (!done.get()) {
                    int n = clientIn.read(buf);
                    if (n < 0) break;
                    byte[] chunk = copy(buf, n);
                    bytesUp.addAndGet(n);
                    byte[] tgCipher = crypto.clientToTelegram(chunk);
                    List<byte[]> frames = splitter.split(tgCipher);
                    if (!frames.isEmpty()) {
                        ws.sendBatch(frames);
                    }
                }
                List<byte[]> tail = splitter.flush();
                if (!tail.isEmpty()) ws.sendBatch(tail);
                ws.initiateClose();
            } catch (Exception ignored) {
            } finally {
                done.set(true);
                try { client.close(); } catch (Exception ignored) {}
            }
        }, "tg-mtproto-up");

        Thread down = new Thread(() -> {
            try {
                while (!done.get()) {
                    byte[] payload = ws.recv();
                    if (payload == null) break;
                    bytesDown.addAndGet(payload.length);
                    byte[] clientCipher = crypto.telegramToClient(payload);
                    synchronized (clientOut) {
                        clientOut.write(clientCipher);
                        clientOut.flush();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                done.set(true);
                try { client.close(); } catch (Exception ignored) {}
            }
        }, "tg-mtproto-down");

        up.setDaemon(true);
        down.setDaemon(true);
        up.start();
        down.start();
        up.join();
        down.join();
    }

    private byte[] readExactly(InputStream in, int n) throws Exception {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new java.io.IOException("EOF");
            off += r;
        }
        return buf;
    }

    private static byte[] copy(byte[] source, int n) {
        byte[] out = new byte[n];
        System.arraycopy(source, 0, out, 0, n);
        return out;
    }

    private void closeServerSocket() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
    }

    private static ArrayList<String> normalizeDomains(List<String> domains) {
        ArrayList<String> result = new ArrayList<>();
        if (domains == null) return result;
        for (String raw : domains) {
            if (raw == null) continue;
            String domain = raw.trim().toLowerCase();
            if (domain.isEmpty() || result.contains(domain)) continue;
            result.add(domain);
        }
        return result;
    }

    static String normalizeCfProxyMode(String mode) {
        if (CF_MODE_ON.equals(mode) || CF_MODE_OFF.equals(mode)) return mode;
        return CF_MODE_AUTO;
    }
}
