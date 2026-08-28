package com.dushnyj.tgproxy;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RelayDialogLayoutTest {
    @Test
    public void relayDialogsUseEqualWidthAlignedActionButtons() throws Exception {
        assertEqualButtons("dialog_relay_paste.xml",
                "btn_relay_paste_cancel", "btn_relay_paste_import");
        assertEqualButtons("dialog_relay_preview.xml",
                "btn_relay_preview_cancel", "btn_relay_preview_continue");
        assertEqualButtons("dialog_relay_scope.xml",
                "btn_relay_scope_cancel", "btn_relay_scope_continue");
        assertEqualButtons("dialog_relay_check.xml",
                "btn_relay_check_cancel", "btn_relay_check_skip");
        assertEqualButtons("dialog_relay_result.xml",
                "btn_relay_result_secondary", "btn_relay_result_primary");
        assertEqualButtons("dialog_background_setup.xml",
                "btn_background_not_now", "btn_background_check");
    }

    @Test
    public void checkDialogShowsFiveNamedStagesAndSupportsSkipping() throws Exception {
        String dialog = source("RelayCheckProgressDialog.java");
        String importer = source("RelayImportCoordinator.java");
        String connections = source("VpsRelayConnectionsActivity.java");

        assertTrue(dialog.contains("TOTAL_STEPS = 5"));
        assertTrue(dialog.contains("R.string.relay_check_stage_connection"));
        assertTrue(dialog.contains("R.string.relay_check_stage_authorization"));
        assertTrue(dialog.contains("R.string.relay_check_stage_health"));
        assertTrue(dialog.contains("R.string.relay_check_stage_server_routes"));
        assertTrue(dialog.contains("R.string.relay_check_stage_telegram_routes"));
        assertTrue(dialog.contains("skip.setOnClickListener"));
        assertTrue(importer.contains("saveUnchecked(relay, targetProfile)"));
        assertTrue(connections.contains("saveManual(existing, candidate, null)"));
    }

    @Test
    public void everyQrEntryPointUsesThePortraitScanner() throws Exception {
        for (String name : new String[]{"MainActivity.java", "VpsRelayConnectionsActivity.java"}) {
            String source = source(name);
            assertTrue(name, source.contains(
                    ".setCaptureActivity(PortraitCaptureActivity.class)"));
            assertTrue(name, source.contains(".setOrientationLocked(true)"));
        }
    }

    private static void assertEqualButtons(String layoutName, String firstId, String secondId)
            throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(layout(layoutName).toFile());
        Node first = node(document, firstId);
        Node second = node(document, secondId);
        assertEquals("0dp", attribute(first, "android:layout_width"));
        assertEquals("0dp", attribute(second, "android:layout_width"));
        assertEquals("1", attribute(first, "android:layout_weight"));
        assertEquals("1", attribute(second, "android:layout_weight"));
        assertEquals(attribute(first, "android:layout_height"),
                attribute(second, "android:layout_height"));
    }

    private static Node node(Document document, String id) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            Node current = nodes.item(index);
            Node attribute = current.getAttributes() == null ? null
                    : current.getAttributes().getNamedItem("android:id");
            if (attribute != null && ("@+id/" + id).equals(attribute.getNodeValue())) {
                return current;
            }
        }
        throw new IllegalStateException(id + " not found");
    }

    private static String attribute(Node node, String name) {
        Node value = node.getAttributes().getNamedItem(name);
        if (value == null) throw new IllegalStateException(name + " not found");
        return value.getNodeValue();
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/dushnyj/tgproxy/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/dushnyj/tgproxy/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path layout(String name) {
        Path path = Paths.get("app/src/main/res/layout/" + name);
        if (Files.exists(path)) return path;
        return Paths.get("src/main/res/layout/" + name);
    }
}
