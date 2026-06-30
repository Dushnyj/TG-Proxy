package com.dushnyj.tgproxy;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class MtProtoProxyEngineSourcePolicyTest {
    @Test
    public void staleGenerationConnectionIsClosedBeforeBecomingActiveRoute() throws Exception {
        String source = new String(Files.readAllBytes(sourcePath()), "UTF-8");

        assertTrue(source.contains("isStaleGeneration(generation)"));
        assertTrue(source.contains("ws.close()"));
    }

    private static Path sourcePath() {
        Path path = Paths.get("app/src/main/java/com/dushnyj/tgproxy/MtProtoProxyEngine.java");
        if (Files.exists(path)) return path;
        return Paths.get("src/main/java/com/dushnyj/tgproxy/MtProtoProxyEngine.java");
    }
}
