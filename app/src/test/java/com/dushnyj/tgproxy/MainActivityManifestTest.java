package com.dushnyj.tgproxy;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertTrue;

public class MainActivityManifestTest {
    @Test
    public void mainActivityHandlesOrientationConfigChanges() throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(manifest().toFile());

        String configChanges = mainActivity(document).getAttributes()
                .getNamedItem("android:configChanges")
                .getNodeValue();

        assertTrue(configChanges.contains("orientation"));
        assertTrue(configChanges.contains("screenSize"));
    }

    private static Node mainActivity(Document document) {
        NodeList activities = document.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            Node node = activities.item(i);
            Node name = node.getAttributes().getNamedItem("android:name");
            if (name != null && ".MainActivity".equals(name.getNodeValue())) return node;
        }
        throw new IllegalStateException("MainActivity not found");
    }

    private static Path manifest() {
        Path path = Paths.get("app/src/main/AndroidManifest.xml");
        if (Files.exists(path)) return path;
        return Paths.get("src/main/AndroidManifest.xml");
    }
}
