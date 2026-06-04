package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VpsSetupPlan {
    enum InstallMode {
        STANDALONE,
        NGINX_NEW_SERVER,
        NGINX_EXISTING_LOCATION,
        CADDY_EXISTING_SITE,
        DOCKER_CADDY_EXISTING_SITE,
        EXISTING_RELAY_ADD_TOKEN,
        EXISTING_RELAY_UPDATE
    }

    private final boolean canApply;
    private final List<String> lines;
    private final InstallMode installMode;
    private final String targetPath;
    private final String targetContainer;

    private VpsSetupPlan(boolean canApply, List<String> lines,
                         InstallMode installMode, String targetPath, String targetContainer) {
        this.canApply = canApply;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.installMode = installMode;
        this.targetPath = targetPath == null ? "" : targetPath;
        this.targetContainer = targetContainer == null ? "" : targetContainer;
    }

    static VpsSetupPlan from(VpsSetupRequest request, VpsSetupAudit audit) {
        ArrayList<String> lines = new ArrayList<>();
        boolean canApply = true;
        Decision decision = Decision.empty();
        if (request == null || !request.isValid()) {
            lines.add("Нужно заполнить SSH и Relay параметры.");
            canApply = false;
        }
        if (audit == null) audit = VpsSetupAudit.parse("");

        String os = audit.os().isEmpty() ? "unknown" : audit.os();
        String arch = audit.architecture().isEmpty() ? "unknown" : audit.architecture();
        lines.add("Read-only audit: os=" + os + ", arch=" + arch
                + ", systemd=" + yesNo(audit.hasSystemd()) + ".");
        lines.add("Web stack: nginx=" + valueOrUnknown(audit.value("nginx"))
                + ", apache=" + valueOrUnknown(audit.value("apache"))
                + ", caddy=" + valueOrUnknown(audit.value("caddy"))
                + ", docker=" + valueOrUnknown(audit.value("docker")) + ".");

        if (!audit.hasSystemd()) {
            lines.add("systemd не найден, автоматическая установка остановлена.");
            canApply = false;
        }
        if (!audit.isSupportedArch()) {
            lines.add("Архитектура " + arch + " не поддерживается release binary.");
            canApply = false;
        }

        if (request != null && request.isValid()) {
            if (isYes(audit.value("existing_relay"))) {
                decision = planExistingRelay(request, audit, lines);
                canApply = decision.canApply && canApply;
            } else {
                if (!isYes(audit.value("curl")) && !isYes(audit.value("wget"))) {
                    lines.add("На VPS нужен curl или wget для загрузки tgproxy-relay release asset.");
                    canApply = false;
                }
                if (isNo(audit.value("tar"))) {
                    lines.add("На VPS нужен tar для распаковки tgproxy-relay release asset.");
                    canApply = false;
                }

                if (request.reverseProxyMode()) {
                    decision = planReverseProxy(request, audit, lines);
                } else {
                    decision = planStandalone(request, audit, lines);
                }
                canApply = decision.canApply && canApply;
            }
        }

        lines.add("Создать backup /etc/tgproxy-relay, systemd unit и TG Proxy web-конфигов перед изменениями.");
        if (decision.installMode == InstallMode.EXISTING_RELAY_ADD_TOKEN) {
            lines.add("Не переустанавливать Relay: добавить новый token в существующий config и перезапустить service.");
        } else if (decision.installMode == InstallMode.EXISTING_RELAY_UPDATE) {
            lines.add("Обновить tgproxy-relay binary из GitHub Release, сохранить существующий config и перезапустить service.");
        } else {
            lines.add("Установить tgproxy-relay из GitHub Release в /opt/tgproxy-relay.");
            lines.add("Записать /etc/tgproxy-relay/config.json и systemd unit.");
        }
        lines.add("Проверить /healthz, /version и /test-routes, затем сохранить Relay.");
        return new VpsSetupPlan(canApply, lines, decision.installMode,
                decision.targetPath, decision.targetContainer);
    }

    boolean canApply() {
        return canApply;
    }

    List<String> lines() {
        return lines;
    }

    InstallMode installMode() {
        return installMode;
    }

    String targetPath() {
        return targetPath;
    }

    String targetContainer() {
        return targetContainer;
    }

    String summary() {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    private static Decision planExistingRelay(VpsSetupRequest request, VpsSetupAudit audit,
                                              ArrayList<String> lines) {
        boolean canApply = true;
        String config = audit.value("existing_relay_config");
        String publicUrl = audit.value("existing_relay_public_url");
        String listen = audit.value("existing_relay_listen");
        lines.add("Relay уже установлен"
                + (config.isEmpty() ? "" : ": " + config)
                + (publicUrl.isEmpty() ? "" : ", publicUrl=" + publicUrl)
                + (listen.isEmpty() ? "" : ", listen=" + listen)
                + ".");
        if (!isYes(audit.value("python3"))) {
            lines.add("Для безопасного изменения JSON config нужен python3.");
            canApply = false;
        }
        if (config.isEmpty()) {
            lines.add("Не найден путь к существующему config.json Relay.");
            canApply = false;
        }
        if (!publicUrl.isEmpty() && !samePublicEndpoint(publicUrl, request.publicUrl())) {
            lines.add("Существующий Relay опубликован как " + publicUrl
                    + ", новый профиль будет использовать " + request.publicUrl() + ".");
        }
        if (request.updateExistingRelay()) {
            if (!isYes(audit.value("curl")) && !isYes(audit.value("wget"))) {
                lines.add("Для обновления tgproxy-relay нужен curl или wget для загрузки release asset.");
                canApply = false;
            }
            if (isNo(audit.value("tar"))) {
                lines.add("Для обновления tgproxy-relay нужен tar для распаковки release asset.");
                canApply = false;
            }
            lines.add("Режим установки: обновить tgproxy-relay до " + request.releaseVersion()
                    + ", сохранить существующий config и добавить новый token при необходимости.");
            return new Decision(canApply, InstallMode.EXISTING_RELAY_UPDATE, config);
        }
        lines.add("Режим установки: Relay уже установлен, можно добавить новый token без полной автонастройки.");
        return new Decision(canApply, InstallMode.EXISTING_RELAY_ADD_TOKEN, config);
    }

    private static Decision planStandalone(VpsSetupRequest request, VpsSetupAudit audit,
                                           ArrayList<String> lines) {
        boolean canApply = true;
        int relayPort = request.relayPort();
        lines.add("Ports: " + relayPort + "=" + valueOrUnknown(audit.value("port_" + relayPort)) + ".");
        if (relayPort == 443) {
            lines.add("Standalone на 443 отключен: 443 обычно принадлежит существующему сайту/TLS.");
            canApply = false;
        }
        if (relayPort > 0 && audit.isPortBusy(relayPort)) {
            lines.add("Порт " + relayPort + " занят, автонастройка не будет менять существующие сайты.");
            canApply = false;
        }
        if (audit.hasWebServer()) {
            lines.add("Обнаружен nginx/apache/caddy: standalone Relay не будет менять домены и web-конфиги.");
        }
        lines.add("Режим установки: standalone HTTP Relay на " + request.relayListenAddress() + ".");
        return new Decision(canApply, InstallMode.STANDALONE, "");
    }

    private static Decision planReverseProxy(VpsSetupRequest request, VpsSetupAudit audit,
                                             ArrayList<String> lines) {
        boolean canApply = true;
        InstallMode mode = InstallMode.NGINX_NEW_SERVER;
        String targetPath = "";
        String domain = valueOr(request.relayHost(), audit.value("domain"));
        String ips = audit.value("domain_ips");
        lines.add("Ports: 443=" + valueOrUnknown(audit.value("port_443"))
                + ", internal " + request.internalRelayPort() + "="
                + valueOrUnknown(audit.value("port_" + request.internalRelayPort())) + ".");
        lines.add("DNS: " + valueOrDash(domain) + " -> " + valueOrDash(ips)
                + " (VPS public IP: " + valueOrDash(audit.value("public_ip")) + ").");
        if (audit.hasWebServer()) {
            lines.add("Автонастройка не будет менять существующие сайты и домены автоматически.");
        }

        if (!request.relayHostIsDomain()) {
            lines.add("Для TLS/reverse proxy нужен домен или поддомен, IP-only TLS здесь не включается.");
            canApply = false;
        }
        if (!audit.domainPointsToVps()) {
            lines.add("DNS домена не указывает на этот VPS; исправьте A/AAAA-запись перед установкой.");
            canApply = false;
        }
        if (audit.isPortBusy(request.internalRelayPort())) {
            lines.add("Внутренний порт " + request.internalRelayPort()
                    + " занят; reverse proxy не сможет проксировать Relay безопасно.");
            canApply = false;
        }

        Decision dockerCaddy = planDockerCaddy(request, audit, lines, canApply);
        if (dockerCaddy != null) {
            return dockerCaddy;
        }

        Decision hostCaddy = planHostCaddy(request, audit, lines, canApply);
        if (hostCaddy != null) {
            return hostCaddy;
        }

        if (!isYes(audit.value("nginx"))) {
            lines.add("nginx не найден: автоматический TLS domain setup сейчас поддерживает nginx, host Caddy или Docker Caddy.");
            canApply = false;
        }
        if (isYes(audit.value("apache")) || isYes(audit.value("caddy"))) {
            lines.add("Обнаружен apache/caddy рядом с nginx: автоматическое изменение web stack остановлено.");
            canApply = false;
        }
        if (!audit.certificateExists()) {
            lines.add("Сертификат Let's Encrypt для " + valueOrDash(domain)
                    + " не найден; автоматическая установка не будет выпускать/менять сертификаты.");
            canApply = false;
        }

        int nginxMatches = audit.intValue("nginx_domain_match_count");
        if (nginxMatches > 0) {
            targetPath = firstCsvValue(audit.value("nginx_domain_matches"));
            if (nginxMatches == 1 && isYes(audit.value("nginx_safe_embed"))
                    && !isYes(audit.value("nginx_path_exists"))) {
                mode = InstallMode.NGINX_EXISTING_LOCATION;
                lines.add("nginx: домен найден в одном простом server block, можно встроить location "
                        + request.relayPath() + " в " + valueOrDash(targetPath) + ".");
            } else {
                lines.add("Домен уже найден в nginx (" + nginxMatches
                        + "): существующий server block/path не изменяется автоматически.");
                if (isYes(audit.value("nginx_path_exists"))) {
                    lines.add("Path " + request.relayPath()
                            + " уже найден в nginx; автонастройка не будет перезаписывать его.");
                }
                canApply = false;
            }
        } else {
            lines.add("nginx: домен не найден в существующих конфигах, можно создать отдельный nginx server block.");
        }
        if (!audit.value("nginx_domain_matches").isEmpty()) {
            lines.add("nginx matches: " + audit.value("nginx_domain_matches"));
        }
        lines.add("Режим установки: TLS domain " + valueOrDash(domain)
                + " -> nginx:443 -> 127.0.0.1:" + request.internalRelayPort()
                + " path " + request.relayPath() + ".");
        return new Decision(canApply, mode, targetPath);
    }

    private static Decision planHostCaddy(VpsSetupRequest request, VpsSetupAudit audit,
                                          ArrayList<String> lines, boolean baseCanApply) {
        int matches = audit.intValue("caddy_domain_match_count");
        if (matches <= 0) return null;

        boolean canApply = baseCanApply;
        String targetPath = firstCsvValue(audit.value("caddy_domain_matches"));
        if (matches == 1 && isYes(audit.value("caddy_safe_embed"))
                && !isYes(audit.value("caddy_path_exists"))) {
            lines.add("Caddy: домен найден в одном Caddyfile, можно встроить isolated handle "
                    + request.relayPath() + " в " + valueOrDash(targetPath) + ".");
        } else {
            lines.add("Домен найден в Caddy (" + matches
                    + "), но автоматическое встраивание остановлено.");
            if (isYes(audit.value("caddy_path_exists"))) {
                lines.add("Path " + request.relayPath()
                        + " уже найден в Caddy; автонастройка не будет перезаписывать его.");
            }
            canApply = false;
        }
        if (!isYes(audit.value("caddy"))) {
            lines.add("caddy не найден, host Caddy setup невозможен.");
            canApply = false;
        }
        if (!isYes(audit.value("python3"))) {
            lines.add("Для безопасного изменения Caddyfile нужен python3.");
            canApply = false;
        }
        if (targetPath.isEmpty()) {
            lines.add("Не найден Caddyfile для выбранного домена.");
            canApply = false;
        }
        if (!isYes(audit.value("caddy_validate"))) {
            lines.add("Текущий Caddy config не проходит validate; автонастройка остановлена.");
            canApply = false;
        }
        lines.add("Режим установки: TLS domain " + valueOrDash(request.relayHost())
                + " -> Caddy:443 -> 127.0.0.1:" + request.internalRelayPort()
                + " path " + request.relayPath() + ".");
        return new Decision(canApply, InstallMode.CADDY_EXISTING_SITE, targetPath);
    }

    private static Decision planDockerCaddy(VpsSetupRequest request, VpsSetupAudit audit,
                                            ArrayList<String> lines, boolean baseCanApply) {
        int matches = audit.intValue("docker_caddy_domain_match_count");
        if (matches <= 0) return null;

        boolean canApply = baseCanApply;
        String targetPath = firstCsvValue(audit.value("docker_caddy_domain_matches"));
        String container = audit.value("docker_caddy_container");
        if (matches == 1 && isYes(audit.value("docker_caddy_safe_embed"))
                && !isYes(audit.value("docker_caddy_path_exists"))) {
            lines.add("Docker Caddy: домен найден в контейнерном Caddy stack, можно встроить isolated handle "
                    + request.relayPath() + " в " + valueOrDash(targetPath)
                    + (container.isEmpty() ? "." : " (" + container + ")."));
            lines.add("Relay будет слушать Docker gateway, чтобы Caddy container мог достучаться до backend без открытия публичного 18080.");
        } else {
            lines.add("Домен найден в Docker Caddy (" + matches
                    + "), но автоматическое встраивание остановлено.");
            if (isYes(audit.value("docker_caddy_path_exists"))) {
                lines.add("Path " + request.relayPath()
                        + " уже найден в Docker Caddy; автонастройка не будет перезаписывать его.");
            }
            canApply = false;
        }
        if (!isYes(audit.value("docker"))) {
            lines.add("docker не найден, Docker Caddy setup невозможен.");
            canApply = false;
        }
        if (!isYes(audit.value("python3"))) {
            lines.add("Для безопасного изменения Caddyfile нужен python3.");
            canApply = false;
        }
        if (container.isEmpty()) {
            lines.add("Не найден running Caddy container для проверки и reload.");
            canApply = false;
        }
        if (targetPath.isEmpty()) {
            lines.add("Не найден host Caddyfile для Docker Caddy.");
            canApply = false;
        }
        if (!isYes(audit.value("docker_caddy_validate"))) {
            lines.add("Текущий Docker Caddy config не проходит validate; автонастройка остановлена.");
            canApply = false;
        }
        lines.add("Режим установки: TLS domain " + valueOrDash(request.relayHost())
                + " -> Docker Caddy:443 -> Docker gateway:" + request.internalRelayPort()
                + " path " + request.relayPath() + ".");
        return new Decision(canApply, InstallMode.DOCKER_CADDY_EXISTING_SITE,
                targetPath, container);
    }

    private static boolean isYes(String value) {
        return "yes".equalsIgnoreCase(value)
                || "true".equalsIgnoreCase(value)
                || "1".equals(value);
    }

    private static boolean isNo(String value) {
        return "no".equalsIgnoreCase(value)
                || "false".equalsIgnoreCase(value)
                || "0".equals(value);
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String valueOrUnknown(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private static String valueOrDash(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? "-" : normalized;
    }

    private static String valueOr(String primary, String fallback) {
        String normalized = primary == null ? "" : primary.trim();
        return normalized.isEmpty() ? valueOrDash(fallback) : normalized;
    }

    private static String firstCsvValue(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return "";
        int comma = normalized.indexOf(',');
        return (comma < 0 ? normalized : normalized.substring(0, comma)).trim();
    }

    private static boolean samePublicEndpoint(String left, String right) {
        return normalizeEndpoint(left).equals(normalizeEndpoint(right));
    }

    private static String normalizeEndpoint(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static final class Decision {
        final boolean canApply;
        final InstallMode installMode;
        final String targetPath;
        final String targetContainer;

        Decision(boolean canApply, InstallMode installMode, String targetPath) {
            this(canApply, installMode, targetPath, "");
        }

        Decision(boolean canApply, InstallMode installMode,
                 String targetPath, String targetContainer) {
            this.canApply = canApply;
            this.installMode = installMode;
            this.targetPath = targetPath == null ? "" : targetPath;
            this.targetContainer = targetContainer == null ? "" : targetContainer;
        }

        static Decision empty() {
            return new Decision(false, null, "");
        }
    }
}
