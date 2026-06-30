package com.dushnyj.tgproxy;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProxyServiceSourcePolicyTest {
    @Test
    public void engineStartFailureKeepsForegroundServiceAndRetries() throws Exception {
        String source = new String(Files.readAllBytes(sourcePath()), "UTF-8");

        assertFalse(source.contains("handler.post(this::stopSelf)"));
        assertTrue(source.contains("scheduleEngineStartRetry()"));
    }

    @Test
    public void diagnosticsExposeStartingRetryingAndDeadEngineStates() throws Exception {
        String source = new String(Files.readAllBytes(sourcePath()), "UTF-8");

        assertTrue(source.contains("engineStartInProgress"));
        assertTrue(source.contains("engineStartRetry != null"));
        assertTrue(source.contains("ServiceState.from("));
    }

    @Test
    public void notificationTickerRunsWatchdogBeforeRefreshingNotification() throws Exception {
        String source = new String(Files.readAllBytes(sourcePath()), "UTF-8");

        assertTrue(source.contains("watchdogTick();"));
        assertTrue(source.contains("engineNeedsStart()"));
    }

    @Test
    public void runtimeVpsRelayConfigComesOnlyFromRelayStore() throws Exception {
        String source = new String(Files.readAllBytes(sourcePath()), "UTF-8");

        assertTrue(source.contains("VpsRelayStore.fromPreferences(prefs).selectedRelay(profileKey)"));
        assertFalse(source.contains("prefs.getBoolean(\"vps_relay_enabled\""));
        assertFalse(source.contains("prefs.getString(\"vps_relay_host\""));
        assertFalse(source.contains("prefs.getString(\"vps_relay_token\""));
    }

    @Test
    public void proxyServiceExposesDiagnosticsStateReset() throws Exception {
        String source = new String(Files.readAllBytes(sourcePath()), "UTF-8");

        assertTrue(source.contains("void resetDiagnosticsState()"));
        assertTrue(source.contains(".resetDiagnosticsState()"));
    }

    private static Path sourcePath() {
        Path path = Paths.get("app/src/main/java/com/dushnyj/tgproxy/ProxyService.java");
        if (Files.exists(path)) return path;
        return Paths.get("src/main/java/com/dushnyj/tgproxy/ProxyService.java");
    }
}
