package com.dushnyj.tgproxy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

final class VpsRelayClient {
    static final int APP_PROTOCOL = 1;
    static final int APP_PROTOCOL_MAX = 2;
    private static final int TIMEOUT_MS = 7000;
    private static final int MANAGEMENT_READ_TIMEOUT_MS = 20_000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final long PRODUCTION_END_TO_END_TIMEOUT_MS = 40_000L;
    private static final long TEST_END_TO_END_TIMEOUT_MS = 22_000L;
    private static final int PRODUCTION_VERIFY_THREADS = 3;
    private static final int MAX_SCOPE_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 300L;
    private static final AtomicInteger VERIFY_THREAD_ID = new AtomicInteger(1);

    interface RouteVerifier {
        RouteValidation verify(VpsRelayConfig config, Map<Integer, String> dcRules) throws Exception;
    }

    enum CheckStage {
        CONNECTION,
        AUTHORIZATION,
        HEALTH,
        SERVER_ROUTES,
        TELEGRAM_ROUTES
    }

    interface ProgressListener {
        /** completedSteps is in the range 0..5; currentStage is null after success. */
        void onProgress(int completedSteps, CheckStage currentStage);
    }

    static final class RouteValidation {
        final String blockingFailures;
        final String advisoryFailures;

        private RouteValidation(String blockingFailures, String advisoryFailures) {
            this.blockingFailures = safe(blockingFailures);
            this.advisoryFailures = safe(advisoryFailures);
        }

        static RouteValidation ok() {
            return new RouteValidation("", "");
        }

        static RouteValidation blocking(String failures) {
            return new RouteValidation(failures, "");
        }

        static RouteValidation advisory(String failures) {
            return new RouteValidation("", failures);
        }

