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

    @Test
    public void manifestDeclaresQrWifiAndBackgroundReliabilityCapabilities() throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(manifest().toFile());
        String xml = new String(Files.readAllBytes(manifest()), "UTF-8");

        assertTrue(xml.contains("android.permission.CAMERA"));
        assertTrue(xml.contains("android.permission.NEARBY_WIFI_DEVICES"));
        assertTrue(xml.contains("android.permission.POST_NOTIFICATIONS"));
        assertTrue(xml.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"));
        assertTrue(xml.contains("android.permission.RECEIVE_BOOT_COMPLETED"));
        assertTrue(xml.contains("android.intent.action.MY_PACKAGE_REPLACED"));
        assertTrue(document.getElementsByTagName("receiver").getLength() >= 1);
    }

    @Test
    public void manifestAcceptsRelayLinksFilesTextAndQrImages() throws Exception {
        String xml = new String(Files.readAllBytes(manifest()), "UTF-8");

        assertTrue(xml.contains("android:scheme=\"tgproxy\""));
        assertTrue(xml.contains("android:host=\"import\""));
        assertTrue(xml.contains("android.intent.action.SEND"));
        assertTrue(xml.contains("android:mimeType=\"text/plain\""));
        assertTrue(xml.contains("android:mimeType=\"application/vnd.tgproxy\""));
        assertTrue(xml.contains("android:mimeType=\"application/octet-stream\""));
        assertTrue(xml.contains("android:mimeType=\"image/*\""));
        assertTrue(xml.contains("android:scheme=\"content\""));
    }

    @Test
    public void qrScannerActivityIsExplicitlyLockedToPortrait() throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(manifest().toFile());

        Node scanner = activity(document, ".PortraitCaptureActivity");

        assertTrue("portrait".equals(scanner.getAttributes()
                .getNamedItem("android:screenOrientation").getNodeValue()));
        assertTrue("false".equals(scanner.getAttributes()
                .getNamedItem("android:exported").getNodeValue()));
    }

    private static Node mainActivity(Document document) {
        return activity(document, ".MainActivity");
    }

    private static Node activity(Document document, String activityName) {
        NodeList activities = document.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            Node node = activities.item(i);
            Node name = node.getAttributes().getNamedItem("android:name");
            if (name != null && activityName.equals(name.getNodeValue())) return node;
        }
        throw new IllegalStateException(activityName + " not found");
    }

    private static Path manifest() {
        Path path = Paths.get("app/src/main/AndroidManifest.xml");
        if (Files.exists(path)) return path;
        return Paths.get("src/main/AndroidManifest.xml");
    }
}
