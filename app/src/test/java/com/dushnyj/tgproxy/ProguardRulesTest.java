package com.dushnyj.tgproxy;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class ProguardRulesTest {
    @Test
    public void releaseBuildKeepsJschReflectionClasses() throws Exception {
        String rules = new String(Files.readAllBytes(proguardRulesPath()), "UTF-8");

        assertTrue(rules.contains("-keep class com.jcraft.jsch.** { *; }"));
        assertTrue(rules.contains("-dontwarn com.jcraft.jsch.**"));
    }

    private static Path proguardRulesPath() {
        Path path = Paths.get("app/proguard-rules.pro");
        if (Files.exists(path)) return path;
        return Paths.get("proguard-rules.pro");
    }
}