        static RouteValidation of(String blockingFailures, String advisoryFailures) {
            return new RouteValidation(blockingFailures, advisoryFailures);
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private final RouteVerifier routeVerifier;

    VpsRelayClient() {
        this(VpsRelayClient::verifyEndToEndRoutes);
    }

    VpsRelayClient(RouteVerifier routeVerifier) {
        this.routeVerifier = routeVerifier == null
                ? VpsRelayClient::verifyEndToEndRoutes : routeVerifier;
    }

    VpsRelayCheckResult check(VpsRelayConfig config, Map<Integer, String> dcRules) {
        return check(config, dcRules, null);
    }

    VpsRelayCheckResult check(VpsRelayConfig config, Map<Integer, String> dcRules,
                              ProgressListener progress) {
        if (config == null || !config.isUsable()) {
            return VpsRelayCheckResult.of(VpsRelayCheckResult.Status.BAD_CONFIG,
                    "relay is not configured");
        }
        try {
            report(progress, 0, CheckStage.CONNECTION);
            HttpResult version = requestManagementWithRetry(config, "GET", "/version", "");
            report(progress, 1, CheckStage.AUTHORIZATION);
            if (isAuthFailure(version.code)) return wrongToken();
            if (!version.isSuccessful()) return unavailable("version failed: " + version.code);
            if (!isRelayVersionBody(version.body)) {
                return unavailable("version endpoint returned non-relay response");
            }
            int protocol = intJson(version.body, "protocol", 0);
            int minAppProtocol = intJson(version.body, "minAppProtocol", 0);
            if (protocol < APP_PROTOCOL || protocol > APP_PROTOCOL_MAX
                    || minAppProtocol > APP_PROTOCOL_MAX) {
                return VpsRelayCheckResult.of(VpsRelayCheckResult.Status.OUTDATED_VERSION,
                        "relay protocol is not compatible");
            }

            VpsRelayCapabilities capabilities = VpsRelayCapabilities.unknown();
            HttpResult capabilityResponse = requestManagementWithRetry(
                    config, "GET", "/capabilities", "");
            if (isAuthFailure(capabilityResponse.code)) return wrongToken();
            if (capabilityResponse.isSuccessful()) {
                capabilities = VpsRelayCapabilities.parse(capabilityResponse.body);
                if (!capabilities.compatible(APP_PROTOCOL, APP_PROTOCOL_MAX)) {
                    return VpsRelayCheckResult.of(VpsRelayCheckResult.Status.OUTDATED_VERSION,
                            "relay capabilities are not compatible");
                }
            } else if (capabilityResponse.code != 404 && capabilityResponse.code != 405) {
                return unavailable("capabilities failed: " + capabilityResponse.code);
            }

            report(progress, 2, CheckStage.HEALTH);
            HttpResult health = requestManagementWithRetry(config, "GET", "/healthz", "");
            if (isAuthFailure(health.code)) return wrongToken();
            if (!health.isSuccessful()) return unavailable("healthz failed: " + health.code);

            report(progress, 3, CheckStage.SERVER_ROUTES);
            Map<Integer, String> relayRoutes = effectiveProductionRoutes(capabilities, dcRules);
            HttpResult routes = requestManagementWithRetry(config, "POST", "/test-routes",
                    testRoutesBody(relayRoutes));
            if (isAuthFailure(routes.code)) return wrongToken();
            if (!routes.isSuccessful()) {
                return unavailable("test-routes failed: " + routes.code
                        + compactBody(routes.body));
            }
            if (routes.body.toUpperCase(Locale.US).contains(" ERROR")) {
                return unavailable("test-routes reported unavailable routes"
                        + compactBody(routes.body));
            }
            report(progress, 4, CheckStage.TELEGRAM_ROUTES);
            RouteValidation validation = routeVerifier.verify(config, relayRoutes);
            if (validation == null) validation = RouteValidation.ok();
            if (!validation.blockingFailures.isEmpty()) {
                return unavailable("end-to-end production routes failed: "
                        + validation.blockingFailures);
            }
            if (!validation.advisoryFailures.isEmpty()) {
                DiagnosticsLog.record("Telegram test environment advisory: "
                        + validation.advisoryFailures);
            }
            String instanceId = booleanJson(version.body, "identityPersistent", false)
                    ? stringJson(version.body, "instanceId", "") : "";
            report(progress, 5, null);
            return VpsRelayCheckResult.ok(routes.body,
                    stringJson(version.body, "version", ""),
                    validation.advisoryFailures, capabilities,
                    instanceId);
        } catch (SSLException e) {
            return VpsRelayCheckResult.of(VpsRelayCheckResult.Status.TLS_ERROR,
                    "TLS handshake failed");
        } catch (Exception e) {
            return unavailable(e.getClass().getSimpleName());
        }
    }

    private static void report(ProgressListener progress, int completedSteps,
                               CheckStage currentStage) {
        if (progress == null) return;
        try {
            progress.onProgress(completedSteps, currentStage);
        } catch (RuntimeException ignored) {
            // A UI progress listener must never change the network-check result.
        }
    }

    VpsRelayInfo inspect(VpsRelayConfig config, String targetVersion) {
        if (config == null || !config.isUsable()) {
            return VpsRelayInfo.of(VpsRelayCheckResult.Status.BAD_CONFIG,
                    "relay is not configured", "", targetVersion, 0, 0);
        }
        try {
            HttpResult version = requestManagementWithRetry(config, "GET", "/version", "");
            if (isAuthFailure(version.code)) {
                return VpsRelayInfo.of(VpsRelayCheckResult.Status.WRONG_TOKEN,
                        "relay token was rejected", "", targetVersion, 0, 0);
            }
            if (!version.isSuccessful()) {
                return VpsRelayInfo.of(VpsRelayCheckResult.Status.UNAVAILABLE,
                        "version failed: " + version.code, "", targetVersion, 0, 0);
            }
            if (!isRelayVersionBody(version.body)) {
                return VpsRelayInfo.of(VpsRelayCheckResult.Status.UNAVAILABLE,
                        "version endpoint returned non-relay response", "", targetVersion, 0, 0);
            }
            String relayVersion = stringJson(version.body, "version", "");
            int protocol = intJson(version.body, "protocol", 0);
            int minAppProtocol = intJson(version.body, "minAppProtocol", 0);
            VpsRelayCheckResult.Status status = VpsRelayCheckResult.Status.OK;
            String message = "relay version is available";
            if (protocol < APP_PROTOCOL || protocol > APP_PROTOCOL_MAX
                    || minAppProtocol > APP_PROTOCOL_MAX) {
                status = VpsRelayCheckResult.Status.OUTDATED_VERSION;
                message = "relay protocol is not compatible";
            }
            VpsRelayCapabilities capabilities = VpsRelayCapabilities.unknown();
            if (status == VpsRelayCheckResult.Status.OK) {
                HttpResult response = requestManagementWithRetry(
                        config, "GET", "/capabilities", "");
                if (isAuthFailure(response.code)) {
                    return VpsRelayInfo.of(VpsRelayCheckResult.Status.WRONG_TOKEN,
                            "relay token was rejected", relayVersion, targetVersion,
                            protocol, minAppProtocol);
                }
                if (response.isSuccessful()) {
                    capabilities = VpsRelayCapabilities.parse(response.body);
                    if (!capabilities.compatible(APP_PROTOCOL, APP_PROTOCOL_MAX)) {
                        status = VpsRelayCheckResult.Status.OUTDATED_VERSION;
                        message = "relay capabilities are not compatible";
                    }
                } else if (response.code != 404 && response.code != 405) {
                    status = VpsRelayCheckResult.Status.UNAVAILABLE;
                    message = "capabilities failed: " + response.code;
                }
            }
            return VpsRelayInfo.of(status, message, relayVersion, targetVersion,
                    protocol, minAppProtocol, capabilities);
        } catch (SSLException e) {
            return VpsRelayInfo.of(VpsRelayCheckResult.Status.TLS_ERROR,
                    "TLS handshake failed", "", targetVersion, 0, 0);
        } catch (Exception e) {
            return VpsRelayInfo.of(VpsRelayCheckResult.Status.UNAVAILABLE,
                    e.getClass().getSimpleName(), "", targetVersion, 0, 0);
        }
    }

    private static VpsRelayCheckResult wrongToken() {
        return VpsRelayCheckResult.of(VpsRelayCheckResult.Status.WRONG_TOKEN,
                "relay token was rejected");
    }

    private static VpsRelayCheckResult unavailable(String message) {
        return VpsRelayCheckResult.of(VpsRelayCheckResult.Status.UNAVAILABLE, message);
    }

    private static HttpResult request(VpsRelayConfig config, String method,
                                      String path, String body) throws Exception {
        return request(config, config.token(), method, path, body);
    }

    private static HttpResult request(VpsRelayConfig config, String bearer, String method,
                                      String path, String body) throws Exception {
        if (!validBearer(bearer)) throw new IOException("invalid bearer credential");
        if (!config.tls()) return requestPlain(config, bearer, method, path, body);
        URL url = new URL(config.baseUrl() + path);
        RelayNetworkBinding.Binding network = RelayNetworkBinding.capture();
        HttpURLConnection connection = (HttpURLConnection) network.openConnection(url);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(MANAGEMENT_READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + bearer);
        connection.setRequestProperty("Accept", "text/plain, application/json");
        if (body != null && !body.isEmpty()) {
            byte[] bytes = body.getBytes("UTF-8");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
        }
        int code = connection.getResponseCode();
        return new HttpResult(code, readBody(connection, code));
    }

    /**
     * Android's Network Security Config intentionally blocks generic cleartext HTTP.
     * The Relay UI nevertheless supports an explicit non-TLS/IP mode, so its authenticated
     * management requests use the same bounded raw TCP transport as the WebSocket tunnel.
     */
    private static HttpResult requestPlain(VpsRelayConfig config, String bearer, String method,
                                           String path, String body) throws Exception {
        byte[] requestBody = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        RelayNetworkBinding.Binding network = RelayNetworkBinding.capture();
        try (Socket socket = connectPlainSocket(network, config.host(), config.port())) {
            socket.setSoTimeout(MANAGEMENT_READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            StringBuilder headers = new StringBuilder();
            headers.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(httpHost(config.host(), config.port(), false))
                    .append("\r\n")
                    .append("Authorization: Bearer ").append(bearer).append("\r\n")
                    .append("Accept: text/plain, application/json\r\n")
                    .append("Connection: close\r\n");
            if (requestBody.length > 0) {
                headers.append("Content-Type: application/json; charset=utf-8\r\n")
                        .append("Content-Length: ").append(requestBody.length).append("\r\n");
            }
            headers.append("\r\n");
            out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            if (requestBody.length > 0) out.write(requestBody);
            out.flush();

            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            int[] headerBytes = {0};
            String statusLine = readHttpLine(in, headerBytes);
            if (statusLine == null) throw new IOException("empty HTTP response");
            String[] statusParts = statusLine.split(" ", 3);
            if (statusParts.length < 2) throw new IOException("invalid HTTP status");
            int status;
            try { status = Integer.parseInt(statusParts[1]); }
            catch (NumberFormatException error) { throw new IOException("invalid HTTP status", error); }

            LinkedHashMap<String, String> responseHeaders = new LinkedHashMap<>();
            while (true) {
                String line = readHttpLine(in, headerBytes);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0) {
                    responseHeaders.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                            line.substring(colon + 1).trim());
                }
            }
            byte[] responseBody;
            String transferEncoding = responseHeaders.get("transfer-encoding");
            if (transferEncoding != null
                    && transferEncoding.toLowerCase(Locale.US).contains("chunked")) {
                responseBody = readChunkedBody(in, headerBytes);
            } else {
                int contentLength = parseBoundedLength(responseHeaders.get("content-length"));
                responseBody = contentLength >= 0
                        ? readExactly(in, contentLength) : readToEnd(in);
            }
            return new HttpResult(status, new String(responseBody, StandardCharsets.UTF_8));
        }
    }

