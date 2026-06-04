package com.dushnyj.tgproxy;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertNotNull;

public class DiagnosticsScreenLayoutTest {
    @Test
    public void diagnosticsScreenExposesReportResetAction() throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(layout().toFile());

        assertId(document, "btn_diagnostics_reset");
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
        Path path = Paths.get("app/src/main/res/layout/view_diagnostics_screen.xml");
        if (Files.exists(path)) return path;
        return Paths.get("src/main/res/layout/view_diagnostics_screen.xml");
    }
}
