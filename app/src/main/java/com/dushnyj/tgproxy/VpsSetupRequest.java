package com.dushnyj.tgproxy;

final class VpsSetupRequest {
    private final VpsSshCredentials sshCredentials;
    private final String relayName;
    private final String relayHost;
    private final int relayPort;
    private final boolean relayTls;
    private final String relayPath;
    private final String relayToken;
    private final String releaseVersion;
    private final String profileKey;
    private final boolean updateExistingRelay;

    private VpsSetupRequest(Builder builder) {
        this.sshCredentials = new VpsSshCredentials(
                builder.sshHost, builder.sshPort, builder.sshUser, builder.sshPassword);
        this.relayName = valueOr(builder.relayName, "VPS Relay");
        this.relayHost = normalizeHost(valueOr(builder.relayHost, builder.sshHost));
        this.relayPort = builder.relayPort <= 0 || builder.relayPort > 65535 ? 18080 : builder.relayPort;
        this.relayTls = builder.relayTls;
        this.relayPath = normalizePath(valueOr(builder.relayPath, "/apiws"));
        this.relayToken = clean(builder.relayToken);
        this.releaseVersion = clean(builder.releaseVersion);
        this.profileKey = clean(builder.profileKey);
        this.updateExistingRelay = builder.updateExistingRelay;
    }

    static Builder builder() {
        return new Builder();
    }

    boolean isValid() {
        VpsRelayConfig relay = relayConfig();
        return sshCredentials.isValid()
                && relay.isUsable()
                && relayHost.length() <= 253
                && VpsRelayConfig.isValidPath(relayPath)
                && relayToken.length() <= 512
                && releaseVersion.length() <= 64
                && releaseVersion.matches("[0-9]+(?:\\.[0-9]+){2}(?:[-+][0-9A-Za-z.-]+)?");
    }

    VpsSshCredentials sshCredentials() {
        return sshCredentials;
    }

    VpsRelayConfig relayConfig() {
        return VpsRelayConfig.manual(true, relayName, relayHost, relayPort,
                relayTls, relayPath, relayToken, profileKey);
    }

    String relayName() {
        return relayName;
    }

    String relayHost() {
        return relayHost;
    }

    int relayPort() {
        return relayPort;
    }

    boolean relayTls() {
        return relayTls;
    }

    String relayPath() {
        return relayPath;
    }

    String relayToken() {
        return relayToken;
    }

    String releaseVersion() {
        return releaseVersion;
    }

    String profileKey() {
        return profileKey;
    }

    boolean updateExistingRelay() {
        return updateExistingRelay;
    }

    VpsSetupRequest withRelayEndpoint(String host, int port, boolean tls, String path) {
        return builder()
                .sshHost(sshCredentials.host())
                .sshPort(sshCredentials.port())
                .sshUser(sshCredentials.user())
                .sshPassword(sshCredentials.password())
                .relayName(relayName)
                .relayHost(host)
                .relayPort(port)
                .relayTls(tls)
                .relayPath(path)
                .relayToken(relayToken)
                .releaseVersion(releaseVersion)
                .profileKey(profileKey)
                .updateExistingRelay(updateExistingRelay)
                .build();
    }

    String publicUrl() {
        String authority = relayHost.indexOf(':') >= 0 && !relayHost.startsWith("[")
                ? "[" + relayHost + "]" : relayHost;
        return (relayTls ? "https://" : "http://") + authority + ":" + relayPort + relayPath;
    }

    boolean reverseProxyMode() {
        return relayTls && relayPort == 443 && relayHostIsDomain();
    }

    int internalRelayPort() {
        return reverseProxyMode() ? 18080 : relayPort;
    }

    String relayListenAddress() {
        return (reverseProxyMode() ? "127.0.0.1:" : "0.0.0.0:") + internalRelayPort();
    }

    boolean relayHostIsDomain() {
        return relayHost.indexOf(':') < 0
                && relayHost.contains(".")
                && !relayHost.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    static final class Builder {
        private String sshHost = "";
        private int sshPort = 22;
        private String sshUser = "";
        private String sshPassword = "";
        private String relayName = "VPS Relay";
        private String relayHost = "";
        private int relayPort = 18080;
        private boolean relayTls;
        private String relayPath = "/apiws";
        private String relayToken = "";
        private String releaseVersion = "";
        private String profileKey = "";
        private boolean updateExistingRelay;

        Builder sshHost(String value) {
            sshHost = value;
            return this;
        }

        Builder sshPort(int value) {
            sshPort = value;
            return this;
        }

        Builder sshUser(String value) {
            sshUser = value;
            return this;
        }

        Builder sshPassword(String value) {
            sshPassword = value;
            return this;
        }

        Builder relayName(String value) {
            relayName = value;
            return this;
        }

        Builder relayHost(String value) {
            relayHost = value;
            return this;
        }

        Builder relayPort(int value) {
            relayPort = value;
            return this;
        }

        Builder relayTls(boolean value) {
            relayTls = value;
            return this;
        }

        Builder relayPath(String value) {
            relayPath = value;
            return this;
        }

        Builder relayToken(String value) {
            relayToken = value;
            return this;
        }

        Builder releaseVersion(String value) {
            releaseVersion = value;
            return this;
        }

        Builder profileKey(String value) {
            profileKey = value;
            return this;
        }

        Builder updateExistingRelay(boolean value) {
            updateExistingRelay = value;
            return this;
        }

        VpsSetupRequest build() {
            return new VpsSetupRequest(this);
        }
    }

    private static String normalizeHost(String raw) {
        String value = clean(raw).toLowerCase(java.util.Locale.US);
        if (value.startsWith("https://")) value = value.substring("https://".length());
        else if (value.startsWith("http://")) value = value.substring("http://".length());
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        if (value.startsWith("[")) {
            int closing = value.indexOf(']');
            if (closing > 1) return value.substring(1, closing);
        }
        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            String suffix = value.substring(firstColon + 1);
            if (suffix.matches("\\d{1,5}")) value = value.substring(0, firstColon);
        }
        return value;
    }

    private static String normalizePath(String raw) {
        String value = clean(raw);
        if (value.isEmpty()) return "";
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String valueOr(String value, String fallback) {
        String normalized = clean(value);
        return normalized.isEmpty() ? clean(fallback) : normalized;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