    private static Socket connectPlainSocket(RelayNetworkBinding.Binding network, String host,
                                             int port) throws Exception {
        Exception last = null;
        InetAddress[] addresses = network.resolveAll(host);
        if (addresses == null || addresses.length == 0) throw new IOException("DNS returned no address");
        for (InetAddress address : addresses) {
            Socket socket = network.newSocket();
            try {
                socket.connect(new InetSocketAddress(address, port), TIMEOUT_MS);
                return socket;
            } catch (Exception error) {
                last = error;
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
        if (last != null) throw last;
        throw new IOException("connection failed");
    }

    private static String httpHost(String host, int port, boolean tls) {
        String value = host == null ? "" : host.trim();
        if (value.indexOf(':') >= 0 && !value.startsWith("[")) value = "[" + value + "]";
        int defaultPort = tls ? 443 : 80;
        return port > 0 && port != defaultPort ? value + ":" + port : value;
    }

    private static String readHttpLine(InputStream in, int[] totalBytes) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = in.read();
            if (value < 0) return line.length() == 0 ? null : line.toString();
            totalBytes[0]++;
            if (totalBytes[0] > MAX_HEADER_BYTES) throw new IOException("HTTP headers too large");
            if (value == '\n') {
                if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
                    line.setLength(line.length() - 1);
                }
                return line.toString();
            }
            line.append((char) value);
            if (line.length() > MAX_LINE_BYTES) throw new IOException("HTTP line too large");
        }
    }

