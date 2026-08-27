package com.dushnyj.tgproxy;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class VpsSetupAudit {
    private final LinkedHashMap<String, String> values;

    private VpsSetupAudit(LinkedHashMap<String, String> values) {
        this.values = values;
    }

    static VpsSetupAudit parse(String raw) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (raw != null) {
            for (String line : raw.split("\\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim().toLowerCase(Locale.US);
                String value = line.substring(eq + 1).trim();
                if (!key.isEmpty()) values.put(key, value);
            }
        }
        return new VpsSetupAudit(values);
    }

    boolean hasSystemd() {
        return "systemd".equals(initSystem()) || isYes("systemd");
    }

    boolean isLinux() {
        String kernel = value("kernel");
        return kernel.isEmpty() || "linux".equalsIgnoreCase(kernel);
    }

    String initSystem() {
        String value = value("init_system").toLowerCase(Locale.US);
        if (!value.isEmpty()) return value;
        return isYes("systemd") ? "systemd" : "portable";
    }

    boolean hasSupportedInit() {
        String init = initSystem();
        return "systemd".equals(init)
                || "openrc".equals(init)
                || "runit".equals(init)
                || "sysv".equals(init)
                || "portable".equals(init);
    }

    boolean hasWebServer() {
        return isYes("nginx") || isYes("apache") || isYes("caddy");
    }

    boolean isPortBusy(int port) {
        return "busy".equalsIgnoreCase(value("port_" + port));
    }

    boolean isPortFree(int port) {
        return "free".equalsIgnoreCase(value("port_" + port));
    }

    boolean isSupportedArch() {
        String arch = architecture();
        return "x86_64".equals(arch)
                || "amd64".equals(arch)
                || "i386".equals(arch)
                || "i486".equals(arch)
                || "i586".equals(arch)
                || "i686".equals(arch)
                || "x86".equals(arch)
                || "aarch64".equals(arch)
                || "arm64".equals(arch)
                || arch.startsWith("armv5")
                || arch.startsWith("armv6")
                || arch.startsWith("armv7")
                || arch.startsWith("armv8l")
                || "riscv64".equals(arch)
                || "ppc64".equals(arch)
                || "ppc64le".equals(arch)
                || "s390x".equals(arch)
                || "loong64".equals(arch)
                || "loongarch64".equals(arch)
                || "mips".equals(arch)
                || "mipsel".equals(arch)
                || "mipsle".equals(arch)
                || "mips64".equals(arch)
                || "mips64el".equals(arch)
                || "mips64le".equals(arch);
    }

    String architecture() {
        return value("arch").toLowerCase(Locale.US);
    }

    String os() {
        return value("os");
    }

    String publicIp() {
        return value("public_ip");
    }

    boolean domainPointsToVps() {
        return "yes".equalsIgnoreCase(value("domain_points_to_vps"));
    }

    boolean certificateExists() {
        return isYes("cert_exists");
    }

    int intValue(String key) {
        try {
            return Integer.parseInt(value(key));
        } catch (Exception ignored) {
            return 0;
        }
    }

    String value(String key) {
        String value = values.get(key == null ? "" : key.toLowerCase(Locale.US));
        return value == null ? "" : value;
    }

    Map<String, String> values() {
        return new LinkedHashMap<>(values);
    }

    List<String> discoveredDomains() {
        ArrayList<String> out = new ArrayList<>();
        String raw = value("discovered_domains");
        if (!raw.isEmpty()) {
            for (String part : raw.split(",")) {
                String domain = normalizeDomain(part);
                if (domain.isEmpty() || out.contains(domain)) continue;
                out.add(domain);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private boolean isYes(String key) {
        String value = value(key);
        return "yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static String normalizeDomain(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.startsWith("*.")) value = value.substring(2);
        if (!value.contains(".")) return "";
        if (!value.matches("[a-z0-9][a-z0-9.-]*[a-z0-9]")) return "";
        if (value.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return "";
        return value;
    }
}
