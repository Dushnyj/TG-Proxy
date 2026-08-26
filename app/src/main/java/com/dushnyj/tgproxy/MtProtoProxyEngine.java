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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class MtProtoProxyEngine {
    private static final long ROUTE_EVIDENCE_REFRESH_MS = 15_000L;
    static final String ROUTE_WORKER = "worker";
    static final String ROUTE_CF_PROXY = "cf_proxy";
    static final String ROUTE_DIRECT = "direct";
    static final String ROUTE_VPS_RELAY = "vps_relay";
    static final String CF_MODE_AUTO = "auto";
    static final String CF_MODE_ON = "on";
    static final String CF_MODE_OFF = "off";

    private static final int HANDSHAKE_LEN = 64;
    private static final int CONNECT_TIMEOUT_MS = 7_000;
    private static final int ROUTE_ATTEMPT_BUDGET_MS = 12_000;
    private static final int CONNECT_BUDGET_MS = 22_000;
    private static final int ROUTE_RACE_PARALLELISM = 3;
    private static final long ROUTE_RACE_STAGGER_MS = 300L;
    private static final int CLIENT_HANDSHAKE_TIMEOUT_MS = 15_000;
    private static final int FIRST_TELEGRAM_BYTE_TIMEOUT_MS = 12_000;
    private static final int FIRST_CLIENT_PACKET_TIMEOUT_MS = 12_000;
    private static final int LOCAL_WRITE_TIMEOUT_MS = 30_000;
    private static final int UPSTREAM_WRITE_TIMEOUT_MS = 30_000;
    private static final long HALF_CLOSE_DRAIN_TIMEOUT_MS = 120_000L;
    private static final int MAX_CONSECUTIVE_ACCEPT_ERRORS = 5;
    private static final int CF_POOL_SIZE_PER_DC = 1;
    private static final long CF_POOL_MAX_IDLE_MS = 20_000L;
    private static final int MAX_ACTIVE_SESSIONS = 24;
    private static final int[] TELEGRAM_DCS = {1, 2, 3, 4, 5};

    private static final Map<Integer, String> DEFAULT_DC_IPS = new LinkedHashMap<>();
    private static final Map<Integer, String> TEST_DC_IPS = new LinkedHashMap<>();
    static {
        DEFAULT_DC_IPS.put(1, "149.154.175.50");
        DEFAULT_DC_IPS.put(2, "149.154.167.51");
        DEFAULT_DC_IPS.put(3, "149.154.175.100");
        DEFAULT_DC_IPS.put(4, "149.154.167.91");
        DEFAULT_DC_IPS.put(5, "149.154.171.5");
        DEFAULT_DC_IPS.put(203, "91.105.192.100");
        TEST_DC_IPS.putAll(MtProtoConfig.testDcRules());
    }

    public final AtomicLong bytesUp = new AtomicLong();
    public final AtomicLong bytesDown = new AtomicLong();
    public final AtomicLong connections = new AtomicLong();
    public final AtomicLong errors = new AtomicLong();

    private final ConcurrentHashMap<Socket, Boolean> activeSockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RouteStats> routeStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RouteEvidence> activeRouteByScope =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, RouteEvidence>>
            activeEvidenceByScope = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ConnectAttemptContext, Boolean> inFlightConnectAttempts =
            new ConcurrentHashMap<>();
    private final RouteEngine routeEngine = new RouteEngine();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong routeGeneration = new AtomicLong(1L);
    private final AtomicLong nextConnectionId = new AtomicLong(1L);
    private final Semaphore sessionPermits = new Semaphore(MAX_ACTIVE_SESSIONS);
    private final Object lifecycleLock = new Object();
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private final WarmConnectionPool<CfSocket> cfPool =
            new WarmConnectionPool<>(CF_POOL_SIZE_PER_DC, CF_POOL_MAX_IDLE_MS,
                    CfSocket::isAlive, CfSocket::close);

    private volatile String boundIp = MtProtoConfig.DEFAULT_HOST;
    private volatile String secretHex = MtProtoConfig.generateSecretHex();
    private volatile byte[] secret = MtProtoConfig.secretBytes(secretHex);
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
    private volatile String networkProfileKey = networkProfile.key();
    private volatile String cfNetworkProfile = networkProfile.cfProfileId();
    private volatile RoutePreference routePreference = RoutePreference.AUTO;
    private volatile Runnable routeStatsChangedListener;
    private boolean runtimeConfigurationApplied;

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

    public synchronized void setNetworkProfile(NetworkProfile profile) {
        NetworkProfile next = profile == null ? NetworkProfile.defaultProfile() : profile;
        String nextProfileKey = next.key();
        String nextCfProfile = next.cfProfileId();
        if (!nextProfileKey.equals(networkProfileKey)) {
            long generation = routeGeneration.incrementAndGet();
            activeRouteByScope.clear();
            activeEvidenceByScope.clear();
            cancelInFlightConnectAttempts();
            // Existing MTProto streams cannot be migrated safely. Keep them alive while the
            // old Android Network is still usable; only new route attempts use this generation.
            DiagnosticsLog.record("route network generation=" + generation
                    + " profile=" + nextProfileKey);
            networkProfileKey = nextProfileKey;
        }
        if (!nextCfProfile.equals(cfNetworkProfile)) {
            cfPool.clear();
            cfNetworkProfile = nextCfProfile;
        }
        networkProfile = next;
    }

    public void setRoutePreference(RoutePreference preference) {
        this.routePreference = preference == null ? RoutePreference.AUTO : preference;
    }

    synchronized void replaceRouteStats(Map<String, RouteStats> statsByRoute) {
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

    /** Publishes one complete configuration to new route attempts. Existing bridges keep running. */
    void applyRuntimeConfiguration(RuntimeConfigSnapshot snapshot) {
        if (snapshot == null) return;
        boolean warmup;
        boolean statsInvalidated = false;
        long generation;
        synchronized (this) {
            String nextSecretHex = MtProtoConfig.normalizeSecretHex(snapshot.secretHex);
            byte[] nextSecret = MtProtoConfig.secretBytes(nextSecretHex);
            Map<Integer, String> nextDcRedirects = MtProtoConfig.parseDcRules(snapshot.dcRules);
            List<String> nextCfDomains = normalizeDomains(snapshot.cfDomains);
            if (nextCfDomains.isEmpty()) nextCfDomains = FlowsealCfDomains.defaults();
            ArrayList<String> nextWorkerDomains = normalizeDomains(snapshot.workerDomains);
            String nextCfMode = normalizeCfProxyMode(snapshot.cfMode);
            NetworkProfile nextProfile = snapshot.networkProfile == null
                    ? NetworkProfile.defaultProfile() : snapshot.networkProfile;
            RoutePreference nextPreference = snapshot.routePreference == null
                    ? RoutePreference.AUTO : snapshot.routePreference;
            VpsRelayConfig nextRelay = snapshot.relay == null
                    ? VpsRelayConfig.disabled() : snapshot.relay;

            boolean directChanged = !dcRedirects.equals(nextDcRedirects);
            boolean cfChanged = !cfProxyDomains.equals(nextCfDomains)
                    || cfProxyCustomDomains != snapshot.cfCustomDomains
                    || !cfProxyMode.equals(nextCfMode);
            boolean workerChanged = !cfWorkerDomains.equals(nextWorkerDomains);
            boolean relayChanged = !vpsRelayConfig.sameRoutingIdentity(nextRelay);
            boolean profileChanged = !networkProfileKey.equals(nextProfile.key());
            boolean routingChanged = !secretHex.equals(nextSecretHex)
                    || directChanged
                    || cfChanged
                    || workerChanged
                    || profileChanged
                    || routePreference != nextPreference
                    || relayChanged;
            boolean poolChanged = routingChanged || cfWarmupEnabled != snapshot.cfWarmupEnabled;

            // Invalidate old attempts before publishing any profile-dependent maps. Recorders
            // use the same monitor, so an old-generation result cannot land in the new profile.
            if (routingChanged) generation = routeGeneration.incrementAndGet();
            else generation = routeGeneration.get();

            secretHex = nextSecretHex;
            secret = nextSecret;
            dcRedirects = nextDcRedirects;
            cfProxyDomains = nextCfDomains;
            cfProxyCustomDomains = snapshot.cfCustomDomains;
            cfProxyMode = nextCfMode;
            cfWorkerDomains = nextWorkerDomains;
            cfWarmupEnabled = snapshot.cfWarmupEnabled;
            vpsRelayConfig = nextRelay;
            verbose = snapshot.verbose;
            networkProfile = nextProfile;
            networkProfileKey = nextProfile.key();
            cfNetworkProfile = nextProfile.cfProfileId();
            routePreference = nextPreference;

            if (!runtimeConfigurationApplied || profileChanged) {
                routeStats.clear();
                for (Map.Entry<String, RouteStats> entry : snapshot.routeStats.entrySet()) {
                    if (runtimeConfigurationApplied
                            && invalidatedRouteStatsKey(entry.getKey(), directChanged, cfChanged,
                            workerChanged, relayChanged)) {
                        statsInvalidated = true;
                        continue;
                    }
                    routeStats.put(entry.getKey(), entry.getValue().copy());
                }
            } else if (directChanged || cfChanged || workerChanged || relayChanged) {
                for (String key : new ArrayList<>(routeStats.keySet())) {
                    if (invalidatedRouteStatsKey(key, directChanged, cfChanged,
                            workerChanged, relayChanged)) {
                        routeStats.remove(key);
                        statsInvalidated = true;
                    }
                }
            }
            runtimeConfigurationApplied = true;

            if (routingChanged) {
                activeRouteByScope.clear();
                activeEvidenceByScope.clear();
                cancelInFlightConnectAttempts();
                DiagnosticsLog.record("runtime config applied generation=" + generation
                        + " profile=" + networkProfileKey);
            } else {
                generation = routeGeneration.get();
            }
            if (poolChanged) cfPool.clear();
            warmup = poolChanged && running.get() && cfWarmupEnabled
                    && !CF_MODE_OFF.equals(cfProxyMode);
        }
        if (statsInvalidated) notifyRouteStatsChanged();
        if (warmup) warmupCfPool();
    }

    long routeGeneration() {
        return routeGeneration.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isListening() {
        Thread listenerThread = acceptThread;
        return running.get()
                && serverSocket != null
                && serverSocket.isBound()
                && !serverSocket.isClosed()
                && listenerThread != null
                && listenerThread.isAlive();
    }

    public void start(int port) throws Exception {
        synchronized (lifecycleLock) {
            if (running.get()) return;
            ServerSocket listener = new ServerSocket();
            try {
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(boundIp, port));
                running.set(true);
                serverSocket = listener;
                Thread thread = new Thread(() -> acceptLoop(listener), "tg-mtproto-accept");
                thread.setDaemon(true);
                acceptThread = thread;
                thread.start();
            } catch (Exception error) {
                running.set(false);
                closeServerSocket(listener);
                serverSocket = null;
                acceptThread = null;
                throw error;
            }
        }
        warmupCfPool();
    }

    public void stop() {
        ServerSocket listener;
        Thread listenerThread;
        long stoppedGeneration;
        synchronized (lifecycleLock) {
            running.set(false);
            stoppedGeneration = routeGeneration.incrementAndGet();
            listener = serverSocket;
            listenerThread = acceptThread;
            serverSocket = null;
            acceptThread = null;
            closeServerSocket(listener);
        }
        DiagnosticsLog.record("engine stop requested generation=" + stoppedGeneration);
        cancelInFlightConnectAttempts();
        cfPool.clear();
        activeRouteByScope.clear();
        activeEvidenceByScope.clear();
        if (listenerThread != null) listenerThread.interrupt();
        closeActiveClientSockets();
        activeSockets.clear();
    }

    private void closeActiveClientSockets() {
        for (Socket socket : activeSockets.keySet()) {
            try { socket.close(); } catch (Exception ignored) {}
        }
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

    /** Invalidates route evidence for a new Android Network without killing established bridges. */
    void onNetworkAttachmentChanged() {
        long generation;
        synchronized (this) {
            generation = routeGeneration.incrementAndGet();
            activeRouteByScope.clear();
            activeEvidenceByScope.clear();
            cancelInFlightConnectAttempts();
            cfPool.clear();
        }
        DiagnosticsLog.record("network attachment generation=" + generation
                + " profile=" + networkProfileKey);
    }

    private void acceptLoop(ServerSocket listener) {
        int consecutiveErrors = 0;
        try {
            while (running.get() && serverSocket == listener && !listener.isClosed()) {
                Socket client;
                try {
                    client = listener.accept();
                    consecutiveErrors = 0;
                } catch (Exception error) {
                    if (!running.get() || listener.isClosed() || serverSocket != listener) break;
                    errors.incrementAndGet();
                    consecutiveErrors++;
                    DiagnosticsLog.record("listener accept failed count=" + consecutiveErrors
                            + " " + errorSummary(error));
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ACCEPT_ERRORS) break;
                    try {
                        Thread.sleep(Math.min(1_000L, 50L << (consecutiveErrors - 1)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                if (!sessionPermits.tryAcquire()) {
                    errors.incrementAndGet();
                    DiagnosticsLog.record("client rejected: active session limit "
                            + MAX_ACTIVE_SESSIONS);
                    try { client.close(); } catch (Exception ignored) {}
                    continue;
                }

                boolean registered = false;
                synchronized (lifecycleLock) {
                    if (running.get() && serverSocket == listener && !listener.isClosed()) {
                        activeSockets.put(client, Boolean.TRUE);
                        registered = true;
                    }
                }
                if (!registered) {
                    sessionPermits.release();
                    try { client.close(); } catch (Exception ignored) {}
                    break;
                }

                try {
                    Thread thread = new Thread(() -> {
                        try {
                            handleClient(client);
                        } finally {
                            sessionPermits.release();
                        }
                    }, "tg-mtproto-client");
                    thread.setDaemon(true);
                    thread.start();
                } catch (Throwable startError) {
                    activeSockets.remove(client);
                    sessionPermits.release();
                    try { client.close(); } catch (Exception ignored) {}
                    DiagnosticsLog.record("client thread start failed "
                            + startError.getClass().getSimpleName());
                    if (startError instanceof Error) throw (Error) startError;
                }
            }
        } finally {
            closeServerSocket(listener);
            synchronized (lifecycleLock) {
                if (serverSocket == listener) {
                    serverSocket = null;
                    acceptThread = null;
                    running.set(false);
                    DiagnosticsLog.record("engine accept loop terminated");
                }
            }
        }
    }

    private void handleClient(Socket client) {
        long connectionId = nextConnectionId.getAndIncrement();
        RawWebSocket ws = null;
        ConnectedRoute connectedRoute = null;
        AtomicLong sessionUp = new AtomicLong();
        AtomicLong sessionDown = new AtomicLong();
        try {
            connections.incrementAndGet();
            DiagnosticsLog.record("client accepted " + safeRemote(client));
            client.setTcpNoDelay(true);
            client.setKeepAlive(true);
            client.setReceiveBufferSize(524288);
            client.setSendBufferSize(524288);

            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] handshake = readExactly(client, in, HANDSHAKE_LEN,
                    new ConnectBudget(CLIENT_HANDSHAKE_TIMEOUT_MS),
                    CLIENT_HANDSHAKE_TIMEOUT_MS);

            byte[] sessionSecret = secret;
            MtProtoCrypto.ClientHandshake parsed =
                    MtProtoCrypto.parseClientHandshake(handshake, sessionSecret);
            if (parsed == null) {
                errors.incrementAndGet();
                DiagnosticsLog.record("client handshake rejected " + safeRemote(client));
                return;
            }
            DiagnosticsLog.record("client handshake ok dcRaw=" + parsed.dcRaw
                    + " dc=" + parsed.dc
                    + " test=" + parsed.test
                    + " media=" + parsed.media
                    + " proto=" + protoLabel(parsed.protoTag));
            int dcIdx = parsed.relayDcRaw;
            byte[] relayInit = MtProtoCrypto.generateRelayInit(parsed.protoTag, dcIdx);
            MtProtoCrypto.CryptoContext crypto =
                    MtProtoCrypto.buildCryptoContext(parsed.clientPrekeyIv, sessionSecret, relayInit);
            MtProtoPacketSplitter splitter =
                    new MtProtoPacketSplitter(relayInit, MtProtoCrypto.protoInt(parsed.protoTag));
            List<byte[]> initialClientFrames = new ArrayList<>();
            ConnectBudget firstPacketBudget = new ConnectBudget(FIRST_CLIENT_PACKET_TIMEOUT_MS);
            byte[] initialBuffer = new byte[64 * 1024];
            while (initialClientFrames.isEmpty()) {
                int firstPacketTimeout = firstPacketBudget.remainingTimeoutMs(
                        FIRST_CLIENT_PACKET_TIMEOUT_MS);
                if (firstPacketTimeout <= 0) {
                    throw new java.net.SocketTimeoutException(
                            "first complete MTProto packet deadline exceeded");
                }
                client.setSoTimeout(firstPacketTimeout);
                int count = in.read(initialBuffer);
                if (count < 0) throw new java.io.EOFException("client closed before first MTProto packet");
                if (count == 0) continue;
                byte[] telegramCipher = crypto.clientToTelegram(copy(initialBuffer, count));
                initialClientFrames.addAll(splitter.split(telegramCipher));
                bytesUp.addAndGet(count);
                sessionUp.addAndGet(count);
            }
            client.setSoTimeout(0);
            DiagnosticsLog.record("client first MTProto packet ready frames="
                    + initialClientFrames.size());

            ConnectBudget connectionBudget = new ConnectBudget(CONNECT_BUDGET_MS);
            for (int generationAttempt = 0;
                 generationAttempt < 2 && connectionBudget.hasTime();
                 generationAttempt++) {
                long sessionGeneration = routeGeneration.get();
                connectedRoute = connectForDc(parsed.dcRaw, parsed.dc, parsed.media, parsed.test,
                        relayInit, initialClientFrames, connectionId, sessionGeneration,
                        connectionBudget);
                if (connectedRoute != null && connectedRoute.generation == routeGeneration.get()) {
                    break;
                }
                if (connectedRoute != null) {
                    connectedRoute.close();
                    connectedRoute = null;
                }
                if (sessionGeneration == routeGeneration.get()) break;
                DiagnosticsLog.record("route generation changed during connect; retrying dc="
                        + parsed.dc + " media=" + parsed.media);
            }
            if (connectedRoute == null) {
                errors.incrementAndGet();
                DiagnosticsLog.record("client route unavailable dcRaw=" + parsed.dcRaw
                        + " dc=" + parsed.dc + " media=" + parsed.media);
                return;
            }

            ws = connectedRoute.socket;
            bridge(client, in, out, connectedRoute, crypto, splitter, sessionUp, sessionDown);
        } catch (Exception e) {
            errors.incrementAndGet();
            if (connectedRoute != null && !connectedRoute.verified()) {
                connectedRoute.recordFailure(RouteError.classify(e));
            }
            DiagnosticsLog.record("client bridge error " + errorSummary(e));
            if (verbose) e.printStackTrace();
        } finally {
            DiagnosticsLog.record("client closed " + safeRemote(client)
                    + " up=" + sessionUp.get() + " down=" + sessionDown.get());
            if (ws != null) {
                try { ws.abort(); } catch (Exception ignored) {}
            }
            if (connectedRoute != null) connectedRoute.releaseEvidence();
            activeSockets.remove(client);
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private ConnectedRoute connectForDc(int dcRaw, int dc, boolean media, boolean test,
                                        byte[] relayInit, List<byte[]> initialClientFrames,
                                        long connectionId, long generation, ConnectBudget budget) {
        RoutePlan plan = routePlanCandidatesForDc(dc, media, test);
        String scope = routeScope(dc, media, test);
        DiagnosticsLog.record("route plan dcRaw=" + dcRaw + " dc=" + dc
                + " test=" + test + " media=" + media + " " + routePlanKeys(plan));
        if (plan.isEmpty()) {
            DiagnosticsLog.record("route unsupported dcRaw=" + dcRaw
                    + " dc=" + dc + " media=" + media);
            return null;
        }
        ArrayList<ConnectionRacer.Candidate<ConnectedRoute>> attempts = new ArrayList<>();
        for (RouteCandidate route : plan.routes()) {
            attempts.add(new ConnectionRacer.Candidate<>(
                    cancellation -> connectRoute(route, dc, media, scope, relayInit, connectionId,
                            initialClientFrames, generation,
                            budget.child(ROUTE_ATTEMPT_BUDGET_MS), cancellation)));
        }
        ConnectedRoute connected = new ConnectionRacer<ConnectedRoute>().connect(
                attempts, ROUTE_RACE_PARALLELISM, ROUTE_RACE_STAGGER_MS, budget,
                ConnectedRoute::close);
        if (connected == null && !budget.hasTime()) {
            DiagnosticsLog.record("route connect budget exhausted dc=" + dc
                    + " media=" + media);
        }
        return connected;
    }

    private ConnectedRoute connectRoute(RouteCandidate route, int dc, boolean media,
                                        String scope, byte[] relayInit, long connectionId,
                                        List<byte[]> initialClientFrames, long generation,
                                        ConnectBudget budget,
                                        ConnectionRacer.Cancellation cancellation) {
        if (route == null || isRouteAttemptCancelled(generation, cancellation)
                || !budget.hasTime()) return null;
        ConnectAttemptContext attemptContext = new ConnectAttemptContext(generation, cancellation);
        inFlightConnectAttempts.put(attemptContext, Boolean.TRUE);
        ScheduledFuture<?> deadlineAbort = null;
        try {
        RawWebSocket ws = null;
        String connectedEndpoint = route.endpoint();
        long attemptStartedMs = System.currentTimeMillis();
        DiagnosticsLog.record("route connecting " + route.key()
                + " endpoint=" + route.endpoint());
        if (route.type() == RouteType.WORKER) {
            CfSocket workerSocket = connectViaWorker(route, generation, budget, cancellation,
                    attemptContext);
            if (workerSocket != null) {
                ws = workerSocket.socket;
                connectedEndpoint = workerSocket.baseDomain;
            }
        } else if (route.type() == RouteType.VPS_RELAY) {
            ws = connectViaVpsRelay(route, dc, media, generation, budget, cancellation,
                    attemptContext);
        } else if (route.type() == RouteType.PUBLIC_CLOUDFLARE
                || route.type() == RouteType.CUSTOM_CLOUDFLARE) {
            CfSocket cfSocket = connectViaCfProxy(dc, media, route, generation, budget,
                    cancellation, attemptContext);
            if (cfSocket != null) {
                ws = cfSocket.socket;
                connectedEndpoint = cfSocket.baseDomain;
            }
        } else if (route.type() == RouteType.DIRECT_WS) {
            ws = connectDirectWs(route, generation, budget, cancellation, attemptContext);
        }
        if (ws == null) return null;
        deadlineAbort = ws.abortAtDeadline(budget);
        if (isRouteAttemptCancelled(generation, cancellation)) {
            try { ws.abort(); } catch (Exception ignored) {}
            DiagnosticsLog.record("route stale generation ignored " + route.key());
            return null;
        }
        try {
            ws.send(relayInit);
            if (initialClientFrames != null && !initialClientFrames.isEmpty()) {
                ws.sendBatch(initialClientFrames);
            }
            DiagnosticsLog.record("telegram relay init sent route=" + route.key()
                    + " dc=" + dc + " media=" + media + " requestFrames="
                    + (initialClientFrames == null ? 0 : initialClientFrames.size()));
            byte[] firstPayload = null;
            while (firstPayload == null || firstPayload.length == 0) {
                if (isRouteAttemptCancelled(generation, cancellation) || !budget.hasTime()) {
                    throw connectBudgetTimeout();
                }
                firstPayload = ws.recv(budget, FIRST_TELEGRAM_BYTE_TIMEOUT_MS);
            }
            if (isRouteAttemptCancelled(generation, cancellation)) {
                ws.abort();
                return null;
            }
            DiagnosticsLog.record("route first-byte ready " + route.key()
                    + " endpoint=" + connectedEndpoint);
            return new ConnectedRoute(ws, route, scope, connectedEndpoint, firstPayload,
                    connectionId, generation, attemptStartedMs);
        } catch (Exception error) {
            ws.abort();
            RouteError routeError = error instanceof java.net.SocketTimeoutException
                    ? RouteError.FIRST_BYTE_TIMEOUT : RouteError.classify(error);
            if (shouldRecordRouteFailure(generation, cancellation)) {
                recordRouteFailure(route, routeError, generation);
            }
            DiagnosticsLog.record("route first-byte failed " + route.key() + " "
                    + errorSummary(error));
            return null;
        }
        } finally {
            if (deadlineAbort != null) deadlineAbort.cancel(false);
            attemptContext.release();
        }
    }

    private boolean isStaleGeneration(long generation) {
        return generation != routeGeneration.get();
    }

    private RawWebSocket connectViaVpsRelay(RouteCandidate route, int dc, boolean media,
                                            long generation, ConnectBudget budget,
                                            ConnectionRacer.Cancellation cancellation,
                                            RawWebSocket.SocketObserver observer) {
        VpsRelayConfig config = vpsRelayConfig;
        if (config == null || !config.isAllowedForProfile(networkProfile.key())) return null;
        try {
            return RawWebSocket.connectRelay(config, dc, media, route.test(), budget,
                    CONNECT_TIMEOUT_MS,
                    observer);
        } catch (Exception error) {
            DiagnosticsLog.record("route failed " + route.key()
                    + " endpoint=" + route.endpoint() + " " + errorSummary(error));
            if (shouldRecordRouteFailure(generation, cancellation)) {
                recordRouteFailure(route, RouteError.classify(error), generation);
            }
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
        return routePlanCandidatesForDc(dc, media, false);
    }

    private String activeRouteKey(String scope) {
        RouteEvidence evidence = activeRouteByScope.get(scope);
        return evidence == null ? "" : evidence.routeKey;
    }

    RoutePlan routePlanCandidatesForDc(int dc, boolean media, boolean test) {
        return routeEngine.plan(routeSettings(test), dc, media,
                activeRouteKey(routeScope(dc, media, test)), routeStats,
                System.currentTimeMillis());
    }

    private RawWebSocket connectDirectWs(RouteCandidate route, long generation,
                                         ConnectBudget budget,
                                         ConnectionRacer.Cancellation cancellation,
                                         RawWebSocket.SocketObserver observer) {
        String[] domains = TgConstants.wsDomains(route.dc(), route.media());
        Exception lastError = null;
        for (String domain : domains) {
            if (isRouteAttemptCancelled(generation, cancellation)
                    || !budget.hasTime()) return null;
            try {
                return RawWebSocket.connect(route.endpoint(), domain,
                        route.test() ? "/apiws_test" : "/apiws", budget,
                        CONNECT_TIMEOUT_MS, observer);
            } catch (Exception error) {
                lastError = error;
                DiagnosticsLog.record("route failed " + route.key()
                        + " domain=" + domain + " " + errorSummary(error));
            }
        }
        if (shouldRecordRouteFailure(generation, cancellation)) {
            recordRouteFailure(route, RouteError.classify(lastError), generation);
        }
        return null;
    }

    private CfSocket connectViaWorker(RouteCandidate route, long generation,
                                      ConnectBudget budget,
                                      ConnectionRacer.Cancellation cancellation,
                                      RawWebSocket.SocketObserver observer) {
        String dst = (route.test() ? TEST_DC_IPS : DEFAULT_DC_IPS).get(route.dc());
        if (dst == null || cfWorkerDomains.isEmpty()) return null;
        Exception lastError = null;
        for (String workerDomain : cfWorkerDomains) {
            if (isRouteAttemptCancelled(generation, cancellation)
                    || !budget.hasTime()) return null;
            try {
                String path = "/apiws?dst=" + URLEncoder.encode(dst, "UTF-8")
                        + "&dc=" + route.dc();
                return new CfSocket(RawWebSocket.connect(workerDomain, workerDomain, path, budget,
                        CONNECT_TIMEOUT_MS, observer), workerDomain);
            } catch (Exception error) {
                lastError = error;
                DiagnosticsLog.record("route failed " + route.key()
                        + " worker=" + workerDomain + " " + errorSummary(error));
            }
        }
        if (shouldRecordRouteFailure(generation, cancellation)) {
            recordRouteFailure(route, RouteError.classify(lastError), generation);
        }
        return null;
    }

    private CfSocket connectViaCfProxy(int dc, boolean media, RouteCandidate route,
                                       long generation, ConnectBudget budget,
                                       ConnectionRacer.Cancellation cancellation,
                                       RawWebSocket.SocketObserver observer) {
        CfSocket cfSocket = cfPool.acquire(cfPoolKey(dc, media),
                ignored -> openCfSocket(dc, media, route, generation, budget, true,
                        cancellation, observer));
        warmupCfPoolForDc(dc, media);
        return cfSocket;
    }

    private CfSocket openCfSocket(int dc) {
        return openCfSocket(dc, false,
                RouteCandidate.publicCloudflare(dc, false, "public-cf"), routeGeneration.get(),
                new ConnectBudget(CONNECT_BUDGET_MS), true, null, null);
    }

    private CfSocket openCfSocket(int dc, RouteCandidate route) {
        return openCfSocket(dc, route.media(), route, routeGeneration.get(),
                new ConnectBudget(CONNECT_BUDGET_MS), true, null, null);
    }

    private CfSocket openCfSocket(int dc, boolean media, RouteCandidate route, long generation) {
        return openCfSocket(dc, media, route, generation,
                new ConnectBudget(CONNECT_BUDGET_MS), true, null, null);
    }

    private CfSocket openCfSocket(int dc, boolean media, RouteCandidate route, long generation,
                                  ConnectBudget budget, boolean recordFailure,
                                  ConnectionRacer.Cancellation cancellation,
                                  RawWebSocket.SocketObserver observer) {
        CfProxyDomainState domainState = CfProxyDomainState.shared();
        AtomicReference<Exception> lastError = new AtomicReference<>();
        ConcurrentHashMap<String, DomainSocketObserver> domainAttempts = new ConcurrentHashMap<>();
        for (String baseDomain : cfProxyDomains) {
            if (baseDomain != null && !baseDomain.trim().isEmpty()) {
                domainAttempts.put(baseDomain,
                        new DomainSocketObserver(observer));
            }
        }
        CfSocket connected = new ParallelCfConnector<CfSocket>(
                domainState,
                2,
                cfNetworkProfile,
                (baseDomain, error) -> {
                    lastError.set(error);
                    DiagnosticsLog.record("route failed " + route.key()
                            + " cf=" + baseDomain + " " + errorSummary(error));
                }).connect(
                cfProxyDomains,
                baseDomain -> {
                    DomainSocketObserver domainObserver = domainAttempts.get(baseDomain);
                    if (domainObserver == null) {
                        domainObserver = new DomainSocketObserver(observer);
                        DomainSocketObserver raced = domainAttempts.putIfAbsent(
                                baseDomain, domainObserver);
                        if (raced != null) domainObserver = raced;
                    }
                    // A custom Cloudflare zone exposes exactly kws<dc> (including kws203).
                    // The "-1" hostname is a web.telegram.org origin variant and must never be
                    // appended to the user's base domain.
                    String domain = "kws" + dc + "." + baseDomain;
                    RawWebSocket socket = RawWebSocket.connect(domain, domain, null, budget,
                            CONNECT_TIMEOUT_MS, domainObserver);
                    return new CfSocket(socket, baseDomain);
                },
                CfSocket::close,
                budget);
        for (Map.Entry<String, DomainSocketObserver> entry : domainAttempts.entrySet()) {
            if (connected != null && connected.baseDomain.equals(entry.getKey())) {
                entry.getValue().release();
            } else {
                entry.getValue().cancel();
            }
        }
        if (connected == null && recordFailure
                && shouldRecordRouteFailure(generation, cancellation)) {
            Exception error = lastError.get();
            if (error == null && !budget.hasTime()) error = connectBudgetTimeout();
            recordRouteFailure(route, RouteError.classify(error), generation);
        }
        return connected;
    }

    private boolean shouldRecordRouteFailure(long generation,
                                             ConnectionRacer.Cancellation cancellation) {
        return !isRouteAttemptCancelled(generation, cancellation);
    }

    private boolean isRouteAttemptCancelled(long generation,
                                            ConnectionRacer.Cancellation cancellation) {
        return Thread.currentThread().isInterrupted()
                || isStaleGeneration(generation)
                || (cancellation != null && cancellation.isCancelled());
    }

    List<String> cfWarmupKeys() {
        ArrayList<String> keys = new ArrayList<>();
        // Opening every DC/main/media combination at once creates an avoidable burst and
        // can trigger CDN rate limits. DC2 covers the common Web endpoint; other DCs warm
        // lazily after their first real request.
        int dc = dcRedirects.containsKey(2) ? 2 : firstConfiguredDc();
        keys.add(cfPoolKey(dc, false));
        keys.add(cfPoolKey(dc, true));
        return keys;
    }

    private String cfPoolKey(int dc, boolean media) {
        return cfNetworkProfile + ":" + dc + (media ? ":media" : ":main");
    }

    private int dcFromCfPoolKey(String key) {
        if (key == null) return 2;
        int suffix = key.lastIndexOf(':');
        int colon = suffix <= 0 ? -1 : key.lastIndexOf(':', suffix - 1);
        if (colon < 0 || suffix <= colon + 1) return 2;
        try {
            return Integer.parseInt(key.substring(colon + 1, suffix));
        } catch (NumberFormatException ignored) {
            return 2;
        }
    }

    private boolean mediaFromCfPoolKey(String key) {
        return key != null && key.endsWith(":media");
    }

    private void warmupCfPool() {
        if (!cfWarmupEnabled || !running.get() || CF_MODE_OFF.equals(cfProxyMode) || cfProxyDomains.isEmpty()) return;
        cfPool.warmup(cfWarmupKeys(), key -> {
            int dc = dcFromCfPoolKey(key);
            boolean media = mediaFromCfPoolKey(key);
            return openCfSocket(dc, media, cfRouteCandidateForDc(dc, media),
                    routeGeneration.get(), new ConnectBudget(CONNECT_BUDGET_MS), false, null,
                    null);
        });
    }

    private void warmupCfPoolForDc(int dc, boolean media) {
        if (!cfWarmupEnabled || !running.get() || CF_MODE_OFF.equals(cfProxyMode) || cfProxyDomains.isEmpty()) return;
        cfPool.warmup(Collections.singletonList(cfPoolKey(dc, media)),
                key -> openCfSocket(dc, media, cfRouteCandidateForDc(dc, media),
                        routeGeneration.get(), new ConnectBudget(CONNECT_BUDGET_MS), false,
                        null, null));
    }

    RouteState currentRouteState() {
        long currentGeneration = routeGeneration.get();
        RouteCandidate freshestRoute = null;
        RouteStats freshestStats = null;
        String freshestEndpoint = "";
        long freshestAt = 0L;
        for (Map.Entry<String, RouteEvidence> entry : activeRouteByScope.entrySet()) {
            ActiveScope activeScope = ActiveScope.parse(entry.getKey());
            if (activeScope == null) continue;
            RouteEvidence evidence = entry.getValue();
            if (evidence == null || evidence.generation != currentGeneration) continue;
            RouteCandidate configured = null;
            for (RouteCandidate candidate : routeEngine.buildCandidates(
                    routeSettings(activeScope.test), activeScope.dc, activeScope.media)) {
                if (candidate.key().equals(evidence.routeKey)) {
                    configured = candidate;
                    break;
                }
            }
            if (configured == null) continue;
            RouteStats stats = routeStats.get(configured.key());
            if (stats == null || evidence.lastVerifiedMs <= freshestAt) continue;
            freshestRoute = configured;
            freshestStats = stats;
            freshestAt = evidence.lastVerifiedMs;
            freshestEndpoint = evidence.endpoint.isEmpty()
                    ? configured.endpoint() : evidence.endpoint;
        }
        if (freshestRoute != null && freshestStats != null) {
            int ping = freshestStats.medianLatencyMs();
            String quality = freshestStats.totalFailures() == 0
                    ? "stable" : freshestStats.lastError().name();
            return RouteState.active(freshestRoute, freshestEndpoint, ping, quality, freshestAt);
        }

        int dc = firstConfiguredDc();
        if (routeEngine.buildCandidates(routeSettings(false), dc, false).isEmpty()) {
            return RouteState.inactive("no available route");
        }
        return RouteState.inactive(activeRouteByScope.isEmpty()
                ? "no verified route on current network"
                : "verified route is no longer configured");
    }

    private static final class ActiveScope {
        final int dc;
        final boolean media;
        final boolean test;

        ActiveScope(int dc, boolean media, boolean test) {
            this.dc = dc;
            this.media = media;
            this.test = test;
        }

        static ActiveScope parse(String value) {
            if (value == null || value.trim().isEmpty()) return null;
            String[] parts = value.split(":");
            int dc;
            try {
                dc = Integer.parseInt(parts[0]);
            } catch (RuntimeException ignored) {
                return null;
            }
            boolean media = false;
            boolean test = false;
            for (int index = 1; index < parts.length; index++) {
                if ("media".equals(parts[index])) media = true;
                else if ("test".equals(parts[index])) test = true;
            }
            return dc > 0 ? new ActiveScope(dc, media, test) : null;
        }
    }

    Map<String, RouteStats> routeStatsSnapshot() {
        return new LinkedHashMap<>(routeStats);
    }

    void resetDiagnosticsState() {
        routeGeneration.incrementAndGet();
        cancelInFlightConnectAttempts();
        routeStats.clear();
        activeRouteByScope.clear();
        activeEvidenceByScope.clear();
        cfPool.clear();
        notifyRouteStatsChanged();
    }

    int activeConnectionCount() {
        return activeSockets.size();
    }

    private synchronized RouteEngine.Settings routeSettings() {
        return routeSettings(false);
    }

    private synchronized RouteEngine.Settings routeSettings(boolean test) {
        RouteEngine.Settings.Builder builder = RouteEngine.Settings.builder()
                .networkProfile(networkProfile)
                .routePreference(routePreference)
                .cfMode(cfProxyMode)
                .dcRedirects(test ? TEST_DC_IPS : dcRedirects)
                .workerDomains(cfWorkerDomains)
                .testDc(test);
        if (!test && cfProxyCustomDomains) {
            builder.customCfDomains(cfProxyDomains);
        } else if (!test) {
            builder.publicCfDomains(cfProxyDomains);
        }
        VpsRelayConfig relay = vpsRelayConfig;
        if (relay != null && relay.isAllowedForProfile(networkProfile.key())) {
            builder.vpsRelay(relay.name(), relay.host(), relay.port());
        }
        return builder.build();
    }

    private void recordRouteSuccess(RouteCandidate route, int latencyMs) {
        recordRouteSuccess(route, latencyMs, routeGeneration.get());
    }

    void recordRouteSuccess(RouteCandidate route, int latencyMs, long generation) {
        recordRouteSuccess(route, latencyMs, generation,
                route == null ? "" : route.endpoint(), 0L);
    }

    synchronized void recordRouteSuccess(RouteCandidate route, int latencyMs,
                                         long generation, String endpoint,
                                         long evidenceOwner) {
        if (route == null || isStaleGeneration(generation)) return;
        long nowMs = System.currentTimeMillis();
        routeStatsFor(route.key()).recordSuccess(nowMs, latencyMs);
        String scope = routeScope(route.dc(), route.media(), route.test());
        String actualEndpoint = endpoint == null || endpoint.isEmpty()
                ? route.endpoint() : endpoint;
        if (evidenceOwner > 0L) {
            ConcurrentHashMap<Long, RouteEvidence> scopeEvidence =
                    activeEvidenceByScope.get(scope);
            if (scopeEvidence == null) {
                ConcurrentHashMap<Long, RouteEvidence> created = new ConcurrentHashMap<>();
                ConcurrentHashMap<Long, RouteEvidence> raced =
                        activeEvidenceByScope.putIfAbsent(scope, created);
                scopeEvidence = raced == null ? created : raced;
            }
            scopeEvidence.put(evidenceOwner,
                    new RouteEvidence(route.key(), actualEndpoint, generation, nowMs));
        }
        activeRouteByScope.put(scope,
                new RouteEvidence(route.key(), actualEndpoint, generation, nowMs));
        DiagnosticsLog.record("route success " + route.key()
                + (latencyMs >= 0 ? " latency=" + latencyMs + "ms" : ""));
        notifyRouteStatsChanged();
    }

    private synchronized void refreshRouteEvidence(String scope, long evidenceOwner,
                                                   RouteCandidate route, String endpoint,
                                                   long generation, long nowMs) {
        if (scope == null || route == null || evidenceOwner <= 0L
                || isStaleGeneration(generation)) return;
        ConcurrentHashMap<Long, RouteEvidence> scopeEvidence = activeEvidenceByScope.get(scope);
        if (scopeEvidence == null || !scopeEvidence.containsKey(evidenceOwner)) return;
        String actualEndpoint = endpoint == null || endpoint.isEmpty()
                ? route.endpoint() : endpoint;
        scopeEvidence.put(evidenceOwner,
                new RouteEvidence(route.key(), actualEndpoint, generation, nowMs));
        routeStatsFor(route.key()).recordVerifiedTraffic(nowMs);
        activeRouteByScope.put(scope,
                new RouteEvidence(route.key(), actualEndpoint, generation, nowMs));
    }

    /** Removes one session and promotes the freshest still-live session in the same scope. */
    synchronized boolean unregisterRouteEvidence(String scope, long evidenceOwner,
                                                 String failedRouteKey, long generation,
                                                 boolean clearLastVerifiedWhenEmpty) {
        ConcurrentHashMap<Long, RouteEvidence> scopeEvidence = activeEvidenceByScope.get(scope);
        if (scopeEvidence == null) return false;
        scopeEvidence.remove(evidenceOwner);
        RouteEvidence freshest = null;
        boolean sameRouteStillHealthy = false;
        for (RouteEvidence evidence : scopeEvidence.values()) {
            if (evidence == null || evidence.generation != generation
                    || isStaleGeneration(evidence.generation)) continue;
            if (evidence.routeKey.equals(failedRouteKey)) sameRouteStillHealthy = true;
            if (freshest == null || evidence.lastVerifiedMs > freshest.lastVerifiedMs) {
                freshest = evidence;
            }
        }
        if (freshest == null) {
            activeEvidenceByScope.remove(scope, scopeEvidence);
            // A cleanly completed Telegram connection remains valid evidence for the current
            // network attachment. Only a real transport failure invalidates the last selected
            // route; otherwise short-lived media sessions would make the UI oscillate between
            // connected and degraded while the proxy itself is healthy.
            if (clearLastVerifiedWhenEmpty) {
                activeRouteByScope.remove(scope);
            }
        } else {
            activeRouteByScope.put(scope, freshest);
        }
        return sameRouteStillHealthy;
    }

    private void recordRouteFailure(RouteCandidate route, RouteError error) {
        recordRouteFailure(route, error, routeGeneration.get());
    }

    synchronized void recordRouteFailure(RouteCandidate route, RouteError error, long generation) {
        if (route == null || isStaleGeneration(generation)) return;
        RouteError normalized = error == null ? RouteError.UNKNOWN : error;
        routeStatsFor(route.key()).recordFailure(normalized, System.currentTimeMillis());
        DiagnosticsLog.record("route failure " + route.key() + " " + normalized.name());
        notifyRouteStatsChanged();
    }

    private void notifyRouteStatsChanged() {
        Runnable listener = routeStatsChangedListener;
        if (listener != null) listener.run();
    }

    private RouteStats routeStatsFor(String routeKey) {
        RouteStats existing = routeStats.get(routeKey);
        if (existing != null) return existing;
        RouteStats created = new RouteStats();
        RouteStats raced = routeStats.putIfAbsent(routeKey, created);
        return raced == null ? created : raced;
    }

    private RouteCandidate cfRouteCandidateForDc(int dc) {
        return cfRouteCandidateForDc(dc, false);
    }

    private static boolean invalidatedRouteStatsKey(String key, boolean directChanged,
                                                    boolean cfChanged, boolean workerChanged,
                                                    boolean relayChanged) {
        String value = key == null ? "" : key;
        if (directChanged && value.startsWith(RouteType.DIRECT_WS.id() + ":")) return true;
        if (workerChanged && value.startsWith(RouteType.WORKER.id() + ":")) return true;
        if (relayChanged && value.startsWith(RouteType.VPS_RELAY.id() + ":")) return true;
        return cfChanged && (value.startsWith(RouteType.PUBLIC_CLOUDFLARE.id() + ":")
                || value.startsWith(RouteType.CUSTOM_CLOUDFLARE.id() + ":"));
    }

    private RouteCandidate cfRouteCandidateForDc(int dc, boolean media) {
        for (RouteCandidate route : routeEngine.buildCandidates(routeSettings(), dc, media)) {
            if (route.type() == RouteType.PUBLIC_CLOUDFLARE
                    || route.type() == RouteType.CUSTOM_CLOUDFLARE) {
                return route;
            }
        }
        return cfProxyCustomDomains
                ? RouteCandidate.customCloudflare(dc, media, "custom-cf")
                : RouteCandidate.publicCloudflare(dc, media, "public-cf");
    }

    private int firstConfiguredDc() {
        for (Integer dc : dcRedirects.keySet()) {
            if (dc != null && dc > 0) return dc;
        }
        return 2;
    }

    private static String routeScope(int dc, boolean media) {
        return routeScope(dc, media, false);
    }

    private static String routeScope(int dc, boolean media, boolean test) {
        return dc + (test ? ":test" : "") + (media ? ":media" : ":main");
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

    private void cancelInFlightConnectAttempts() {
        for (ConnectAttemptContext attempt : inFlightConnectAttempts.keySet()) {
            attempt.cancel();
        }
    }

    private final class ConnectAttemptContext implements RawWebSocket.SocketObserver {
        private final long generation;
        private final ConnectionRacer.Cancellation cancellation;
        private final ConcurrentHashMap<Socket, Boolean> sockets = new ConcurrentHashMap<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean released = new AtomicBoolean(false);

        ConnectAttemptContext(long generation, ConnectionRacer.Cancellation cancellation) {
            this.generation = generation;
            this.cancellation = cancellation;
            if (cancellation != null) cancellation.onCancel(this::cancel);
        }

        @Override public void onSocket(Socket socket) {
            if (socket == null) return;
            if (isCancelled()) {
                try { socket.close(); } catch (Exception ignored) {}
                return;
            }
            sockets.put(socket, Boolean.TRUE);
            if (isCancelled() && sockets.remove(socket) != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        @Override public boolean isCancelled() {
            return cancelled.get() || isStaleGeneration(generation)
                    || (cancellation != null && cancellation.isCancelled());
        }

        void cancel() {
            if (released.get() || !cancelled.compareAndSet(false, true)) return;
            inFlightConnectAttempts.remove(this);
            for (Socket socket : sockets.keySet()) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            sockets.clear();
        }

        void release() {
            if (!released.compareAndSet(false, true)) return;
            inFlightConnectAttempts.remove(this);
            sockets.clear();
        }
    }

    /** Physically aborts nested Cloudflare-domain attempts that lose their inner race. */
    private static final class DomainSocketObserver implements RawWebSocket.SocketObserver {
        private final RawWebSocket.SocketObserver parent;
        private final ConcurrentHashMap<Socket, Boolean> sockets = new ConcurrentHashMap<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean released = new AtomicBoolean(false);

        DomainSocketObserver(RawWebSocket.SocketObserver parent) {
            this.parent = parent;
        }

        @Override public void onSocket(Socket socket) {
            if (socket == null) return;
            if (parent != null) parent.onSocket(socket);
            if (isCancelled()) {
                try { socket.close(); } catch (Exception ignored) {}
                return;
            }
            sockets.put(socket, Boolean.TRUE);
            if (isCancelled() && sockets.remove(socket) != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        @Override public boolean isCancelled() {
            return cancelled.get() || (parent != null && parent.isCancelled());
        }

        void cancel() {
            if (released.get() || !cancelled.compareAndSet(false, true)) return;
            for (Socket socket : sockets.keySet()) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            sockets.clear();
        }

        void release() {
            released.set(true);
            sockets.clear();
        }
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
            try { socket.abort(); } catch (Exception ignored) {}
        }
    }

    private void bridge(Socket client, InputStream clientIn, OutputStream clientOut,
                        ConnectedRoute connectedRoute, MtProtoCrypto.CryptoContext crypto,
                        MtProtoPacketSplitter splitter, AtomicLong sessionUp,
                        AtomicLong sessionDown) throws InterruptedException {
        RawWebSocket ws = connectedRoute.socket;
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicBoolean localInputEnded = new AtomicBoolean(false);
        AtomicBoolean localSessionCancelled = new AtomicBoolean(false);
        AtomicBoolean halfCloseDeadlineExpired = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> halfCloseAbort = new AtomicReference<>();

        // The route race has already received the first Telegram payload. Promote it only after
        // it was successfully transformed and written to the local Telegram socket.
        try {
            byte[] firstPayload = connectedRoute.firstPayload;
            byte[] clientCipher = crypto.telegramToClient(firstPayload);
            writeLocalWithDeadline(client, clientOut, clientCipher);
            bytesDown.addAndGet(firstPayload.length);
            sessionDown.addAndGet(firstPayload.length);
            connectedRoute.recordSuccess();
            ws.useSteadyStateReadTimeout();
        } catch (Exception localWriteError) {
            localSessionCancelled.set(true);
            DiagnosticsLog.record("local client rejected first Telegram payload "
                    + errorSummary(localWriteError));
            ws.abort();
            return;
        }

        Thread up = new Thread(() -> {
            byte[] buf = new byte[64 * 1024];
            boolean uploadCompletedCleanly = false;
            try {
                while (!done.get()) {
                    int n;
                    try {
                        n = clientIn.read(buf);
                    } catch (Exception localReadError) {
                        localSessionCancelled.set(true);
                        DiagnosticsLog.record("local client upload closed "
                                + errorSummary(localReadError));
                        break;
                    }
                    if (n < 0) {
                        localInputEnded.set(true);
                        ScheduledFuture<?> drainTimer = RawWebSocket.scheduleAfter(
                                HALF_CLOSE_DRAIN_TIMEOUT_MS, () -> {
                                    halfCloseDeadlineExpired.set(true);
                                    ws.abort();
                                });
                        if (!halfCloseAbort.compareAndSet(null, drainTimer)) {
                            drainTimer.cancel(false);
                        }
                        break;
                    }
                    byte[] chunk = copy(buf, n);
                    bytesUp.addAndGet(n);
                    sessionUp.addAndGet(n);
                    byte[] tgCipher;
                    List<byte[]> frames;
                    try {
                        tgCipher = crypto.clientToTelegram(chunk);
                        frames = splitter.split(tgCipher);
                    } catch (MtProtoPacketSplitter.PacketException protocolError) {
                        localSessionCancelled.set(true);
                        DiagnosticsLog.record("local MTProto stream rejected "
                                + errorSummary(protocolError));
                        break;
                    }
                    if (!frames.isEmpty()) {
                        sendBatchWithDeadline(ws, frames);
                        connectedRoute.recordTraffic();
                    }
                }
                if (!localSessionCancelled.get()) splitter.flush();
                // TCP half-close from Telegram means "no more request bytes", not
                // "discard the response". Keep the WebSocket open so media can drain.
                uploadCompletedCleanly = localInputEnded.get() && !localSessionCancelled.get();
            } catch (Exception error) {
                DiagnosticsLog.record("bridge upload stopped " + errorSummary(error));
                if (!done.get() && !localSessionCancelled.get()
                        && !(error instanceof MtProtoPacketSplitter.PacketException)) {
                    connectedRoute.recordFailure(RouteError.classify(error));
                }
            } finally {
                if (!uploadCompletedCleanly) {
                    done.set(true);
                    try { ws.abort(); } catch (Exception ignored) {}
                    try { client.close(); } catch (Exception ignored) {}
                }
            }
        }, "tg-mtproto-up");

        Thread down = new Thread(() -> {
            try {
                while (!done.get()) {
                    byte[] payload = ws.recv();
                    if (payload == null) {
                        if (!localSessionCancelled.get() && !halfCloseDeadlineExpired.get()
                                && !done.get()) {
                            connectedRoute.recordFailure(RouteError.REMOTE_EOF);
                        }
                        break;
                    }
                    if (payload.length == 0) continue;
                    byte[] clientCipher = crypto.telegramToClient(payload);
                    try {
                        writeLocalWithDeadline(client, clientOut, clientCipher);
                    } catch (Exception localWriteError) {
                        localSessionCancelled.set(true);
                        DiagnosticsLog.record("local client download closed "
                                + errorSummary(localWriteError));
                        break;
                    }
                    bytesDown.addAndGet(payload.length);
                    sessionDown.addAndGet(payload.length);
                    connectedRoute.recordTraffic();
                }
            } catch (Exception error) {
                DiagnosticsLog.record("bridge download stopped " + errorSummary(error));
                if (!done.get() && !localSessionCancelled.get()
                        && !halfCloseDeadlineExpired.get()
                        && !RouteError.isCleanSessionClose(error)) {
                    connectedRoute.recordFailure(RouteError.classify(error));
                }
            } finally {
                done.set(true);
                ScheduledFuture<?> drainTimer = halfCloseAbort.getAndSet(null);
                if (drainTimer != null) drainTimer.cancel(false);
                try { ws.abort(); } catch (Exception ignored) {}
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

    private final class ConnectedRoute {
        final RawWebSocket socket;
        final RouteCandidate route;
        final String scope;
        final String endpoint;
        final byte[] firstPayload;
        final long connectionId;
        final long generation;
        final long attemptStartedMs;
        final AtomicBoolean verified = new AtomicBoolean(false);
        final AtomicBoolean failureRecorded = new AtomicBoolean(false);
        final AtomicBoolean evidenceReleased = new AtomicBoolean(false);
        final AtomicLong lastEvidenceRefreshMs = new AtomicLong(0L);

        ConnectedRoute(RawWebSocket socket, RouteCandidate route, String scope,
                       String endpoint, byte[] firstPayload, long connectionId,
                       long generation, long attemptStartedMs) {
            this.socket = socket;
            this.route = route;
            this.scope = scope;
            this.endpoint = endpoint == null ? "" : endpoint;
            this.firstPayload = firstPayload == null ? new byte[0] : firstPayload;
            this.connectionId = connectionId;
            this.generation = generation;
            this.attemptStartedMs = attemptStartedMs;
        }

        boolean verified() {
            return verified.get();
        }

        void recordSuccess() {
            if (!verified.compareAndSet(false, true) || isStaleGeneration(generation)) return;
            long elapsed = Math.max(0L, System.currentTimeMillis() - attemptStartedMs);
            int latencyMs = (int) Math.min(Integer.MAX_VALUE, elapsed);
            recordRouteSuccess(route, latencyMs, generation,
                    endpoint.isEmpty() ? route.endpoint() : endpoint, connectionId);
            lastEvidenceRefreshMs.set(System.currentTimeMillis());
            DiagnosticsLog.record("route verified first-byte " + route.key()
                    + " latency=" + latencyMs + "ms endpoint="
                    + (endpoint.isEmpty() ? route.endpoint() : endpoint));
        }

        void recordFailure(RouteError error) {
            if (isStaleGeneration(generation)
                    || !failureRecorded.compareAndSet(false, true)) return;
            boolean healthyPeer = releaseEvidenceAndCheckHealthyPeer(true);
            if (!healthyPeer) {
                recordRouteFailure(route, error, generation);
            } else {
                DiagnosticsLog.record("route failure suppressed; another verified session is live "
                        + route.key());
            }
            errors.incrementAndGet();
        }

        void recordTraffic() {
            if (!verified() || isStaleGeneration(generation)) return;
            long nowMs = System.currentTimeMillis();
            long previous = lastEvidenceRefreshMs.get();
            if (nowMs - previous < ROUTE_EVIDENCE_REFRESH_MS
                    || !lastEvidenceRefreshMs.compareAndSet(previous, nowMs)) return;
            refreshRouteEvidence(scope, connectionId, route, endpoint, generation, nowMs);
            notifyRouteStatsChanged();
        }

        private boolean releaseEvidenceAndCheckHealthyPeer(boolean failed) {
            if (!verified() || !evidenceReleased.compareAndSet(false, true)) return false;
            return unregisterRouteEvidence(scope, connectionId, route.key(), generation, failed);
        }

        void releaseEvidence() {
            releaseEvidenceAndCheckHealthyPeer(false);
        }

        void close() {
            try { socket.abort(); } catch (Exception ignored) {}
        }
    }

    private static final class RouteEvidence {
        final String routeKey;
        final String endpoint;
        final long generation;
        final long lastVerifiedMs;

        RouteEvidence(String routeKey, String endpoint, long generation, long lastVerifiedMs) {
            this.routeKey = routeKey == null ? "" : routeKey;
            this.endpoint = endpoint == null ? "" : endpoint;
            this.generation = generation;
            this.lastVerifiedMs = lastVerifiedMs;
        }
    }

    private static String routePlanKeys(RoutePlan plan) {
        if (plan == null || plan.routes().isEmpty()) return "empty";
        StringBuilder out = new StringBuilder();
        for (RouteCandidate route : plan.routes()) {
            if (out.length() > 0) out.append(" -> ");
            out.append(route.key());
        }
        return out.toString();
    }

    private static String protoLabel(byte[] protoTag) {
        int proto = MtProtoCrypto.protoInt(protoTag);
        if (proto == MtProtoCrypto.PROTO_ABRIDGED_INT) return "abridged";
        if (proto == MtProtoCrypto.PROTO_INTERMEDIATE_INT) return "intermediate";
        if (proto == MtProtoCrypto.PROTO_PADDED_INTERMEDIATE_INT) return "padded";
        return "unknown";
    }

    private static String safeRemote(Socket socket) {
        if (socket == null || socket.getRemoteSocketAddress() == null) return "-";
        return socket.getRemoteSocketAddress().toString();
    }

    private static String errorSummary(Exception error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        String value = message.trim().replace('\r', ' ');
        int newline = value.indexOf('\n');
        if (newline >= 0) value = value.substring(0, newline).trim();
        return error.getClass().getSimpleName() + ": " + value;
    }

    private static java.net.SocketTimeoutException connectBudgetTimeout() {
        return new java.net.SocketTimeoutException("route connect budget exhausted");
    }

    private byte[] readExactly(Socket socket, InputStream in, int n, ConnectBudget budget,
                               int perReadCapMs) throws Exception {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int timeout = budget == null ? perReadCapMs
                    : budget.remainingTimeoutMs(perReadCapMs);
            if (timeout <= 0) {
                throw new java.net.SocketTimeoutException("local read deadline exceeded");
            }
            socket.setSoTimeout(timeout);
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new java.io.IOException("EOF");
            off += r;
        }
        return buf;
    }

    private static void writeLocalWithDeadline(Socket client, OutputStream out, byte[] data)
            throws java.io.IOException {
        ScheduledFuture<?> deadline = RawWebSocket.closeSocketAfter(
                client, LOCAL_WRITE_TIMEOUT_MS);
        try {
            synchronized (out) {
                out.write(data);
                out.flush();
            }
        } finally {
            if (deadline != null) deadline.cancel(false);
        }
    }

    private static void sendBatchWithDeadline(RawWebSocket ws, List<byte[]> frames)
            throws java.io.IOException {
        ScheduledFuture<?> deadline = ws.abortAfter(UPSTREAM_WRITE_TIMEOUT_MS);
        try {
            ws.sendBatch(frames);
        } finally {
            if (deadline != null) deadline.cancel(false);
        }
    }

    private static byte[] copy(byte[] source, int n) {
        byte[] out = new byte[n];
        System.arraycopy(source, 0, out, 0, n);
        return out;
    }

    private static void closeServerSocket(ServerSocket listener) {
        if (listener == null) return;
        try { listener.close(); } catch (Exception ignored) {}
    }

    private static ArrayList<String> normalizeDomains(List<String> domains) {
        ArrayList<String> result = new ArrayList<>();
        if (domains == null) return result;
        for (String raw : domains) {
            if (raw == null) continue;
            String domain = raw.trim().toLowerCase(Locale.ROOT);
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
