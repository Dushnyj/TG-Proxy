package com.dushnyj.tgproxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VpsOwnerClient {
    Overview load(VpsRelayConfig relay, String adminToken) throws Exception {
        VpsRelayClient.HttpResult result = VpsRelayClient.requestOwner(relay, adminToken,
                "GET", "/admin/v1/overview", "");
        requireSuccess(result, 200);
        JSONObject root = new JSONObject(result.body);
        ArrayList<Token> tokens = new ArrayList<>();
        JSONArray tokenArray = root.optJSONArray("tokens");
        if (tokenArray != null) {
            for (int i = 0; i < tokenArray.length(); i++) {
                JSONObject item = tokenArray.optJSONObject(i);
                if (item == null) continue;
                tokens.add(new Token(item.optString("id"), item.optString("name"),
                        item.optString("createdAt"), item.optInt("activeDevices"),
                        item.optInt("knownDevices")));
            }
        }
        ArrayList<Client> clients = new ArrayList<>();
        JSONArray clientArray = root.optJSONArray("clients");
        if (clientArray != null) {
            for (int i = 0; i < clientArray.length(); i++) {
                JSONObject item = clientArray.optJSONObject(i);
                if (item == null) continue;
                clients.add(new Client(item.optString("tokenId"), item.optString("deviceId"),
                        item.optString("manufacturer"), item.optString("model"),
                        item.optString("appVersion"), item.optString("appCode"),
                        item.optString("android"), item.optString("country"),
                        item.optString("city"), item.optString("remoteIp"),
                        item.optString("firstSeen"), item.optString("lastSeen"),
                        item.optInt("activeSessions"), item.optBoolean("blocked"),
                        item.optString("blockedAt")));
            }
        }
        return new Overview(tokens, clients);
    }

    CreatedToken create(VpsRelayConfig relay, String adminToken, String name) throws Exception {
        JSONObject request = new JSONObject();
        request.put("name", name == null ? "" : name.trim());
        VpsRelayClient.HttpResult result = VpsRelayClient.requestOwner(relay, adminToken,
                "POST", "/admin/v1/tokens", request.toString());
        requireSuccess(result, 201);
        JSONObject root = new JSONObject(result.body);
        JSONObject item = root.getJSONObject("token");
        Token token = new Token(item.optString("id"), item.optString("name"),
                item.optString("createdAt"), item.optInt("activeDevices"),
                item.optInt("knownDevices"));
        String secret = root.optString("secret");
        if (token.id().isEmpty() || secret.isEmpty()) throw new Exception("invalid owner response");
        return new CreatedToken(token, secret);
    }

    void delete(VpsRelayConfig relay, String adminToken, String tokenId) throws Exception {
        String encoded = URLEncoder.encode(tokenId == null ? "" : tokenId, "UTF-8")
                .replace("+", "%20");
        VpsRelayClient.HttpResult result = VpsRelayClient.requestOwner(relay, adminToken,
                "DELETE", "/admin/v1/tokens/" + encoded, "");
        requireSuccess(result, 204);
    }

    void disconnectDevice(VpsRelayConfig relay, String adminToken,
                          String tokenId, String deviceId) throws Exception {
        deviceAction(relay, adminToken, "POST", tokenId, deviceId, "disconnect");
    }

    void blockDevice(VpsRelayConfig relay, String adminToken,
                     String tokenId, String deviceId) throws Exception {
        deviceAction(relay, adminToken, "PUT", tokenId, deviceId, "block");
    }

    void unblockDevice(VpsRelayConfig relay, String adminToken,
                       String tokenId, String deviceId) throws Exception {
        deviceAction(relay, adminToken, "DELETE", tokenId, deviceId, "block");
    }

    private static void deviceAction(VpsRelayConfig relay, String adminToken, String method,
                                     String tokenId, String deviceId, String action) throws Exception {
        String token = pathSegment(tokenId);
        String device = pathSegment(deviceId);
        VpsRelayClient.HttpResult result = VpsRelayClient.requestOwner(relay, adminToken,
                method, "/admin/v1/tokens/" + token + "/devices/" + device + "/" + action, "");
        requireSuccess(result, 204);
    }

    private static String pathSegment(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
    }

    private static void requireSuccess(VpsRelayClient.HttpResult result, int expected)
            throws Exception {
        if (result == null || result.code != expected) {
            int code = result == null ? 0 : result.code;
            if (code == 401 || code == 403) throw new SecurityException("owner access rejected");
            throw new Exception("owner API HTTP " + code);
        }
    }

    static final class Overview {
        private final List<Token> tokens;
        private final List<Client> clients;

        Overview(List<Token> tokens, List<Client> clients) {
            this.tokens = Collections.unmodifiableList(new ArrayList<>(tokens));
            this.clients = Collections.unmodifiableList(new ArrayList<>(clients));
        }

        List<Token> tokens() { return tokens; }
        List<Client> clients() { return clients; }

        List<Client> clientsFor(String tokenId) {
            ArrayList<Client> result = new ArrayList<>();
            for (Client client : clients) if (client.tokenId().equals(tokenId)) result.add(client);
            return result;
        }
    }

    static final class Token {
        private final String id;
        private final String name;
        private final String createdAt;
        private final int activeDevices;
        private final int knownDevices;

        Token(String id, String name, String createdAt, int activeDevices, int knownDevices) {
            this.id = clean(id);
            this.name = clean(name);
            this.createdAt = clean(createdAt);
            this.activeDevices = Math.max(0, activeDevices);
            this.knownDevices = Math.max(0, knownDevices);
        }

        String id() { return id; }
        String name() { return name; }
        String createdAt() { return createdAt; }
        int activeDevices() { return activeDevices; }
        int knownDevices() { return knownDevices; }
    }

    static final class Client {
        private final String tokenId, deviceId, manufacturer, model, appVersion, appCode;
        private final String android, country, city, remoteIp, firstSeen, lastSeen;
        private final int activeSessions;
        private final boolean blocked;
        private final String blockedAt;

        Client(String tokenId, String deviceId, String manufacturer, String model,
               String appVersion, String appCode, String android, String country, String city,
               String remoteIp, String firstSeen, String lastSeen, int activeSessions,
               boolean blocked, String blockedAt) {
            this.tokenId = clean(tokenId); this.deviceId = clean(deviceId);
            this.manufacturer = clean(manufacturer); this.model = clean(model);
            this.appVersion = clean(appVersion); this.appCode = clean(appCode);
            this.android = clean(android); this.country = clean(country); this.city = clean(city);
            this.remoteIp = clean(remoteIp); this.firstSeen = clean(firstSeen);
            this.lastSeen = clean(lastSeen); this.activeSessions = Math.max(0, activeSessions);
            this.blocked = blocked; this.blockedAt = clean(blockedAt);
        }

        String tokenId() { return tokenId; }
        String deviceId() { return deviceId; }
        String manufacturer() { return manufacturer; }
        String model() { return model; }
        String appVersion() { return appVersion; }
        String appCode() { return appCode; }
        String android() { return android; }
        String country() { return country; }
        String city() { return city; }
        String remoteIp() { return remoteIp; }
        String firstSeen() { return firstSeen; }
        String lastSeen() { return lastSeen; }
        int activeSessions() { return activeSessions; }
        boolean blocked() { return blocked; }
        String blockedAt() { return blockedAt; }

        String deviceLabel() {
            String hardware = (manufacturer + " " + model).trim();
            return hardware.isEmpty() ? deviceId : hardware;
        }

        String locationLabel() {
            if (!country.isEmpty() && !city.isEmpty()) return country + ", " + city;
            if (!country.isEmpty()) return country;
            if (!city.isEmpty()) return city;
            return remoteIp;
        }
    }

    static final class CreatedToken {
        private final Token token;
        private final String secret;

        CreatedToken(Token token, String secret) {
            this.token = token;
            this.secret = clean(secret);
        }

        Token token() { return token; }
        String secret() { return secret; }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
