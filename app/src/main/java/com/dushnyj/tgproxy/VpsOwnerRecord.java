package com.dushnyj.tgproxy;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Owner-only SSH/admin material. It is never included in SettingsTransfer exports. */
final class VpsOwnerRecord {
    private final String id;
    private final String sshHost;
    private final int sshPort;
    private final String sshUser;
    private final String sshPassword;
    private final String adminToken;
    private final String relayHost;
    private final int relayPort;
    private final boolean relayTls;
    private final String relayPath;
    private final long updatedAtMs;
    private final LinkedHashMap<String, ManagedToken> managedTokens;

    VpsOwnerRecord(String id, String sshHost, int sshPort, String sshUser, String sshPassword,
                   String adminToken, String relayHost, int relayPort, boolean relayTls,
                   String relayPath, long updatedAtMs, Map<String, ManagedToken> managedTokens) {
        this.id = clean(id).isEmpty() ? idFor(relayHost, relayPort, relayTls, relayPath) : clean(id);
        this.sshHost = clean(sshHost);
        this.sshPort = validPort(sshPort, 22);
        this.sshUser = clean(sshUser);
        this.sshPassword = sshPassword == null ? "" : sshPassword;
        this.adminToken = clean(adminToken);
        this.relayHost = clean(relayHost).toLowerCase(Locale.US);
        this.relayPort = validPort(relayPort, relayTls ? 443 : 18080);
        this.relayTls = relayTls;
        this.relayPath = normalizePath(relayPath);
        this.updatedAtMs = Math.max(0L, updatedAtMs);
        this.managedTokens = new LinkedHashMap<>();
        if (managedTokens != null) this.managedTokens.putAll(managedTokens);
    }

    static VpsOwnerRecord fromSetup(VpsSetupRequest request, VpsRelayConfig relay) {
        LinkedHashMap<String, ManagedToken> tokens = new LinkedHashMap<>();
        String tokenId = clientTokenId(relay == null ? "" : relay.token());
        if (!tokenId.isEmpty()) {
            tokens.put(tokenId, new ManagedToken(tokenId,
                    relay == null ? "Основной" : relay.name(), relay.token()));
        }
        VpsSshCredentials ssh = request.sshCredentials();
        return new VpsOwnerRecord("", ssh.host(), ssh.port(), ssh.user(),
                request.rememberSshPassword() ? ssh.password() : "",
                request.adminToken(), relay.host(), relay.port(), relay.tls(), relay.path(),
                System.currentTimeMillis(), tokens);
    }

    String id() { return id; }
    String sshHost() { return sshHost; }
    int sshPort() { return sshPort; }
    String sshUser() { return sshUser; }
    String sshPassword() { return sshPassword; }
    String adminToken() { return adminToken; }
    long updatedAtMs() { return updatedAtMs; }

    boolean canManage() {
        return !adminToken.isEmpty() && adminToken.length() <= 512
                && !containsHeaderUnsafe(adminToken);
    }

    boolean matches(VpsRelayConfig relay) {
        return relay != null && relayHost.equals(relay.host()) && relayPort == relay.port()
                && relayTls == relay.tls() && relayPath.equals(relay.path());
    }

    boolean sameSshEndpoint(VpsOwnerRecord other) {
        return other != null && sshHost.equalsIgnoreCase(other.sshHost)
                && sshPort == other.sshPort
                && sshUser.equalsIgnoreCase(other.sshUser);
    }

    boolean matchesSsh(VpsSshCredentials credentials) {
        return credentials != null
                && sshHost.equalsIgnoreCase(credentials.host())
                && sshPort == credentials.port()
                && sshUser.equalsIgnoreCase(credentials.user());
    }

    List<ManagedToken> managedTokens() {
        return Collections.unmodifiableList(new ArrayList<>(managedTokens.values()));
    }

    ManagedToken managedToken(String tokenId) {
        return managedTokens.get(clean(tokenId));
    }

    VpsOwnerRecord withManagedToken(String tokenId, String name, String secret) {
        LinkedHashMap<String, ManagedToken> copy = new LinkedHashMap<>(managedTokens);
        String id = clean(tokenId);
        if (!id.isEmpty() && !clean(secret).isEmpty()) {
            copy.put(id, new ManagedToken(id, name, secret));
        }
        return copy(copy);
    }

    VpsOwnerRecord withoutManagedToken(String tokenId) {
        LinkedHashMap<String, ManagedToken> copy = new LinkedHashMap<>(managedTokens);
        copy.remove(clean(tokenId));
        return copy(copy);
    }

