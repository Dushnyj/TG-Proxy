package com.dushnyj.tgproxy;

final class VpsRelayInfo {
    private final VpsRelayCheckResult.Status status;
    private final String message;
    private final String relayVersion;
    private final String targetVersion;
    private final int protocol;
    private final int minAppProtocol;
    private final boolean updateAvailable;

    private VpsRelayInfo(VpsRelayCheckResult.Status status, String message,
                         String relayVersion, String targetVersion,
                         int protocol, int minAppProtocol, boolean updateAvailable) {
        this.status = status == null ? VpsRelayCheckResult.Status.UNAVAILABLE : status;
        this.message = message == null ? "" : message;
        this.relayVersion = relayVersion == null ? "" : relayVersion;
        this.targetVersion = targetVersion == null ? "" : targetVersion;
        this.protocol = protocol;
        this.minAppProtocol = minAppProtocol;
        this.updateAvailable = updateAvailable;
    }

    static VpsRelayInfo of(VpsRelayCheckResult.Status status, String message,
                           String relayVersion, String targetVersion,
                           int protocol, int minAppProtocol) {
        boolean update = status == VpsRelayCheckResult.Status.OK
                && !relayVersion.isEmpty()
                && !targetVersion.isEmpty()
                && GithubReleaseUpdater.isNewerVersion(targetVersion, relayVersion);
        return new VpsRelayInfo(status, message, relayVersion, targetVersion,
                protocol, minAppProtocol, update);
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
}
