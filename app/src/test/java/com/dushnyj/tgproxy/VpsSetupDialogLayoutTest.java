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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsSetupDialogLayoutTest {
    @Test
    public void reusableTokenChoiceUsesCustomRowsAndAlignedActions() throws Exception {
        String source = source();
        Document dialog = xml("dialog_vps_token_choice.xml");
        Document item = xml("item_vps_token_choice.xml");

        assertTrue(source.contains("R.layout.dialog_vps_token_choice"));
        assertTrue(source.contains("R.layout.item_vps_token_choice"));
        assertFalse(source.contains(".setItems(labels.toArray"));
        assertEquals("0dp", attribute(node(dialog, "btn_vps_token_choice_cancel"),
                "android:layout_width"));
        assertEquals("0dp", attribute(node(dialog, "btn_vps_token_choice_new"),
                "android:layout_width"));
        assertEquals("1", attribute(node(dialog, "btn_vps_token_choice_cancel"),
                "android:layout_weight"));
        assertEquals("1", attribute(node(dialog, "btn_vps_token_choice_new"),
                "android:layout_weight"));
        assertEquals("68dp", attribute(item.getDocumentElement(), "android:minHeight"));
    }

    @Test
    public void installPageDoesNotRepeatTheToolbarTitleInItsContent() throws Exception {
        String source = source();

        assertTrue(source.contains("addInstallNotice();"));
        assertFalse(source.contains("addHero(R.drawable.ic_server, "
                + "R.string.vps_setup_progress_title"));
    }

    private static String source() throws Exception {
        Path path = Paths.get("app/src/main/java/com/dushnyj/tgproxy/VpsSetupActivity.java");
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/dushnyj/tgproxy/VpsSetupActivity.java");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Document xml(String name) throws Exception {
        Path path = Paths.get("app/src/main/res/layout/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/res/layout/" + name);
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile());
    }

    private static Node node(Document document, String id) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            Node value = nodes.item(index);
            Node attribute = value.getAttributes() == null ? null
                    : value.getAttributes().getNamedItem("android:id");
            if (attribute != null && ("@+id/" + id).equals(attribute.getNodeValue())) {
                return value;
            }
        }
        throw new IllegalStateException(id + " not found");
    }

    private static String attribute(Node node, String name) {
        Node value = node.getAttributes().getNamedItem(name);
        if (value == null) throw new IllegalStateException(name + " not found");
        return value.getNodeValue();
    }
}
