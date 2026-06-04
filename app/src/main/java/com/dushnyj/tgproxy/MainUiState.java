package com.dushnyj.tgproxy;

final class MainUiState {
    private MainUiState() {}

    static boolean canOpenSettings(boolean proxyRunning) {
        return !proxyRunning;
    }

    static String trafficSummary(String bytesUp, String bytesDown) {
        return "Up " + bytesUp + " | Down " + bytesDown;
    }

    static String emptyTrafficSummary() {
        return trafficSummary("-", "-");
    }

    static String uptimeSummary(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d",
                seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L);
    }
}
