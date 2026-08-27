package com.dushnyj.tgproxy;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsRelaySavePolicyTest {
    @Test
    public void savePersistsImmediatelyAndDoesNotStartLongConnectivityTest() throws Exception {
        String source = new String(Files.readAllBytes(sourcePath()), StandardCharsets.UTF_8);
        int start = source.indexOf("btnVpsRelaySave.setOnClickListener");
        int end = source.indexOf("btnVpsRelayDelete.setOnClickListener", start);

        assertTrue(start >= 0);
        assertTrue(end > start);
        String saveHandler = source.substring(start, end);
        assertTrue(saveHandler.contains("saveCurrentVpsRelayFromForm()"));
        assertFalse(saveHandler.contains("testVpsRelay()"));
        assertFalse(source.contains("vps_relay_test_required"));
    }

    @Test
    public void backSavePreservesNegotiatedCapabilitiesForUnchangedConnection() throws Exception {
        String source = new String(Files.readAllBytes(sourcePath()), StandardCharsets.UTF_8);

        assertTrue(source.contains(
                "preserveSavedVpsRelayCapabilities(currentVpsRelayConfig())"));
        assertTrue(source.contains("saved.sameRelayConnection(relay)"));
    }

    private static Path sourcePath() {
        Path path = Paths.get("app/src/main/java/com/dushnyj/tgproxy/MainActivity.java");
        if (Files.exists(path)) return path;
        return Paths.get("src/main/java/com/dushnyj/tgproxy/MainActivity.java");
    }
}
