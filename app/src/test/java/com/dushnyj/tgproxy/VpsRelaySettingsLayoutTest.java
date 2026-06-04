package com.dushnyj.tgproxy;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertNotNull;

public class VpsRelaySettingsLayoutTest {
    @Test
    public void manualRelayFormContainsAllStageEightFields() throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(layout().toFile());

        assertId(document, "cb_vps_relay_enabled");
        assertId(document, "et_vps_relay_name");
        assertId(document, "et_vps_relay_host");
        assertId(document, "et_vps_relay_port");
        assertId(document, "cb_vps_relay_tls");
        assertId(document, "et_vps_relay_path");
        assertId(document, "et_vps_relay_token");
        assertId(document, "cb_vps_relay_bind_profile");
        assertId(document, "btn_vps_relay_test");
        assertId(document, "sp_vps_relay_saved");
        assertId(document, "btn_vps_relay_new");
        assertId(document, "btn_vps_relay_save");
        assertId(document, "btn_vps_relay_auto_setup");
        assertId(document, "progress_vps_setup");
        assertId(document, "tv_vps_setup_status");
    }

    private static void assertId(Document document, String id) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!nodes.item(i).hasAttributes()) continue;
            if (nodes.item(i).getAttributes().getNamedItem("android:id") == null) continue;
            String value = nodes.item(i).getAttributes().getNamedItem("android:id").getNodeValue();
            if (("@+id/" + id).equals(value)) return;
        }
        assertNotNull(id, null);
    }

    private static Path layout() {
        Path path = Paths.get("app/src/main/res/layout/view_vps_relay.xml");
        if (Files.exists(path)) return path;
        return Paths.get("src/main/res/layout/view_vps_relay.xml");
    }
}

