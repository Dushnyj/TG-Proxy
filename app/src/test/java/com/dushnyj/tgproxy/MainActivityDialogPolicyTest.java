package com.dushnyj.tgproxy;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainActivityDialogPolicyTest {
    @Test
    public void relayShareAndOwnerMenusUseVisibleNotedLists() throws Exception {
        String source = new String(Files.readAllBytes(sourcePath()), StandardCharsets.UTF_8);

        assertTrue(source.contains("showNotedItemsDialog(R.string.relay_share_title, "
                + "R.string.relay_share_note"));
        assertTrue(source.contains("showNotedItemsDialog(R.string.vps_owner_manage, "
                + "R.string.vps_owner_manage_note"));
        assertFalse(source.contains(".setMessage(R.string.relay_share_note)"));
        assertFalse(source.contains(".setMessage(R.string.vps_owner_manage_note)"));
    }

    private static Path sourcePath() {
        Path path = Paths.get("app/src/main/java/com/dushnyj/tgproxy/MainActivity.java");
        if (Files.exists(path)) return path;
        return Paths.get("src/main/java/com/dushnyj/tgproxy/MainActivity.java");
    }
}