    private static int parseBoundedLength(String value) throws IOException {
        if (value == null || value.trim().isEmpty()) return -1;
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0 || parsed > MAX_RESPONSE_BYTES) {
                throw new IOException("HTTP response too large");
            }
            return (int) parsed;
        } catch (NumberFormatException error) {
            throw new IOException("invalid Content-Length", error);
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        if (length < 0 || length > MAX_RESPONSE_BYTES) throw new IOException("HTTP response too large");
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(body, offset, length - offset);
            if (count < 0) throw new IOException("truncated HTTP response");
            offset += count;
        }
        return body;
    }

    private static byte[] readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (body.size() > MAX_RESPONSE_BYTES - count) {
                throw new IOException("HTTP response too large");
            }
            body.write(buffer, 0, count);
        }
        return body.toByteArray();
    }

    private static byte[] readChunkedBody(InputStream in, int[] headerBytes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readHttpLine(in, headerBytes);
            if (sizeLine == null) throw new IOException("truncated chunked response");
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim();
            int size;
            try { size = Integer.parseInt(hex, 16); }
            catch (NumberFormatException error) { throw new IOException("invalid chunk size", error); }
            if (size < 0 || body.size() > MAX_RESPONSE_BYTES - size) {
                throw new IOException("HTTP response too large");
            }
            if (size == 0) {
                while (true) {
                    String trailer = readHttpLine(in, headerBytes);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                return body.toByteArray();
            }
            body.write(readExactly(in, size));
            String terminator = readHttpLine(in, headerBytes);
            if (terminator == null || !terminator.isEmpty()) {
                throw new IOException("invalid chunk terminator");
            }
        }
    }

    private static String readBody(HttpURLConnection connection, int code) {
        InputStream stream = code >= 400 ? connection.getErrorStream() : null;
        try {
            if (stream == null) stream = connection.getInputStream();
            if (stream == null) return "";
            try (InputStream input = stream) {
                return new String(readToEnd(input), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    private static HttpResult requestManagement(VpsRelayConfig config, String method,
                                                String endpoint, String body) throws Exception {
        return requestManagement(config, config.token(), method, endpoint, body);
    }

    private static HttpResult requestManagementWithRetry(VpsRelayConfig config, String method,
                                                          String endpoint, String body)
            throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return requestManagement(config, method, endpoint, body);
            } catch (Exception error) {
                last = error;
                if (attempt > 0 || !transientNetworkError(error)) throw error;
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Relay check interrupted", interrupted);
                }
            }
        }
        throw last == null ? new IOException("Relay check failed") : last;
    }

    private static boolean transientNetworkError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.net.UnknownHostException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.NoRouteToHostException) return true;
            current = current.getCause();
        }
        return false;
    }

    static HttpResult requestOwner(VpsRelayConfig config, String adminToken, String method,
                                   String endpoint, String body) throws Exception {
        if (config == null || !config.hasValidEndpoint() || adminToken == null
                || !validBearer(adminToken.trim())) {
            throw new IOException("owner access is not configured");
        }
        return requestManagement(config, adminToken.trim(), method, endpoint, body);
    }

    private static boolean validBearer(String value) {
        if (value == null || value.isEmpty() || value.length() > 512) return false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch < 0x21 || ch > 0x7e) return false;
        }
        return true;
    }

    private static HttpResult requestManagement(VpsRelayConfig config, String bearer,
                                                String method, String endpoint,
                                                String body) throws Exception {
        String prefixed = prefixedManagementPath(config.path(), endpoint);
        HttpResult result = request(config, bearer, method, prefixed, body);
        if (!prefixed.equals(endpoint) && (result.code == 404 || result.code == 405)) {
            return request(config, bearer, method, endpoint, body);
        }
        return result;
    }

    private static String prefixedManagementPath(String basePath, String endpoint) {
        String endpointPath = endpoint == null || endpoint.isEmpty() ? "/" : endpoint;
        if (!endpointPath.startsWith("/")) endpointPath = "/" + endpointPath;
        String base = basePath == null ? "" : basePath.trim();
        if (base.isEmpty() || "/".equals(base)) return endpointPath;
        while (base.endsWith("/") && base.length() > 1) {
            base = base.substring(0, base.length() - 1);
        }
        return base + endpointPath;
    }

    private static boolean isAuthFailure(int code) {
        return code == 401 || code == 403;
    }

    private static boolean isRelayVersionBody(String body) {
        return "tgproxy-relay".equals(stringJson(body, "name", ""))
                && hasJsonKey(body, "protocol")
                && hasJsonKey(body, "minAppProtocol");
    }

    private static boolean hasJsonKey(String json, String key) {
        if (json == null || key == null) return false;
        return json.contains("\"" + key + "\"");
    }

    private static String testRoutesBody(Map<Integer, String> routes) {
        StringBuilder out = new StringBuilder();
        out.append("{\"dcs\":[");
        boolean first = true;
        for (Map.Entry<Integer, String> entry : routes.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append("{\"dc\":").append(entry.getKey()).append(",\"ip\":\"")
                    .append(entry.getValue() == null ? "" : entry.getValue())
                    .append("\"}");
        }
        out.append("]}");
        return out.toString();
    }

    private static Map<Integer, String> effectiveProductionRoutes(
            VpsRelayCapabilities capabilities, Map<Integer, String> localRules) {
        LinkedHashMap<Integer, String> routes = new LinkedHashMap<>();
        if (capabilities != null && capabilities.known()) {
            for (Integer dc : capabilities.productionDcs()) {
                if (dc == null || dc <= 0) continue;
                String local = localRules == null ? "" : localRules.get(dc);
                routes.put(dc, local == null ? "" : local);
            }
            return routes;
        }
        routes.putAll(MtProtoConfig.relayDcRules());
        if (localRules != null) {
            for (Map.Entry<Integer, String> entry : localRules.entrySet()) {
                if (entry.getKey() != null && entry.getKey() > 0) {
                    routes.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return routes;
    }

    private static RouteValidation verifyEndToEndRoutes(VpsRelayConfig config,
                                                        Map<Integer, String> dcRules)
            throws Exception {
        LinkedHashMap<Integer, String> required = new LinkedHashMap<>();
        if (dcRules != null) {
            for (Map.Entry<Integer, String> entry : dcRules.entrySet()) {
                if (entry.getKey() != null && entry.getKey() > 0) {
                    required.put(entry.getKey(), entry.getValue());
                }
            }
        }
        ArrayList<RelayDcGroup> productionGroups = new ArrayList<>();
        for (Integer dc : required.keySet()) {
            if (dc == null || dc <= 0) continue;
            productionGroups.add(new RelayDcGroup(dc, false));
        }
        RouteBatchResult production = verifyRelayGroups(config, productionGroups,
                PRODUCTION_VERIFY_THREADS, PRODUCTION_END_TO_END_TIMEOUT_MS,
                MAX_SCOPE_ATTEMPTS);
        if (production.incomplete) {
            production.failures.add("production route verification timeout");
        }
        String blockingFailures = joinFailures(production.failures);
        if (!blockingFailures.isEmpty()) return RouteValidation.blocking(blockingFailures);

        ArrayList<RelayDcGroup> testGroups = new ArrayList<>();
        for (Integer dc : MtProtoConfig.testDcRules().keySet()) {
            if (dc == null || dc <= 0) continue;
            testGroups.add(new RelayDcGroup(dc, true));
        }
        RouteBatchResult test = verifyRelayGroups(config, testGroups, 1,
                TEST_END_TO_END_TIMEOUT_MS, 1);
        if (test.incomplete) {
            test.failures.add("test environment verification timeout");
        }
        return RouteValidation.advisory(joinFailures(test.failures));
    }

    private static RouteBatchResult verifyRelayGroups(VpsRelayConfig config,
                                                       List<RelayDcGroup> groups,
                                                       int maxThreads,
                                                       long timeoutMs,
                                                       int attempts) {
        RouteBatchResult result = new RouteBatchResult();
        if (groups == null || groups.isEmpty()) return result;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable,
                    "tg-relay-verify-" + VERIFY_THREAD_ID.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(Math.max(1, maxThreads), groups.size()), threadFactory);
        ArrayList<Future<List<String>>> futures = new ArrayList<>();
        for (RelayDcGroup group : groups) {
            futures.add(executor.submit((Callable<List<String>>) () ->
                    verifyRelayGroup(config, group, attempts)));
        }
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
        try {
            for (Future<List<String>> future : futures) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    result.incomplete = true;
                    break;
                }
                long waitMs = Math.max(1L,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                try {
                    List<String> failures = future.get(waitMs, TimeUnit.MILLISECONDS);
                    if (failures != null) result.failures.addAll(failures);
                } catch (TimeoutException timeout) {
                    result.incomplete = true;
                    break;
                }
            }
        } catch (Exception error) {
            String message = error.getMessage();
            result.failures.add(message == null || message.trim().isEmpty()
                    ? error.getClass().getSimpleName() : message.trim());
        } finally {
            for (Future<List<String>> future : futures) future.cancel(true);
            executor.shutdownNow();
        }
        return result;
    }

    private static List<String> verifyRelayGroup(VpsRelayConfig config,
                                                 RelayDcGroup group,
                                                 int attempts) {
        ArrayList<String> failures = new ArrayList<>();
        for (boolean media : new boolean[]{false, true}) {
            RelayScope scope = new RelayScope(group.dc, media, group.test);
            String failure = verifyRelayScopeWithRetry(config, scope, attempts);
            if (!failure.isEmpty()) failures.add(failure);
            if (Thread.currentThread().isInterrupted()) break;
        }
        return failures;
    }

    private static String verifyRelayScopeWithRetry(VpsRelayConfig config, RelayScope scope,
                                                    int attempts) {
        String failure = "";
        int count = Math.max(1, attempts);
        for (int attempt = 1; attempt <= count; attempt++) {
            failure = verifyRelayScope(config, scope);
            if (failure.isEmpty() || attempt == count) return failure;
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return failure;
            }
        }
        return failure;
    }

    private static String verifyRelayScope(VpsRelayConfig config, RelayScope scope) {
        RawWebSocket socket = null;
        try {
            ConnectBudget budget = new ConnectBudget(TIMEOUT_MS);
            socket = RawWebSocket.connectRelay(config, scope.dc, scope.media, scope.test,
                    budget, TIMEOUT_MS, null);
            RouteProbeClient.verifyTelegramDcResponse(
                    socket, scope.dc, scope.media, budget, TIMEOUT_MS);
            return "";
        } catch (Exception error) {
            String message = error.getMessage();
            String value = message == null || message.trim().isEmpty()
                    ? error.getClass().getSimpleName() : message.trim();
            value = value.replace('\r', ' ').replace('\n', ' ');
            if (value.length() > 80) value = value.substring(0, 80);
            return (scope.test ? "test " : "") + "DC" + scope.dc
                    + (scope.media ? " media=" : " main=") + value;
        } finally {
            if (socket != null) try { socket.abort(); } catch (Exception ignored) {}
        }
    }

    private static final class RelayScope {
        final int dc;
        final boolean media;
        final boolean test;

        RelayScope(int dc, boolean media, boolean test) {
            this.dc = dc;
            this.media = media;
            this.test = test;
        }
    }

    private static final class RelayDcGroup {
        final int dc;
        final boolean test;

        RelayDcGroup(int dc, boolean test) {
            this.dc = dc;
            this.test = test;
        }
    }

    private static final class RouteBatchResult {
        final ArrayList<String> failures = new ArrayList<>();
        boolean incomplete;
    }

    private static String joinFailures(List<String> failures) {
        StringBuilder out = new StringBuilder();
        for (String failure : failures) {
            if (failure == null || failure.isEmpty()) continue;
            if (out.length() > 0) out.append("; ");
            out.append(failure);
            if (out.length() >= 400) return out.substring(0, 400);
        }
        return out.toString();
    }

    private static String compactBody(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        String compact = body.trim().replace('\r', ' ').replace('\n', ';');
        if (compact.length() > 240) compact = compact.substring(0, 240);
        return ": " + compact;
    }

    private static int intJson(String json, String key, int fallback) {
        if (json == null || key == null) return fallback;
        String marker = "\"" + key + "\"";
        int index = json.indexOf(marker);
        if (index < 0) return fallback;
        int colon = json.indexOf(':', index + marker.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end <= start) return fallback;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean booleanJson(String json, String key, boolean fallback) {
        if (json == null || key == null) return fallback;
        String marker = "\"" + key + "\"";
        int index = json.indexOf(marker);
        if (index < 0) return fallback;
        int colon = json.indexOf(':', index + marker.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (json.regionMatches(start, "true", 0, 4)) return true;
        if (json.regionMatches(start, "false", 0, 5)) return false;
        return fallback;
    }

    private static String stringJson(String json, String key, String fallback) {
        if (json == null || key == null) return fallback;
        String marker = "\"" + key + "\"";
        int index = json.indexOf(marker);
        if (index < 0) return fallback;
        int colon = json.indexOf(':', index + marker.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '"') return fallback;
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return fallback;
    }

    static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        boolean isSuccessful() {
            return code >= 200 && code < 300;
        }
    }
}
