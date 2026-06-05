package com.dushnyj.tgproxy;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import javax.net.ssl.SSLException;

final class VpsRelayClient {
    static final int APP_PROTOCOL = 1;
    private static final int TIMEOUT_MS = 7000;

    VpsRelayCheckResult check(VpsRelayConfig config, Map<Integer, String> dcRules) {
        if (config == null || !config.isUsable()) {
            return VpsRelayCheckResult.of(VpsRelayCheckResult.Status.BAD_CONFIG,
                    "relay is not configured");
        }
        try {
            HttpResult version = requestManagement(config, "GET", "/version", "");
            if (isAuthFailure(version.code)) return wrongToken();
            if (!version.isSuccessful()) return unavailable("version failed: " + version.code);
            if (!isRelayVersionBody(version.body)) {
                return unavailable("version endpoint returned non-relay response");
            }
            int protocol = intJson(version.body, "protocol", 0);
            int minAppProtocol = intJson(version.body, "minAppProtocol", 0);
            if (protocol != APP_PROTOCOL || minAppProtocol > APP_PROTOCOL) {
                return VpsRelayCheckResult.of(VpsRelayCheckResult.Status.OUTDATED_VERSION,
                        "relay protocol is not compatible");
            }

            HttpResult health = requestManagement(config, "GET", "/healthz", "");
            if (isAuthFailure(health.code)) return wrongToken();
            if (!health.isSuccessful()) return unavailable("healthz failed: " + health.code);

            HttpResult routes = requestManagement(config, "POST", "/test-routes",
                    testRoutesBody(MtProtoConfig.relayDcRules()));
            if (isAuthFailure(routes.code)) return wrongToken();
            if (!routes.isSuccessful()) return unavailable("test-routes failed: " + routes.code);
            return VpsRelayCheckResult.ok(routes.body);
        } catch (SSLException e) {
            return VpsRelayCheckResult.of(VpsRelayCheckResult.Status.TLS_ERROR,
                    "TLS handshake failed");
        } catch (Exception e) {
            return unavailable(e.getClass().getSimpleName());
        }
    }

    VpsRelayInfo inspect(VpsRelayConfig config, String targetVersion) {
        if (config == null || !config.isUsable()) {
            return VpsRelayInfo.of(VpsRelayCheckResult.Status.BAD_CONFIG,
                    "relay is not configured", "", targetVersion, 0, 0);
        }
        try {
            HttpResult version = requestManagement(config, "GET", "/version", "");
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
            if (protocol != APP_PROTOCOL || minAppProtocol > APP_PROTOCOL) {
                status = VpsRelayCheckResult.Status.OUTDATED_VERSION;
                message = "relay protocol is not compatible";
            }
            return VpsRelayInfo.of(status, message, relayVersion, targetVersion,
                    protocol, minAppProtocol);
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
        URL url = new URL(config.baseUrl() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + config.token());
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

    private static String readBody(HttpURLConnection connection, int code) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 400 ? connection.getErrorStream() : connection.getInputStream(),
                "UTF-8"))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static HttpResult requestManagement(VpsRelayConfig config, String method,
                                                String endpoint, String body) throws Exception {
        String prefixed = prefixedManagementPath(config.path(), endpoint);
        HttpResult result = request(config, method, prefixed, body);
        if (!prefixed.equals(endpoint) && (result.code == 404 || result.code == 405)) {
            return request(config, method, endpoint, body);
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

    private static String testRoutesBody(Map<Integer, String> dcRules) {
        StringBuilder out = new StringBuilder();
        out.append("{\"dcs\":[");
        boolean first = true;
        if (dcRules != null) {
            for (Map.Entry<Integer, String> entry : dcRules.entrySet()) {
                if (entry.getKey() == null || entry.getKey() <= 0) continue;
                if (!first) out.append(',');
                first = false;
                out.append("{\"dc\":").append(entry.getKey()).append(",\"ip\":\"")
                        .append(entry.getValue() == null ? "" : entry.getValue())
                        .append("\"}");
            }
        }
        out.append("]}");
        return out.toString();
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

    private static final class HttpResult {
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
