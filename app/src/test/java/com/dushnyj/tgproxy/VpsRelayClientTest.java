package com.dushnyj.tgproxy;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VpsRelayClientTest {
    private TinyRelayServer server;

    @After
    public void tearDown() throws Exception {
        if (server != null) server.close();
    }

    @Test
    public void checkPassesHealthVersionAndRoutesWithBearerToken() throws Exception {
        server = TinyRelayServer.start("token", 1, 1, 200);
        VpsRelayConfig config = config("token", false);

        VpsRelayCheckResult result = new VpsRelayClient().check(config, dcRules());

        assertEquals(VpsRelayCheckResult.Status.OK, result.status());
        assertTrue(result.routeReport().contains("DC2 main"));
        assertTrue(server.lastRoutesBody.contains("\"dc\":1"));
        assertTrue(server.lastRoutesBody.contains("\"ip\":\"149.154.167.51\""));
        assertTrue(server.lastRoutesBody.contains("\"dc\":5"));
        assertTrue(server.lastRoutesBody.contains("\"ip\":\"149.154.171.5\""));
        assertTrue(server.lastRoutesBody.contains("\"dc\":203"));
        assertTrue(server.lastRoutesBody.contains("\"ip\":\"91.105.192.100\""));
    }

    @Test
    public void checkUsesRelayPathForManagementEndpointsWhenReverseProxyIsIsolated() throws Exception {
        server = TinyRelayServer.startPrefixed("token", "/apiws", 1, 1, 200);
        VpsRelayConfig config = config("token", false);

        VpsRelayCheckResult result = new VpsRelayClient().check(config, dcRules());

        assertEquals(VpsRelayCheckResult.Status.OK, result.status());
        assertTrue(result.routeReport().contains("DC2 main"));
    }

    @Test
    public void wrongTokenIsReportedAsAuthFailure() throws Exception {
        server = TinyRelayServer.start("expected", 1, 1, 200);
        VpsRelayConfig config = config("wrong", false);

        VpsRelayCheckResult result = new VpsRelayClient().check(config, dcRules());

        assertEquals(VpsRelayCheckResult.Status.WRONG_TOKEN, result.status());
    }

    @Test
    public void oldRelayVersionIsRejected() throws Exception {
        server = TinyRelayServer.start("token", 0, 1, 200);
        VpsRelayConfig config = config("token", false);

        VpsRelayCheckResult result = new VpsRelayClient().check(config, dcRules());

        assertEquals(VpsRelayCheckResult.Status.OUTDATED_VERSION, result.status());
    }

    @Test
    public void successfulNonRelayVersionBodyIsReportedAsWrongEndpoint() throws Exception {
        server = TinyRelayServer.startWithVersionBody("token",
                "Slovofon service is online.", 200);
        VpsRelayConfig config = config("token", false);

        VpsRelayCheckResult result = new VpsRelayClient().check(config, dcRules());

        assertEquals(VpsRelayCheckResult.Status.UNAVAILABLE, result.status());
        assertEquals("version endpoint returned non-relay response", result.message());
    }

    @Test
    public void inspectReportsOutdatedServerVersion() throws Exception {
        server = TinyRelayServer.start("token", "1.0.0", 1, 1, 200);
        VpsRelayConfig config = config("token", false);

        VpsRelayInfo info = new VpsRelayClient().inspect(config, "1.0.1");

        assertEquals(VpsRelayCheckResult.Status.OK, info.status());
        assertEquals("1.0.0", info.relayVersion());
        assertEquals("1.0.1", info.targetVersion());
        assertEquals(1, info.protocol());
        assertEquals(1, info.minAppProtocol());
        assertTrue(info.updateAvailable());
    }

    @Test
    public void tlsHandshakeErrorIsReportedSeparately() throws Exception {
        server = TinyRelayServer.start("token", 1, 1, 200);
        VpsRelayConfig config = config("token", true);

        VpsRelayCheckResult result = new VpsRelayClient().check(config, dcRules());

        assertEquals(VpsRelayCheckResult.Status.TLS_ERROR, result.status());
    }

    private VpsRelayConfig config(String token, boolean tls) {
        return VpsRelayConfig.manual(true, "Local relay", "127.0.0.1",
                server.port(), tls, "/apiws", token, "");
    }

    private Map<Integer, String> dcRules() {
        LinkedHashMap<Integer, String> rules = new LinkedHashMap<>();
        rules.put(2, "149.154.167.220");
        return rules;
    }

    private static final class TinyRelayServer implements Closeable {
        private final ServerSocket serverSocket;
        private final String expectedToken;
        private final String version;
        private final int protocol;
        private final int minAppProtocol;
        private final int routeStatus;
        private String rawVersionBody;
        private String pathPrefix = "";
        private volatile String lastRoutesBody = "";
        private volatile boolean running = true;
        private Thread thread;

        private TinyRelayServer(String expectedToken, String version, int protocol,
                                int minAppProtocol, int routeStatus) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.expectedToken = expectedToken;
            this.version = version;
            this.protocol = protocol;
            this.minAppProtocol = minAppProtocol;
            this.routeStatus = routeStatus;
        }

        static TinyRelayServer start(String expectedToken, int protocol,
                                     int minAppProtocol, int routeStatus) throws IOException {
            return start(expectedToken, "1.0.0", protocol, minAppProtocol, routeStatus);
        }

        static TinyRelayServer start(String expectedToken, String version, int protocol,
                                     int minAppProtocol, int routeStatus) throws IOException {
            return start(expectedToken, version, "", protocol, minAppProtocol, routeStatus);
        }

        static TinyRelayServer startPrefixed(String expectedToken, String pathPrefix, int protocol,
                                             int minAppProtocol, int routeStatus) throws IOException {
            return start(expectedToken, "1.0.0", pathPrefix, protocol, minAppProtocol, routeStatus);
        }

        static TinyRelayServer startWithVersionBody(String expectedToken, String versionBody,
                                                    int routeStatus) throws IOException {
            TinyRelayServer server = new TinyRelayServer(expectedToken, "1.0.0", 1,
                    1, routeStatus);
            server.rawVersionBody = versionBody;
            server.thread = new Thread(server::serve, "tiny-vps-relay-test");
            server.thread.setDaemon(true);
            server.thread.start();
            return server;
        }

        private static TinyRelayServer start(String expectedToken, String version,
                                             String pathPrefix, int protocol,
                                             int minAppProtocol, int routeStatus) throws IOException {
            TinyRelayServer server = new TinyRelayServer(expectedToken, version, protocol,
                    minAppProtocol, routeStatus);
            server.pathPrefix = pathPrefix == null ? "" : pathPrefix;
            server.thread = new Thread(server::serve, "tiny-vps-relay-test");
            server.thread.setDaemon(true);
            server.thread.start();
            return server;
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve() {
            while (running) {
                try {
                    handle(serverSocket.accept());
                } catch (IOException ignored) {
                    if (!running) return;
                }
            }
        }

        private void handle(Socket socket) {
            try (Socket accepted = socket) {
                accepted.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(accepted.getInputStream(), "UTF-8"));
                String request = reader.readLine();
                if (request == null || !request.contains(" HTTP/")) return;
                String path = request.split(" ", 3)[1];
                Map<String, String> headers = new LinkedHashMap<>();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                                line.substring(colon + 1).trim());
                    }
                }
                if (!("Bearer " + expectedToken).equals(headers.get("authorization"))) {
                    respond(accepted, 401, "unauthorized");
                    return;
                }
                int contentLength = parseContentLength(headers.get("content-length"));
                StringBuilder requestBody = new StringBuilder();
                for (int i = 0; i < contentLength; i++) {
                    int ch = reader.read();
                    if (ch < 0) break;
                    requestBody.append((char) ch);
                }

                if ((pathPrefix + "/healthz").equals(path)) {
                    respond(accepted, 200, "ok");
                } else if ((pathPrefix + "/version").equals(path)) {
                    String body = rawVersionBody == null
                            ? "{\"name\":\"tgproxy-relay\",\"version\":\"" + version + "\","
                            + "\"protocol\":" + protocol
                            + ",\"minAppProtocol\":" + minAppProtocol + "}"
                            : rawVersionBody;
                    respond(accepted, 200, body);
                } else if ((pathPrefix + "/test-routes").equals(path)) {
                    lastRoutesBody = requestBody.toString();
                    respond(accepted, routeStatus, "DC2 main OK\nDC2 media OK");
                } else {
                    respond(accepted, 404, "not found");
                }
            } catch (IOException ignored) {
            }
        }

        private static void respond(Socket socket, int status, String body) throws IOException {
            byte[] bytes = body.getBytes("UTF-8");
            String reason = status == 200 ? "OK" : "Error";
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            out.write(bytes);
            out.flush();
        }

        private static int parseContentLength(String value) {
            try {
                return value == null ? 0 : Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        @Override
        public void close() throws IOException {
            running = false;
            serverSocket.close();
        }
    }
}

