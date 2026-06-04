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

    private static Path sourcePath() {
        Path path = Paths.get("app/src/main/java/com/dushnyj/tgproxy/ProxyService.java");
        if (Files.exists(path)) return path;
        return Paths.get("src/main/java/com/dushnyj/tgproxy/ProxyService.java");
    }
}
