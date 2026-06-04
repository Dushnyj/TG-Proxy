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
    private static final int HANDSHAKE_LEN = 64;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int CLIENT_READ_TIMEOUT_MS = 90_000;
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
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;

    private String boundIp = MtProtoConfig.DEFAULT_HOST;
    private String secretHex = MtProtoConfig.generateSecretHex();
    private byte[] secret = MtProtoConfig.secretBytes(secretHex);
    private Map<Integer, String> dcRedirects = MtProtoConfig.parseDcRules(MtProtoConfig.DEFAULT_DC_RULES);
    private List<String> cfProxyDomains = FlowsealCfDomains.defaults();
    private List<String> cfWorkerDomains = Collections.emptyList();
    private boolean cfProxyEnabled = true;
    private boolean verbose = false;

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
        this.cfProxyEnabled = enabled;
    }

    public void setCfProxyDomains(List<String> domains) {
        ArrayList<String> normalized = normalizeDomains(domains);
        this.cfProxyDomains = normalized.isEmpty() ? FlowsealCfDomains.defaults() : normalized;
    }

    public void setCfWorkerDomains(List<String> domains) {
        this.cfWorkerDomains = normalizeDomains(domains);
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start(int port) throws Exception {
        if (!running.compareAndSet(false, true)) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(boundIp, port));
        acceptThread = new Thread(this::acceptLoop, "tg-mtproto-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running.set(false);
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
        // Pools are intentionally avoided on Android for now. New WS sessions are created per MTProto connection.
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
        String targetIp = dcRedirects.get(dc);
        boolean directAllowed = targetIp != null
                && TgRoutePolicy.shouldUseDirectWs(dc, media, dcRedirects);
        if (directAllowed) {
            RawWebSocket direct = connectDirectWs(dc, media, targetIp);
            if (direct != null) return direct;
        }
        RawWebSocket fallback = connectViaWorker(dc);
        if (fallback != null) return fallback;
        if (cfProxyEnabled) return connectViaCfProxy(dc);
        if (targetIp != null && !directAllowed) {
            return connectDirectWs(dc, media, targetIp);
        }
        return null;
    }

    private RawWebSocket connectDirectWs(int dc, boolean media, String targetIp) {
        String[] domains = TgConstants.wsDomains(dc, media);
        for (String domain : domains) {
            try {
                return RawWebSocket.connect(targetIp, domain, CONNECT_TIMEOUT_MS);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private RawWebSocket connectViaWorker(int dc) {
        String dst = DEFAULT_DC_IPS.get(dc);
        if (dst == null || cfWorkerDomains.isEmpty()) return null;
        for (String workerDomain : cfWorkerDomains) {
            try {
                String path = "/apiws?dst=" + URLEncoder.encode(dst, "UTF-8")
                        + "&dc=" + dc;
                return RawWebSocket.connect(workerDomain, workerDomain, CONNECT_TIMEOUT_MS, path, true);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private RawWebSocket connectViaCfProxy(int dc) {
        for (String baseDomain : cfProxyDomains) {
            String domain = "kws" + dc + "." + baseDomain;
            try {
                return RawWebSocket.connect(domain, domain, CONNECT_TIMEOUT_MS, null, true);
            } catch (Exception ignored) {
            }
        }
        return null;
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
}
