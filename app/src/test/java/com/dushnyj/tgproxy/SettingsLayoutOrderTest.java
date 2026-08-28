package com.dushnyj.tgproxy;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;

public class SettingsLayoutOrderTest {
    @Test
    public void settingsBlocksFollowRequestedGroupingOrder() throws Exception {
        assertEquals(Arrays.asList(
                "@+id/section_profiles",
                "@+id/section_profile_transfer",
                "@+id/section_route",
                "@+id/section_vps_relay",
                "@+id/section_connection",
                "@+id/section_optimization",
                "@+id/section_interface",
                "@+id/section_behavior",
                "@+id/section_diagnostics_logs",
                "@+id/section_advanced",
                "@+id/section_updates",
                "@+id/section_about"),
                includeIds());
    }

    private static List<String> includeIds() throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(settingsLayout().toFile());
        NodeList includes = document.getElementsByTagName("include");
        ArrayList<String> ids = new ArrayList<>();
        for (int i = 0; i < includes.getLength(); i++) {
            String id = includes.item(i).getAttributes()
                    .getNamedItem("android:id")
                    .getNodeValue();
            if (id.startsWith("@+id/section_")) ids.add(id);
        }
        return ids;
    }

    private static Path settingsLayout() {
        List<Path> candidates = Arrays.asList(
                Paths.get("src/main/res/layout/activity_main.xml"),
                Paths.get("app/src/main/res/layout/activity_main.xml"));
        for (Path path : candidates) {
            if (Files.exists(path)) return path;
        }
        throw new IllegalStateException("activity_main.xml not found");
    }
}
