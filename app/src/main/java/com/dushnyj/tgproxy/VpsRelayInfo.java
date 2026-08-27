package com.dushnyj.tgproxy;

final class VpsRelayInfo {
    private final VpsRelayCheckResult.Status status;
    private final String message;
    private final String relayVersion;
    private final String targetVersion;
    private final int protocol;
    private final int minAppProtocol;
    private final boolean updateAvailable;
    private final VpsRelayCapabilities capabilities;

    private VpsRelayInfo(VpsRelayCheckResult.Status status, String message,
                         String relayVersion, String targetVersion,
                         int protocol, int minAppProtocol, boolean updateAvailable,
                         VpsRelayCapabilities capabilities) {
        this.status = status == null ? VpsRelayCheckResult.Status.UNAVAILABLE : status;
        this.message = message == null ? "" : message;
        this.relayVersion = relayVersion == null ? "" : relayVersion;
        this.targetVersion = targetVersion == null ? "" : targetVersion;
        this.protocol = protocol;
        this.minAppProtocol = minAppProtocol;
        this.updateAvailable = updateAvailable;
        this.capabilities = capabilities == null
                ? VpsRelayCapabilities.unknown() : capabilities;
    }

    static VpsRelayInfo of(VpsRelayCheckResult.Status status, String message,
                           String relayVersion, String targetVersion,
                           int protocol, int minAppProtocol) {
        boolean update = status == VpsRelayCheckResult.Status.OK
                && !relayVersion.isEmpty()
                && !targetVersion.isEmpty()
                && GithubReleaseUpdater.isNewerVersion(targetVersion, relayVersion);
        return new VpsRelayInfo(status, message, relayVersion, targetVersion,
                protocol, minAppProtocol, update, VpsRelayCapabilities.unknown());
    }

    static VpsRelayInfo of(VpsRelayCheckResult.Status status, String message,
                           String relayVersion, String targetVersion,
                           int protocol, int minAppProtocol,
                           VpsRelayCapabilities capabilities) {
        boolean update = status == VpsRelayCheckResult.Status.OK
                && !relayVersion.isEmpty()
                && !targetVersion.isEmpty()
                && GithubReleaseUpdater.isNewerVersion(targetVersion, relayVersion);
        return new VpsRelayInfo(status, message, relayVersion, targetVersion,
                protocol, minAppProtocol, update, capabilities);
    }

    VpsRelayCheckResult.Status status() {
        return status;
    }

    String message() {
        return message;
    }

    String relayVersion() {
        return relayVersion;
    }

    String targetVersion() {
        return targetVersion;
    }

    int protocol() {
        return protocol;
    }

    int minAppProtocol() {
        return minAppProtocol;
    }

    boolean updateAvailable() {
        return updateAvailable;
    }

    VpsRelayCapabilities capabilities() {
        return capabilities;
    }
}
