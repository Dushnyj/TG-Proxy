package com.dushnyj.tgproxy;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class SettingsTransfer {
    static final int MAX_IMPORT_CHARS = 1_048_576;
    private static final String PLAIN_HEADER = "TGPROXY-SETTINGS-v1";
    private static final String ENC_HEADER = "TGPROXY-ENC-v1";
    private static final String DEEPLINK_PREFIX = "tgproxy://import?data=";
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    enum Kind {
        SAFE_PROFILE("safe_profile"),
        VPS_RELAY("vps_relay"),
        FULL_PROFILE("full_profile");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        static Kind fromWire(String raw) throws SettingsTransferException {
            for (Kind kind : values()) {
                if (kind.wireName.equals(raw)) return kind;
            }
            throw new SettingsTransferException("unsupported transfer kind");
        }
    }

    private SettingsTransfer() {}

    static String exportSafeProfile(Data data) {
        Data clean = data == null ? Data.builder().build() : data;
        LinkedHashMap<String, String> fields = baseFields(Kind.SAFE_PROFILE, clean);
        return serialize(fields);
    }

    static String exportVpsRelay(VpsRelayConfig relay) {
        Data data = Data.builder().relayConfig(relay).build();
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("kind", Kind.VPS_RELAY.wireName);
        putRelay(fields, data.relayConfig(), true);
        return serialize(fields);
    }

    static String exportEncrypted(Data data, String password) throws SettingsTransferException {
        Data clean = data == null ? Data.builder().build() : data;
        LinkedHashMap<String, String> fields = baseFields(Kind.FULL_PROFILE, clean);
        fields.put("mtprotoSecret", clean.mtProtoSecret());
        putRelay(fields, clean.relayConfig(), true);
        return encrypt(fields, password);
    }

    static String exportEncryptedVpsRelay(VpsRelayConfig relay, String password)
            throws SettingsTransferException {
        if (relay == null || !relay.isUsable()) {
            throw new SettingsTransferException("relay is not configured");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("kind", Kind.VPS_RELAY.wireName);
        putRelay(fields, relay, true);
        return encrypt(fields, password);
    }

    private static String encrypt(LinkedHashMap<String, String> fields, String password)
            throws SettingsTransferException {
        if (password == null || password.trim().isEmpty()) {
            throw new SettingsTransferException("password is required");
        }
        String plain = serialize(fields);
        try {
            byte[] salt = randomBytes(16);
            byte[] iv = randomBytes(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes("UTF-8"));
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            out.put("salt", hex(salt));
            out.put("iv", hex(iv));
            out.put("data", hex(encrypted));
            return serializeWithHeader(ENC_HEADER, out);
        } catch (Exception e) {
            throw new SettingsTransferException("could not encrypt profile", e);
        }
    }

    static Imported parse(String raw, String password) throws SettingsTransferException {
        requireAcceptableSize(raw);
        String normalized = raw == null ? "" : raw.trim();
        String embeddedDeepLink = extractDeepLink(normalized);
        if (!embeddedDeepLink.isEmpty()) return parseDeepLink(embeddedDeepLink, password);
        if (normalized.startsWith(ENC_HEADER)) {
            return parsePlain(decrypt(normalized, password), true);
        }
        return parsePlain(normalized, false);
    }

    private static Imported parsePlain(String normalized, boolean allowFullProfile)
            throws SettingsTransferException {
        requireAcceptableSize(normalized);
        if (!normalized.startsWith(PLAIN_HEADER)) {
            throw new SettingsTransferException("unsupported transfer format");
        }
        LinkedHashMap<String, String> fields = parseFields(normalized, PLAIN_HEADER);
        Kind kind = Kind.fromWire(fields.get("kind"));
        if (kind == Kind.FULL_PROFILE && !allowFullProfile) {
            throw new SettingsTransferException("full profile must be encrypted");
        }
        validateFieldSchema(kind, fields);
        Data data = Data.fromFields(fields, kind);
        validateImportedData(kind, data);
        return new Imported(kind, data);
    }

    private static String extractDeepLink(String raw) {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf(DEEPLINK_PREFIX);
        if (start < 0) return "";
        int end = value.length();
        for (int i = start; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || ch == '<' || ch == '"' || ch == '\'') {
                end = i;
                break;
            }
        }
        return value.substring(start, end);
    }

    static String toDeepLink(String payload) throws SettingsTransferException {
        try {
            return DEEPLINK_PREFIX + URLEncoder.encode(payload == null ? "" : payload, "UTF-8");
        } catch (Exception e) {
            throw new SettingsTransferException("could not encode deeplink", e);
        }
    }

    static Imported parseDeepLink(String raw, String password) throws SettingsTransferException {
        requireAcceptableSize(raw);
        String value = raw == null ? "" : raw.trim();
        if (!value.startsWith(DEEPLINK_PREFIX)) {
            throw new SettingsTransferException("unsupported deeplink");
        }
        try {
            String decoded = URLDecoder.decode(value.substring(DEEPLINK_PREFIX.length()), "UTF-8");
            requireAcceptableSize(decoded);
            return parse(decoded, password);
        } catch (SettingsTransferException e) {
            throw e;
        } catch (Exception e) {
            throw new SettingsTransferException("could not decode deeplink", e);
        }
    }

    private static LinkedHashMap<String, String> baseFields(Kind kind, Data data) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("kind", kind.wireName);
        fields.put("profileName", data.profileName());
        fields.put("routePreference", data.routePreference().name());
        fields.put("customIp", data.customIp());
        fields.put("customPort", String.valueOf(data.customPort()));
        fields.put("dcRules", data.dcRules());
        fields.put("cfMode", data.cfMode());
        fields.put("cfDomains", data.cfDomains());
        fields.put("workerDomains", data.workerDomains());
        return fields;
    }

    private static void putRelay(LinkedHashMap<String, String> fields, VpsRelayConfig relay,
                                 boolean includeToken) {
        VpsRelayConfig clean = relay == null ? VpsRelayConfig.disabled() : relay;
        fields.put("relay.enabled", clean.isEnabled() ? "1" : "0");
        fields.put("relay.name", clean.name());
        fields.put("relay.host", clean.host());
        fields.put("relay.port", String.valueOf(clean.port()));
        fields.put("relay.tls", clean.tls() ? "1" : "0");
        fields.put("relay.path", clean.path());
        if (includeToken) fields.put("relay.token", clean.token());
    }

    private static String decrypt(String encrypted, String password) throws SettingsTransferException {
        if (password == null || password.trim().isEmpty()) {
            throw new SettingsTransferException("password is required");
        }
        try {
            LinkedHashMap<String, String> fields = parseFields(encrypted, ENC_HEADER);
            byte[] salt = unhex(fields.get("salt"));
            byte[] iv = unhex(fields.get("iv"));
            byte[] data = unhex(fields.get("data"));
            if (salt.length != 16 || iv.length != 12 || data.length < 16) {
                throw new IllegalArgumentException("invalid encrypted profile envelope");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(data), "UTF-8");
        } catch (Exception e) {
            throw new SettingsTransferException("wrong password or damaged profile", e);
        }
    }

    private static SecretKey key(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private static String serialize(LinkedHashMap<String, String> fields) {
        return serializeWithHeader(PLAIN_HEADER, fields);
    }

    private static String serializeWithHeader(String header, LinkedHashMap<String, String> fields) {
        StringBuilder out = new StringBuilder(header);
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            out.append('\n').append(entry.getKey()).append('=').append(encode(entry.getValue()));
        }
        return out.toString();
    }

    private static LinkedHashMap<String, String> parseFields(String raw, String header)
            throws SettingsTransferException {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        String[] lines = raw.split("\\n", -1);
        if (lines.length == 0 || !stripCarriageReturn(lines[0]).equals(header)) {
            throw new SettingsTransferException("unsupported transfer format");
        }
        for (int i = 1; i < lines.length; i++) {
            String line = stripCarriageReturn(lines[i]);
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) throw new SettingsTransferException("damaged transfer field");
            String key = line.substring(0, eq);
            if (!key.matches("[A-Za-z0-9._-]{1,64}") || fields.containsKey(key)
                    || fields.size() >= 64) {
                throw new SettingsTransferException("damaged transfer field");
            }
            fields.put(key, decode(line.substring(eq + 1)));
        }
        return fields;
    }

    private static String stripCarriageReturn(String value) {
        if (value != null && value.endsWith("\r")) {
            return value.substring(0, value.length() - 1);
        }
        return value == null ? "" : value;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }

    private static String decode(String value) throws SettingsTransferException {
        try {
            return URLDecoder.decode(value == null ? "" : value, "UTF-8");
        } catch (Exception error) {
            throw new SettingsTransferException("damaged transfer encoding", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        if (bytes != null) {
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }

    private static byte[] unhex(String raw) {
        String value = raw == null ? "" : raw.trim();
        if ((value.length() & 1) != 0 || !value.matches("[0-9a-fA-F]*")) {
            throw new IllegalArgumentException("invalid hex value");
        }
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static void requireAcceptableSize(String raw) throws SettingsTransferException {
        if (raw != null && raw.length() > MAX_IMPORT_CHARS) {
            throw new SettingsTransferException("transfer payload is too large");
        }
    }

    private static void validateImportedData(Kind kind, Data data)
            throws SettingsTransferException {
        VpsRelayConfig relay = data == null ? null : data.relayConfig();
        if (kind == Kind.VPS_RELAY && (relay == null || !relay.isUsable())) {
            throw new SettingsTransferException("invalid VPS Relay profile");
        }
        if (kind == Kind.FULL_PROFILE) {
            String secret = data.mtProtoSecret();
            if (secret == null || !secret.matches("(?i)(?:dd)?[0-9a-f]{32}")) {
                throw new SettingsTransferException("invalid MTProto secret");
            }
            if (relay != null && relay.isEnabled() && !relay.isUsable()) {
                throw new SettingsTransferException("invalid VPS Relay profile");
            }
        }
    }

    private static void validateFieldSchema(Kind kind, Map<String, String> fields)
            throws SettingsTransferException {
        String[] base = {"kind", "profileName", "routePreference", "customIp", "customPort",
                "dcRules", "cfMode", "cfDomains", "workerDomains"};
        String[] relay = {"relay.enabled", "relay.name", "relay.host", "relay.port",
                "relay.tls", "relay.path", "relay.token"};
        for (String key : fields.keySet()) {
            boolean allowed = contains(base, key);
            if (kind == Kind.VPS_RELAY) allowed = "kind".equals(key) || contains(relay, key);
            else if (kind == Kind.FULL_PROFILE) {
                allowed = allowed || "mtprotoSecret".equals(key) || contains(relay, key);
            }
            if (!allowed) {
                throw new SettingsTransferException("field is not allowed for " + kind.wireName);
            }
        }
    }

    private static boolean contains(String[] values, String wanted) {
        if (values == null || wanted == null) return false;
        for (String value : values) if (wanted.equals(value)) return true;
        return false;
    }

    static final class Imported {
        private final Kind kind;
        private final Data data;

        Imported(Kind kind, Data data) {
            this.kind = kind == null ? Kind.SAFE_PROFILE : kind;
            this.data = data == null ? Data.builder().build() : data;
        }

        Kind kind() {
            return kind;
        }

        Data data() {
            return data;
        }
    }

    static final class Data {
        private final String profileName;
        private final RoutePreference routePreference;
        private final String customIp;
        private final int customPort;
        private final String mtProtoSecret;
        private final String dcRules;
        private final String cfMode;
        private final String cfDomains;
        private final String workerDomains;
        private final VpsRelayConfig relayConfig;

        private Data(Builder builder) {
            this.profileName = clean(builder.profileName);
            this.routePreference = builder.routePreference == null ? RoutePreference.AUTO : builder.routePreference;
            this.customIp = valueOr(builder.customIp, MtProtoConfig.DEFAULT_HOST);
            this.customPort = builder.customPort <= 0 || builder.customPort > 65535
                    ? MtProtoConfig.DEFAULT_PORT : builder.customPort;
            this.mtProtoSecret = clean(builder.mtProtoSecret);
            this.dcRules = clean(builder.dcRules);
            this.cfMode = valueOr(builder.cfMode, MtProtoProxyEngine.CF_MODE_AUTO);
            this.cfDomains = clean(builder.cfDomains);
            this.workerDomains = clean(builder.workerDomains);
            this.relayConfig = builder.relayConfig == null ? VpsRelayConfig.disabled() : builder.relayConfig;
        }

        static Builder builder() {
            return new Builder();
        }

        static Data fromFields(Map<String, String> fields, Kind kind) {
            if (fields == null) fields = new LinkedHashMap<>();
            VpsRelayConfig relay = kind == Kind.SAFE_PROFILE
                    ? VpsRelayConfig.disabled()
                    : VpsRelayConfig.manual(
                    "1".equals(fields.get("relay.enabled")),
                    fields.get("relay.name"),
                    fields.get("relay.host"),
                    intValue(fields.get("relay.port"), 443),
                    "1".equals(fields.get("relay.tls")),
                    fields.get("relay.path"),
                    fields.get("relay.token"),
                    "");
            return builder()
                    .profileName(fields.get("profileName"))
                    .routePreference(routePreference(fields.get("routePreference")))
                    .customIp(fields.get("customIp"))
                    .customPort(intValue(fields.get("customPort"), MtProtoConfig.DEFAULT_PORT))
                    // SAFE_PROFILE is plaintext and must not be able to smuggle a secret by
                    // appending an otherwise well-formed extra field.
                    .mtProtoSecret(kind == Kind.FULL_PROFILE
                            ? fields.get("mtprotoSecret") : "")
                    .dcRules(fields.get("dcRules"))
                    .cfMode(fields.get("cfMode"))
                    .cfDomains(fields.get("cfDomains"))
                    .workerDomains(fields.get("workerDomains"))
                    .relayConfig(relay)
                    .build();
        }

        String profileName() {
            return profileName;
        }

        RoutePreference routePreference() {
            return routePreference;
        }

        String customIp() {
            return customIp;
        }

        int customPort() {
            return customPort;
        }

        String mtProtoSecret() {
            return mtProtoSecret;
        }

        String dcRules() {
            return dcRules;
        }

        String cfMode() {
            return cfMode;
        }

        String cfDomains() {
            return cfDomains;
        }

        String workerDomains() {
            return workerDomains;
        }

        VpsRelayConfig relayConfig() {
            return relayConfig;
        }

        static final class Builder {
            private String profileName = "";
            private RoutePreference routePreference = RoutePreference.AUTO;
            private String customIp = MtProtoConfig.DEFAULT_HOST;
            private int customPort = MtProtoConfig.DEFAULT_PORT;
            private String mtProtoSecret = "";
            private String dcRules = "";
            private String cfMode = MtProtoProxyEngine.CF_MODE_AUTO;
            private String cfDomains = "";
            private String workerDomains = "";
            private VpsRelayConfig relayConfig = VpsRelayConfig.disabled();

            Builder profileName(String value) {
                profileName = value;
                return this;
            }

            Builder routePreference(RoutePreference value) {
                routePreference = value;
                return this;
            }

            Builder customIp(String value) {
                customIp = value;
                return this;
            }

            Builder customPort(int value) {
                customPort = value;
                return this;
            }

            Builder mtProtoSecret(String value) {
                mtProtoSecret = value;
                return this;
            }

            Builder dcRules(String value) {
                dcRules = value;
                return this;
            }

            Builder cfMode(String value) {
                cfMode = value;
                return this;
            }

            Builder cfDomains(String value) {
                cfDomains = value;
                return this;
            }

            Builder workerDomains(String value) {
                workerDomains = value;
                return this;
            }

            Builder relayConfig(VpsRelayConfig value) {
                relayConfig = value;
                return this;
            }

            Data build() {
                return new Data(this);
            }
        }

        private static RoutePreference routePreference(String value) {
            try {
                return RoutePreference.valueOf(value == null ? "" : value);
            } catch (Exception ignored) {
                return RoutePreference.AUTO;
            }
        }
    }

    private static int intValue(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String valueOr(String value, String fallback) {
        String normalized = clean(value);
        return normalized.isEmpty() ? clean(fallback) : normalized;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
