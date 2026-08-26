package com.dushnyj.tgproxy;

import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class RawWebSocket {

    interface SocketObserver {
        void onSocket(Socket socket);
        boolean isCancelled();
    }

    private static final int OP_BINARY = 0x2;
    private static final int OP_CONT   = 0x0;
    private static final int OP_TEXT   = 0x1;
    private static final int OP_CLOSE  = 0x8;
    private static final int OP_PING   = 0x9;
    private static final int OP_PONG   = 0xA;

    private static final int RECV_TIMEOUT_MS = 300_000;
    private static final long HEARTBEAT_INTERVAL_MS = 25_000L;
    private static final long HEARTBEAT_CHECK_MS = 5_000L;
    private static final long PONG_TIMEOUT_MS = 15_000L;
    private static final int MAX_HTTP_HEADER_BYTES = 64 * 1024;
    private static final int MAX_HTTP_LINE_BYTES = 8 * 1024;
    static final int MAX_MESSAGE_LEN = 16 * 1024 * 1024;

    private final InputStream  in;
    private final OutputStream out;
    private final Socket socket;
    private volatile boolean closed = false;
    private volatile long lastWriteNanos = System.nanoTime();
    private volatile ScheduledFuture<?> heartbeatTask;
    private final AtomicLong heartbeatSequence = new AtomicLong();
    private final AtomicReference<byte[]> outstandingPing = new AtomicReference<>();
    private volatile long outstandingPingNanos;
    private static final SecureRandom rng = new SecureRandom();

    private static final ScheduledThreadPoolExecutor heartbeatExecutor = heartbeatExecutor();
    private static final ScheduledThreadPoolExecutor deadlineExecutor = deadlineExecutor();
    private static final ThreadPoolExecutor dnsExecutor = new ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), runnable -> {
        Thread thread = new Thread(runnable, "tg-ws-dns");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private static final SSLSocketFactory sslFactory =
            (SSLSocketFactory) SSLSocketFactory.getDefault();

    public RawWebSocket(Socket socket) throws IOException {
        this.socket = socket;
        this.in  = new java.io.BufferedInputStream(socket.getInputStream(),  TgConstants.BUF);
        this.out = new java.io.BufferedOutputStream(socket.getOutputStream(), TgConstants.BUF);
    }

    public static RawWebSocket connect(String ip, String domain, int timeout) throws Exception {
        return connect(ip, domain, timeout, "/apiws");
    }

    public static RawWebSocket connect(String ip, String domain, int timeout, String path) throws Exception {
        return connect(ip, domain, path, new ConnectBudget(timeout), timeout);
    }

    static RawWebSocket connect(String ip, String domain, ConnectBudget budget,
                                int perPhaseTimeoutMs) throws Exception {
        return connect(ip, domain, "/apiws", budget, perPhaseTimeoutMs, null);
    }

    static RawWebSocket connect(String ip, String domain, String path, ConnectBudget budget,
                                int perPhaseTimeoutMs) throws Exception {
        return connect(ip, domain, path, budget, perPhaseTimeoutMs, null);
    }

    static RawWebSocket connect(String ip, String domain, String path, ConnectBudget budget,
                                int perPhaseTimeoutMs, SocketObserver observer) throws Exception {
        Socket raw = connectTcp(ip, 443, budget, perPhaseTimeoutMs, observer);
        Socket active = raw;
        AtomicReference<Socket> activeSocket = new AtomicReference<>(raw);
        ScheduledFuture<?> deadlineClose = scheduleSocketCloseAtDeadline(activeSocket, budget);
        boolean connected = false;
        try {
            requireNotCancelled(observer);
            raw.setTcpNoDelay(true);
            raw.setReceiveBufferSize(262144);
            raw.setSendBufferSize(262144);

            SSLSocket ssl = (SSLSocket) sslFactory.createSocket(raw, domain, 443, true);
            active = ssl;
            activeSocket.set(ssl);
            observe(observer, ssl);
            requireNotCancelled(observer);
            ssl.setUseClientMode(true);
            enableEndpointIdentification(ssl);
            ssl.setSoTimeout(requirePhaseTimeout(budget, perPhaseTimeoutMs));
            ssl.startHandshake();
            verifyLegacyHostname(ssl, domain);
            requireNotCancelled(observer);

            RawWebSocket ws = new RawWebSocket(ssl);

            byte[] keyBytes = new byte[16];
            rng.nextBytes(keyBytes);
            String wsKey = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP);

            String safePath = (path == null || path.isEmpty()) ? "/apiws" : path;
            String req = "GET " + safePath + " HTTP/1.1\r\n" +
                    "Host: " + domain + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + wsKey + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Protocol: binary\r\n" +
                    "Origin: https://web.telegram.org\r\n" +
                    "User-Agent: Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.6778.204 Mobile Safari/537.36\r\n" +
                    "\r\n";

            ws.out.write(req.getBytes("UTF-8"));
            ws.out.flush();
            ssl.setSoTimeout(requirePhaseTimeout(budget, perPhaseTimeoutMs));

            int statusCode = 0;
            String acceptHeader = "";
            boolean firstLine = true;
            int[] headerBytes = {0};
            while (true) {
                String line = readLine(ws.in, ssl, budget, perPhaseTimeoutMs, headerBytes);
                if (line == null || line.isEmpty()) break;
                if (firstLine) {
                    String[] parts = line.split(" ", 3);
                    if (parts.length >= 2) {
                        try { statusCode = Integer.parseInt(parts[1]); }
                        catch (NumberFormatException ignored) {}
                    }
                    firstLine = false;
                } else {
                    int colon = line.indexOf(':');
                    if (colon > 0 && "sec-websocket-accept".equalsIgnoreCase(
                            line.substring(0, colon).trim())) {
                        acceptHeader = line.substring(colon + 1).trim();
                    }
                }
            }

            if (statusCode == 101 && websocketAccept(wsKey).equals(acceptHeader)) {
                ssl.setSoTimeout(RECV_TIMEOUT_MS);
                connected = true;
                return ws;
            }

            if (statusCode == 301 || statusCode == 302 || statusCode == 303
                    || statusCode == 307 || statusCode == 308) {
                throw new WsRedirectException(statusCode);
            }
            if (statusCode == 101) throw new WsProtocolException("invalid Sec-WebSocket-Accept");
            throw new WsHandshakeException(statusCode);
        } finally {
            if (deadlineClose != null) deadlineClose.cancel(false);
            if (!connected) closeSocket(active);
        }
    }

    static RawWebSocket connectRelay(VpsRelayConfig config, int dc, boolean media,
                                     int timeout) throws Exception {
        return connectRelay(config, dc, media, new ConnectBudget(timeout), timeout);
    }

    static RawWebSocket connectRelay(VpsRelayConfig config, int dc, boolean media,
                                     ConnectBudget budget, int perPhaseTimeoutMs) throws Exception {
        return connectRelay(config, dc, media, budget, perPhaseTimeoutMs, null);
    }

    static RawWebSocket connectRelay(VpsRelayConfig config, int dc, boolean media,
                                     ConnectBudget budget, int perPhaseTimeoutMs,
                                     SocketObserver observer) throws Exception {
        return connectRelay(config, dc, media, false, budget, perPhaseTimeoutMs, observer);
    }

    static RawWebSocket connectRelay(VpsRelayConfig config, int dc, boolean media,
                                     boolean test, ConnectBudget budget, int perPhaseTimeoutMs,
                                     SocketObserver observer) throws Exception {
        if (config == null || !config.isUsable()) {
            throw new IOException("VPS relay is not configured");
        }
        Socket socket = connectTcp(config.host(), config.port(), budget,
                perPhaseTimeoutMs, observer);
        Socket activeSocket = socket;
        AtomicReference<Socket> activeSocketRef = new AtomicReference<>(socket);
        ScheduledFuture<?> deadlineClose = scheduleSocketCloseAtDeadline(activeSocketRef, budget);
        boolean connected = false;
        try {
            requireNotCancelled(observer);
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(262144);
            socket.setSendBufferSize(262144);

            if (config.tls()) {
                SSLSocket ssl = (SSLSocket) sslFactory.createSocket(
                        socket, config.host(), config.port(), true);
                activeSocket = ssl;
                activeSocketRef.set(ssl);
                observe(observer, ssl);
                requireNotCancelled(observer);
                ssl.setUseClientMode(true);
                enableEndpointIdentification(ssl);
                ssl.setSoTimeout(requirePhaseTimeout(budget, perPhaseTimeoutMs));
                ssl.startHandshake();
                verifyLegacyHostname(ssl, config.host());
                requireNotCancelled(observer);
            }

            activeSocket.setSoTimeout(requirePhaseTimeout(budget, perPhaseTimeoutMs));
            RawWebSocket ws = new RawWebSocket(activeSocket);
            String path = relayPath(config.path(), dc, media, test);
            websocketHandshake(ws, config.host(), path, config.token(), budget,
                    perPhaseTimeoutMs, config.tls());
            activeSocket.setSoTimeout(RECV_TIMEOUT_MS);
            connected = true;
            return ws;
        } finally {
            if (deadlineClose != null) deadlineClose.cancel(false);
            if (!connected) closeSocket(activeSocket);
        }
    }

    private static void enableEndpointIdentification(SSLSocket ssl) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
    }

    private static void verifyLegacyHostname(SSLSocket ssl, String hostname)
            throws SSLPeerUnverifiedException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return;
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, ssl.getSession())) {
            throw new SSLPeerUnverifiedException("TLS hostname mismatch: " + hostname);
        }
    }

    private static void websocketHandshake(RawWebSocket ws, String host,
                                           String path, String token, ConnectBudget budget,
                                           int perPhaseTimeoutMs, boolean tls) throws Exception {
        byte[] keyBytes = new byte[16];
        rng.nextBytes(keyBytes);
        String wsKey = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP);
        String req = "GET " + path + " HTTP/1.1\r\n" +
                "Host: " + relayHostHeader(host, ws.socket.getPort(), tls) + "\r\n" +
                "Authorization: Bearer " + token + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + wsKey + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Protocol: binary\r\n" +
                "Origin: https://web.telegram.org\r\n" +
                "\r\n";

        ws.out.write(req.getBytes("UTF-8"));
        ws.out.flush();
        ws.socket.setSoTimeout(requirePhaseTimeout(budget, perPhaseTimeoutMs));

        int statusCode = 0;
        String acceptHeader = "";
        boolean firstLine = true;
        int[] headerBytes = {0};
        while (true) {
            String line = readLine(ws.in, ws.socket, budget, perPhaseTimeoutMs, headerBytes);
            if (line == null || line.isEmpty()) break;
            if (firstLine) {
                String[] parts = line.split(" ", 3);
                if (parts.length >= 2) {
                    try { statusCode = Integer.parseInt(parts[1]); }
                    catch (NumberFormatException ignored) {}
                }
                firstLine = false;
            } else {
                int colon = line.indexOf(':');
                if (colon > 0 && "sec-websocket-accept".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    acceptHeader = line.substring(colon + 1).trim();
                }
            }
        }
        if (statusCode != 101 || !websocketAccept(wsKey).equals(acceptHeader)) {
            ws.closeQuiet();
            if (statusCode == 101) throw new WsProtocolException("invalid Sec-WebSocket-Accept");
            throw new WsHandshakeException(statusCode);
        }
    }

    static String relayPath(String path, int dc, boolean media) {
        return relayPath(path, dc, media, false);
    }

    static String relayPath(String path, int dc, boolean media, boolean test) {
        String safePath = (path == null || path.isEmpty()) ? "/apiws" : path;
        String separator = safePath.contains("?") ? "&" : "?";
        return safePath + separator + "dc=" + dc + "&media=" + (media ? "1" : "0")
                + "&test=" + (test ? "1" : "0");
    }

    private static String readLine(InputStream in, Socket socket, ConnectBudget budget,
                                   int perPhaseTimeoutMs, int[] totalBytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while (true) {
            socket.setSoTimeout(requirePhaseTimeout(budget, perPhaseTimeoutMs));
            c = in.read();
            if (c == -1) break;
            if (totalBytes != null) {
                totalBytes[0]++;
                if (totalBytes[0] > MAX_HTTP_HEADER_BYTES) {
                    throw new WsProtocolException("HTTP upgrade headers too large");
                }
            }
            if (c == '\n') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') {
                    sb.setLength(sb.length() - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > MAX_HTTP_LINE_BYTES) {
                throw new WsProtocolException("HTTP upgrade line too large");
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    public void send(byte[] data) throws IOException {
        if (closed) throw new IOException("closed");
        requireMessageSize(data);
        synchronized (out) {
            writeFrame(out, OP_BINARY, data, true);
            out.flush();
            lastWriteNanos = System.nanoTime();
        }
    }

    public void sendBatch(java.util.List<byte[]> parts) throws IOException {
        if (closed) throw new IOException("closed");
        synchronized (out) {
            for (byte[] p : parts) {
                requireMessageSize(p);
                writeFrame(out, OP_BINARY, p, true);
            }
            out.flush();
            lastWriteNanos = System.nanoTime();
        }
    }

    void useSteadyStateReadTimeout() throws IOException {
        socket.setSoTimeout(RECV_TIMEOUT_MS);
        startHeartbeat();
    }

    private static String relayHostHeader(String host, int port, boolean tls) {
        String value = host == null ? "" : host.trim();
        if (value.indexOf(':') >= 0 && !value.startsWith("[")) value = "[" + value + "]";
        int defaultPort = tls ? 443 : 80;
        return port > 0 && port != defaultPort ? value + ":" + port : value;
    }

    private static int requirePhaseTimeout(ConnectBudget budget, int perPhaseTimeoutMs)
            throws SocketTimeoutException {
        int timeout = budget == null
                ? Math.max(1, perPhaseTimeoutMs)
                : budget.remainingTimeoutMs(perPhaseTimeoutMs);
        if (timeout <= 0) throw new SocketTimeoutException("connect budget exhausted");
        return timeout;
    }

    private static void closeSocket(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (Exception ignored) {}
    }

    private static Socket connectTcp(String host, int port, ConnectBudget budget,
                                     int perPhaseTimeoutMs, SocketObserver observer)
            throws Exception {
        InetAddress[] resolved = resolveAll(host, budget, perPhaseTimeoutMs);
        List<InetAddress> addresses = happyEyeballsOrder(resolved);
        if (addresses.isEmpty()) throw new UnknownHostException(host);
        AtomicReference<Exception> lastError = new AtomicReference<>();
        ArrayList<ConnectionRacer.Candidate<Socket>> attempts = new ArrayList<>();
        for (InetAddress address : addresses) {
            attempts.add(new ConnectionRacer.Candidate<>(cancellation -> {
                if (cancellation.isCancelled() || isCancelled(observer)) return null;
                Socket socket = new Socket();
                observe(observer, socket);
                cancellation.onCancel(() -> closeSocket(socket));
                if (cancellation.isCancelled() || isCancelled(observer)) {
                    closeSocket(socket);
                    return null;
                }
                try {
                    int timeout = requirePhaseTimeout(budget, perPhaseTimeoutMs);
                    socket.connect(new InetSocketAddress(address, port), timeout);
                    if (cancellation.isCancelled() || isCancelled(observer)) {
                        closeSocket(socket);
                        return null;
                    }
                    return socket;
                } catch (Exception error) {
                    lastError.set(error);
                    closeSocket(socket);
                    return null;
                }
            }));
        }
        Socket connected = new ConnectionRacer<Socket>().connect(
                attempts, 2, 250L, budget, RawWebSocket::closeSocket);
        if (connected != null) return connected;
        Exception error = lastError.get();
        if (error != null) throw error;
        if (isCancelled(observer)) throw new IOException("connect cancelled");
        throw new SocketTimeoutException("TCP connect budget exhausted");
    }

    private static InetAddress[] resolveAll(String host, ConnectBudget budget,
                                            int perPhaseTimeoutMs) throws Exception {
        Future<InetAddress[]> future;
        try {
            future = dnsExecutor.submit(() -> InetAddress.getAllByName(host));
        } catch (RejectedExecutionException saturated) {
            throw new IOException("DNS resolver queue is saturated", saturated);
        }
        try {
            int timeout = requirePhaseTimeout(budget, perPhaseTimeoutMs);
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new SocketTimeoutException("DNS resolution timed out: " + host);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("DNS resolution failed: " + host, cause);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("DNS resolution interrupted: " + host, error);
        } finally {
            if (future.isCancelled()) dnsExecutor.purge();
        }
    }

    private static List<InetAddress> happyEyeballsOrder(InetAddress[] source) {
        ArrayList<InetAddress> v6 = new ArrayList<>();
        ArrayList<InetAddress> v4 = new ArrayList<>();
        boolean v6First = source != null && source.length > 0 && source[0] instanceof Inet6Address;
        if (source != null) {
            for (InetAddress address : source) {
                if (address instanceof Inet6Address) v6.add(address);
                else v4.add(address);
            }
        }
        ArrayList<InetAddress> ordered = new ArrayList<>();
        int count = Math.max(v6.size(), v4.size());
        for (int i = 0; i < count; i++) {
            if (v6First) {
                if (i < v6.size()) ordered.add(v6.get(i));
                if (i < v4.size()) ordered.add(v4.get(i));
            } else {
                if (i < v4.size()) ordered.add(v4.get(i));
                if (i < v6.size()) ordered.add(v6.get(i));
            }
        }
        return ordered;
    }

    private static void observe(SocketObserver observer, Socket socket) {
        if (observer != null && socket != null) observer.onSocket(socket);
    }

    private static boolean isCancelled(SocketObserver observer) {
        return observer != null && observer.isCancelled();
    }

    private static void requireNotCancelled(SocketObserver observer) throws IOException {
        if (isCancelled(observer)) throw new IOException("connect cancelled");
    }

    public byte[] recv() throws IOException {
        return recv(null, 0);
    }

    /** Receives one complete message without allowing fragmented/control traffic to extend a deadline. */
    byte[] recv(ConnectBudget budget, int perReadCapMs) throws IOException {
        ByteArrayOutputStream fragmented = null;
        int fragmentedOpcode = -1;
        while (!closed) {
            requireReadTime(budget, perReadCapMs);
            FrameHeader hdr = readFrameHeader(budget, perReadCapMs);
            int opcode = hdr.opcode;
            int length = hdr.length;
            if (hdr.masked) throw protocolError("server frame must not be masked");

            if (opcode == OP_CONT && fragmented != null
                    && fragmented.size() > MAX_MESSAGE_LEN - length) {
                throw messageTooLarge("fragmented message too large");
            }

            byte[] payload = readExactly(length, budget, perReadCapMs);

            if (opcode == OP_CLOSE) {
                CloseFrame close = parseCloseFrame(payload);
                try {
                    sendControl(OP_CLOSE, payload);
                } catch (Exception ignored) {}
                abort();
                throw new WebSocketCloseException(close.code, close.reason, true);
            }

            if (opcode == OP_PING) {
                try {
                    sendControl(OP_PONG, payload);
                } catch (IOException error) {
                    closed = true;
                    closeQuiet();
                    throw error;
                }
                continue;
            }

            if (opcode == OP_PONG) {
                byte[] expected = outstandingPing.get();
                if (expected != null && Arrays.equals(expected, payload)) {
                    outstandingPing.compareAndSet(expected, null);
                }
                continue;
            }

            if (opcode == OP_TEXT) {
                throw protocolError("text frames are not supported");
            }

            if (opcode == OP_BINARY) {
                if (fragmented != null) throw protocolError("new data frame before continuation finished");
                if (hdr.fin) return payload;
                fragmented = new ByteArrayOutputStream(Math.min(length + 1024, MAX_MESSAGE_LEN));
                fragmented.write(payload, 0, payload.length);
                fragmentedOpcode = opcode;
                continue;
            }

            if (opcode == OP_CONT) {
                if (fragmented == null || fragmentedOpcode < 0) {
                    throw protocolError("unexpected continuation frame");
                }
                fragmented.write(payload, 0, payload.length);
                if (hdr.fin) {
                    byte[] message = fragmented.toByteArray();
                    fragmented = null;
                    fragmentedOpcode = -1;
                    return message;
                }
                continue;
            }

            throw protocolError("unsupported websocket opcode " + opcode);
        }
        return null;
    }

    public void close() {
        if (closed) return;
        try {
            sendControl(OP_CLOSE, new byte[0]);
        } catch (Exception ignored) {}
        abort();
    }

    /** Immediate physical teardown for cancellation, network handover and service shutdown. */
    public void abort() {
        closed = true;
        outstandingPing.set(null);
        closeQuiet();
    }

    public boolean isAlive() {
        return !closed && socket != null && !socket.isClosed() && socket.isConnected();
    }

    public void setReadTimeout(int timeoutMs) throws IOException {
        if (socket != null) socket.setSoTimeout(Math.max(1, timeoutMs));
    }

    ScheduledFuture<?> abortAtDeadline(ConnectBudget budget) {
        if (budget == null) return null;
        int timeoutMs = budget.remainingTimeoutMs(Integer.MAX_VALUE);
        if (timeoutMs <= 0) {
            abort();
            return null;
        }
        return deadlineExecutor.schedule(this::abort, timeoutMs, TimeUnit.MILLISECONDS);
    }

    ScheduledFuture<?> abortAfter(long timeoutMs) {
        return deadlineExecutor.schedule(this::abort, Math.max(1L, timeoutMs),
                TimeUnit.MILLISECONDS);
    }

    static ScheduledFuture<?> closeSocketAfter(Socket target, long timeoutMs) {
        if (target == null) return null;
        return deadlineExecutor.schedule(() -> closeSocket(target), Math.max(1L, timeoutMs),
                TimeUnit.MILLISECONDS);
    }

    static ScheduledFuture<?> scheduleAfter(long timeoutMs, Runnable action) {
        if (action == null) return null;
        return deadlineExecutor.schedule(action, Math.max(1L, timeoutMs),
                TimeUnit.MILLISECONDS);
    }

    private static ScheduledFuture<?> scheduleSocketCloseAtDeadline(
            AtomicReference<Socket> target, ConnectBudget budget) {
        if (target == null || budget == null) return null;
        int timeoutMs = budget.remainingTimeoutMs(Integer.MAX_VALUE);
        if (timeoutMs <= 0) {
            closeSocket(target.get());
            return null;
        }
        return deadlineExecutor.schedule(() -> closeSocket(target.get()), timeoutMs,
                TimeUnit.MILLISECONDS);
    }

    private void closeQuiet() {
        stopHeartbeat();
        try { socket.close(); } catch (Exception ignored) {}
    }

    private FrameHeader readFrameHeader(ConnectBudget budget, int perReadCapMs)
            throws IOException {
        byte[] h = readExactly(2, budget, perReadCapMs);
        boolean fin = (h[0] & 0x80) != 0;
        if ((h[0] & 0x70) != 0) throw protocolError("RSV bits are not supported");
        int opcode = h[0] & 0x0F;
        if (opcode != OP_CONT && opcode != OP_TEXT && opcode != OP_BINARY
                && opcode != OP_CLOSE && opcode != OP_PING && opcode != OP_PONG) {
            throw protocolError("unsupported websocket opcode " + opcode);
        }
        if (opcode == OP_TEXT) throw protocolError("text frames are not supported");
        boolean masked = (h[1] & 0x80) != 0;
        int marker = h[1] & 0x7F;
        long length = marker;
        if (marker == 126) {
            byte[] ext = readExactly(2, budget, perReadCapMs);
            length = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
            if (length < 126L) throw protocolError("non-minimal 16-bit frame length");
        } else if (marker == 127) {
            byte[] ext = readExactly(8, budget, perReadCapMs);
            if ((ext[0] & 0x80) != 0) throw protocolError("invalid 64-bit frame length");
            length = ByteBuffer.wrap(ext).getLong();
            if (length <= 0xFFFFL) throw protocolError("non-minimal 64-bit frame length");
        }
        boolean control = opcode >= OP_CLOSE;
        if (control && (!fin || length > 125L)) {
            throw protocolError("invalid control frame");
        }
        if (length < 0L || length > MAX_MESSAGE_LEN || length > Integer.MAX_VALUE) {
            throw messageTooLarge("websocket frame too large");
        }
        return new FrameHeader(fin, opcode, (int) length, masked);
    }

    private byte[] readExactly(int n, ConnectBudget budget, int perReadCapMs)
            throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            requireReadTime(budget, perReadCapMs);
            int r = in.read(buf, off, n - off);
            if (r == -1) throw new IOException("EOF");
            off += r;
        }
        return buf;
    }

    private void requireReadTime(ConnectBudget budget, int perReadCapMs) throws IOException {
        if (budget == null) return;
        int timeout = budget.remainingTimeoutMs(
                perReadCapMs > 0 ? perReadCapMs : Integer.MAX_VALUE);
        if (timeout <= 0) throw new SocketTimeoutException("WebSocket receive deadline exceeded");
        socket.setSoTimeout(timeout);
    }

    private static byte[] xorMask(byte[] data, byte[] mask) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) result[i] = (byte) (data[i] ^ mask[i % 4]);
        return result;
    }

    private static byte[] buildFrame(int opcode, byte[] data, boolean mask) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (data == null ? 0 : data.length) + 14);
        try {
            writeFrame(output, opcode, data, mask);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return output.toByteArray();
    }

    /** Writes masked payload in bounded chunks instead of allocating a second frame-sized copy. */
    private static void writeFrame(OutputStream output, int opcode, byte[] data, boolean mask)
            throws IOException {
        if (data == null) data = new byte[0];
        int len = data.length;
        output.write(0x80 | opcode);

        int maskBit = mask ? 0x80 : 0;
        if (len < 126) {
            output.write(maskBit | len);
        } else if (len < 65536) {
            output.write(maskBit | 126);
            output.write((len >> 8) & 0xFF);
            output.write(len & 0xFF);
        } else {
            output.write(maskBit | 127);
            byte[] length = ByteBuffer.allocate(8).putLong(len).array();
            output.write(length);
        }

        if (mask) {
            byte[] mk = new byte[4];
            rng.nextBytes(mk);
            output.write(mk);
            byte[] masked = new byte[Math.min(16 * 1024, Math.max(1, len))];
            int offset = 0;
            while (offset < len) {
                int count = Math.min(masked.length, len - offset);
                for (int i = 0; i < count; i++) {
                    masked[i] = (byte) (data[offset + i] ^ mk[(offset + i) & 3]);
                }
                output.write(masked, 0, count);
                offset += count;
            }
        } else {
            output.write(data);
        }
    }

    public static class WsRedirectException extends IOException {
        public final int statusCode;
        public WsRedirectException(int code) {
            super("Redirect " + code);
            this.statusCode = code;
        }
    }

    public static class WsHandshakeException extends IOException {
        public final int statusCode;

        WsHandshakeException(int code) {
            super("WS handshake failed: " + code);
            this.statusCode = code;
        }
    }

    public static class WsProtocolException extends IOException {
        WsProtocolException(String message) {
            super("WS protocol error: " + message);
        }
    }

    public static class WebSocketCloseException extends IOException {
        public final int code;
        public final String reason;
        public final boolean peerInitiated;

        WebSocketCloseException(int code, String reason, boolean peerInitiated) {
            super("WebSocket closed: " + code
                    + (reason == null || reason.isEmpty() ? "" : " " + reason));
            this.code = code;
            this.reason = reason == null ? "" : reason;
            this.peerInitiated = peerInitiated;
        }
    }

    private void sendControl(int opcode, byte[] payload) throws IOException {
        byte[] safe = payload == null ? new byte[0] : payload;
        if (safe.length > 125) throw new IOException("control frame too large");
        ScheduledFuture<?> writeDeadline = deadlineExecutor.schedule(
                this::abort, PONG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try {
            synchronized (out) {
                writeFrame(out, opcode, safe, true);
                out.flush();
                lastWriteNanos = System.nanoTime();
            }
        } finally {
            writeDeadline.cancel(false);
        }
    }

    private synchronized void startHeartbeat() {
        if (closed || heartbeatTask != null) return;
        heartbeatTask = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            if (closed) {
                stopHeartbeat();
                return;
            }
            long nowNanos = System.nanoTime();
            byte[] pending = outstandingPing.get();
            if (pending != null) {
                if (nowNanos - outstandingPingNanos
                        >= TimeUnit.MILLISECONDS.toNanos(PONG_TIMEOUT_MS)) {
                    if (outstandingPing.compareAndSet(pending, null)) abort();
                }
                return;
            }
            long idleNanos = nowNanos - lastWriteNanos;
            if (idleNanos < TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_INTERVAL_MS)) return;
            byte[] nonce = ByteBuffer.allocate(8).putLong(heartbeatSequence.incrementAndGet()).array();
            outstandingPingNanos = nowNanos;
            if (!outstandingPing.compareAndSet(null, nonce)) return;
            try {
                sendControl(OP_PING, nonce);
            } catch (IOException ignored) {
                outstandingPing.compareAndSet(nonce, null);
                abort();
            }
        }, HEARTBEAT_CHECK_MS, HEARTBEAT_CHECK_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        heartbeatTask = null;
        if (task != null) task.cancel(false);
    }

    private static ScheduledThreadPoolExecutor heartbeatExecutor() {
        AtomicInteger nextId = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    "tg-ws-heartbeat-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, factory);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ScheduledThreadPoolExecutor deadlineExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "tg-ws-deadline");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, factory);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private IOException protocolError(String message) {
        sendProtocolClose(1002, message);
        return new WsProtocolException(message);
    }

    private IOException messageTooLarge(String message) {
        sendProtocolClose(1009, message);
        return new WsProtocolException(message);
    }

    private IOException invalidPayload(String message) {
        sendProtocolClose(1007, message);
        return new WsProtocolException(message);
    }

    private void sendProtocolClose(int code, String message) {
        byte[] reason = message == null ? new byte[0]
                : message.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(123, reason.length);
        byte[] payload = new byte[2 + length];
        payload[0] = (byte) ((code >>> 8) & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(reason, 0, payload, 2, length);
        try { sendControl(OP_CLOSE, payload); } catch (Exception ignored) {}
        abort();
    }

    private CloseFrame parseCloseFrame(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) return new CloseFrame(1005, "");
        if (payload.length == 1) throw protocolError("invalid close payload length");
        int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        if (!isValidCloseCode(code)) throw protocolError("invalid close code " + code);
        String reason = "";
        if (payload.length > 2) {
            try {
                reason = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(payload, 2, payload.length - 2)).toString();
            } catch (CharacterCodingException error) {
                throw invalidPayload("invalid close reason UTF-8");
            }
        }
        return new CloseFrame(code, reason);
    }

    private static boolean isValidCloseCode(int code) {
        if (code >= 3000 && code <= 4999) return true;
        if (code < 1000 || code > 1014) return false;
        return code != 1004 && code != 1005 && code != 1006;
    }

    private static void requireMessageSize(byte[] data) throws IOException {
        if (data == null) throw new IOException("message is null");
        if (data.length > MAX_MESSAGE_LEN) throw new IOException("message too large");
    }

    private static String websocketAccept(String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                .getBytes("US-ASCII"));
        return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
    }

    private static final class FrameHeader {
        final boolean fin;
        final int opcode;
        final int length;
        final boolean masked;

        FrameHeader(boolean fin, int opcode, int length, boolean masked) {
            this.fin = fin;
            this.opcode = opcode;
            this.length = length;
            this.masked = masked;
        }
    }

    private static final class CloseFrame {
        final int code;
        final String reason;

        CloseFrame(int code, String reason) {
            this.code = code;
            this.reason = reason;
        }
    }

}
