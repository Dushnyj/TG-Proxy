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
    public void relayShareAndOwnerManagementUseResponsiveDedicatedSurfaces() throws Exception {
        String source = read("MainActivity.java");
        String owner = read("VpsOwnerActivity.java");
        String share = read("RelayShareSheet.java");

        assertTrue(source.contains("RelayShareSheet.show(this, relay)"));
        assertTrue(source.contains("VpsOwnerActivity.intent(this, profileKey, relayId)"));
        assertTrue(owner.contains("setContentView(R.layout.activity_vps_owner)"));
        assertTrue(share.contains("new BottomSheetDialog(activity)"));
        assertTrue(share.contains("intent.setType(\"image/png\")"));
        assertTrue(share.contains("Intent.EXTRA_STREAM"));
        assertTrue(share.contains("ClipData.newUri"));
        assertTrue(share.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"));
        assertFalse(source.contains(".setMessage(R.string.relay_share_note)"));
        assertFalse(source.contains(".setMessage(R.string.vps_owner_manage_note)"));
    }

    @Test
    public void backgroundReadinessIsVisibleAndManualRelayStartsCollapsed() throws Exception {
        String source = read("MainActivity.java");

        assertTrue(source.contains("addBackgroundStatusRow(R.string.background_condition_boot"));
        assertTrue(source.contains("R.string.background_condition_network_identity"));
        assertTrue(source.contains("R.string.background_condition_location"));
        assertTrue(source.contains("row.setOnClickListener(view -> missingAction.run())"));
        assertTrue(source.contains(".setView(layout)"));
        assertFalse(source.contains("layout.addView(backgroundSetupBatteryAction"));
        assertTrue(source.contains("setExpandableSection(vpsManualContent, btnVpsManualToggle, false"));
    }

    private static String read(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/dushnyj/tgproxy/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/dushnyj/tgproxy/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