    VpsOwnerRecord mergedWith(VpsOwnerRecord previous) {
        if (previous == null) return this;
        LinkedHashMap<String, ManagedToken> merged = new LinkedHashMap<>(previous.managedTokens);
        merged.putAll(managedTokens);
        return new VpsOwnerRecord(id, sshHost, sshPort, sshUser, sshPassword, adminToken,
                relayHost, relayPort, relayTls, relayPath, updatedAtMs, merged);
    }

    VpsOwnerRecord withMergedManagedTokens(VpsOwnerRecord other) {
        if (other == null) return this;
        LinkedHashMap<String, ManagedToken> merged = new LinkedHashMap<>(managedTokens);
        merged.putAll(other.managedTokens);
        return copy(merged);
    }

    String serialize() {
        return join(id, sshHost, String.valueOf(sshPort), sshUser, sshPassword, adminToken,
                relayHost, String.valueOf(relayPort), relayTls ? "1" : "0", relayPath,
                String.valueOf(updatedAtMs), serializeTokens(managedTokens));
    }

    static VpsOwnerRecord parse(String line) {
        String[] fields = line == null ? new String[0] : line.split("\\t", -1);
        if (fields.length < 12) return null;
        try {
            return new VpsOwnerRecord(decoded(fields[0]), decoded(fields[1]),
                    Integer.parseInt(decoded(fields[2])), decoded(fields[3]), decoded(fields[4]),
                    decoded(fields[5]), decoded(fields[6]), Integer.parseInt(decoded(fields[7])),
                    "1".equals(decoded(fields[8])), decoded(fields[9]),
                    Long.parseLong(decoded(fields[10])), parseTokens(decoded(fields[11])));
        } catch (Exception ignored) {
            return null;
        }
    }

    static String clientTokenId(String rawToken) {
        String token = clean(rawToken);
        if (token.isEmpty()) return "";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("cfg_");
            for (int i = 0; i < 8; i++) out.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
            return out.toString();
        } catch (Exception ignored) {
            return "cfg_" + Integer.toHexString(token.hashCode());
        }
    }

    static String idFor(VpsRelayConfig relay) {
        return relay == null ? "" : idFor(relay.host(), relay.port(), relay.tls(), relay.path());
    }

    private VpsOwnerRecord copy(Map<String, ManagedToken> tokens) {
        return new VpsOwnerRecord(id, sshHost, sshPort, sshUser, sshPassword, adminToken,
                relayHost, relayPort, relayTls, relayPath, System.currentTimeMillis(), tokens);
    }

    private static String idFor(String host, int port, boolean tls, String path) {
        String value = clean(host).toLowerCase(Locale.US) + "\n" + port + "\n" + tls + "\n"
                + normalizePath(path);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("owner_");
            for (int i = 0; i < 12; i++) out.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
            return out.toString();
        } catch (Exception ignored) {
            return "owner_" + Integer.toHexString(value.hashCode());
        }
    }

    private static String serializeTokens(Map<String, ManagedToken> tokens) {
        StringBuilder out = new StringBuilder();
        for (ManagedToken token : tokens.values()) {
            if (out.length() > 0) out.append('|');
            out.append(encoded(token.id())).append(',').append(encoded(token.name()))
                    .append(',').append(encoded(token.secret()));
        }
        return out.toString();
    }

    private static Map<String, ManagedToken> parseTokens(String raw) {
        LinkedHashMap<String, ManagedToken> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String item : raw.split("\\|", -1)) {
            String[] fields = item.split(",", -1);
            if (fields.length != 3) continue;
            ManagedToken token = new ManagedToken(decoded(fields[0]), decoded(fields[1]),
                    decoded(fields[2]));
            if (!token.id().isEmpty() && !token.secret().isEmpty()) result.put(token.id(), token);
        }
        return result;
    }

    private static String join(String... fields) {
        StringBuilder out = new StringBuilder();
        for (String field : fields) {
            if (out.length() > 0) out.append('\t');
            out.append(encoded(field));
        }
        return out.toString();
    }

    private static String encoded(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }

    private static String decoded(String value) {
        try { return URLDecoder.decode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }

    private static int validPort(int value, int fallback) {
        return value > 0 && value <= 65535 ? value : fallback;
    }

    private static String normalizePath(String raw) {
        String value = clean(raw);
        if (value.isEmpty()) return "/apiws";
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean containsHeaderUnsafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x21 || ch > 0x7e) return true;
        }
        return false;
    }

    static final class ManagedToken {
        private final String id;
        private final String name;
        private final String secret;

        ManagedToken(String id, String name, String secret) {
            this.id = clean(id);
            this.name = clean(name);
            this.secret = clean(secret);
        }

        String id() { return id; }
        String name() { return name; }
        String secret() { return secret; }
    }
}
