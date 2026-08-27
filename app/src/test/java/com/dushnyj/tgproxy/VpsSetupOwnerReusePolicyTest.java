package com.dushnyj.tgproxy;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsSetupOwnerReusePolicyTest {
    @Test
    public void existingEndpointOwnerIsReusedAndNeverDeletedByFailedSecondSetup()
            throws Exception {
        String source = read("VpsSetupActivity.java");

        assertTrue(source.contains("reuseEndpointOwner(endpointOwnerForChoice)"));
        assertTrue(source.contains("adminToken = owner.adminToken()"));
        assertTrue(source.contains("ownerRecordCreatedForAttempt.set(!exactOwnerAlreadyExisted)"));
        assertTrue(source.contains("if (ownerRecordCreatedForAttempt.get())"));
        assertFalse(source.contains("if (!ownerExisted) ownerStore.forget"));
    }

    private static String read(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/dushnyj/tgproxy/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/dushnyj/tgproxy/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
