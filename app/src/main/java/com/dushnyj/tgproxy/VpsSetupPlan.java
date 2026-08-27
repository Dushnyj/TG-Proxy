package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class VpsSetupPlan {
    enum InstallMode {
        STANDALONE,
        NGINX_MANAGED_TLS,
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
    private final InstallMode routeRepairMode;
    private final String routeTargetPath;
    private final String routeTargetContainer;
    private final VpsSetupRequest routeRepairRequest;
    private final VpsSetupRequest effectiveRequest;

    private VpsSetupPlan(boolean canApply, List<String> lines,
                         InstallMode installMode, String targetPath, String targetContainer) {
        this(canApply, lines, installMode, targetPath, targetContainer, null, "", "", null, null);
    }

    private VpsSetupPlan(boolean canApply, List<String> lines,
                         InstallMode installMode, String targetPath, String targetContainer,
                         InstallMode routeRepairMode, String routeTargetPath,
                         String routeTargetContainer, VpsSetupRequest routeRepairRequest,
                         VpsSetupRequest effectiveRequest) {
        this.canApply = canApply;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.installMode = installMode;
        this.targetPath = targetPath == null ? "" : targetPath;
        this.targetContainer = targetContainer == null ? "" : targetContainer;
        this.routeRepairMode = routeRepairMode;
        this.routeTargetPath = routeTargetPath == null ? "" : routeTargetPath;
        this.routeTargetContainer = routeTargetContainer == null ? "" : routeTargetContainer;
        this.routeRepairRequest = routeRepairRequest;
        this.effectiveRequest = effectiveRequest;
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
                + ", init=" + valueOrUnknown(audit.initSystem())
                + ", packages=" + valueOrUnknown(audit.value("package_manager")) + ".");
        lines.add("Web stack: nginx=" + valueOrUnknown(audit.value("nginx"))
                + ", apache=" + valueOrUnknown(audit.value("apache"))
                + ", caddy=" + valueOrUnknown(audit.value("caddy"))
                + ", docker=" + valueOrUnknown(audit.value("docker")) + ".");

        if (!audit.isLinux()) {
            lines.add("Удалённая система не является Linux; автоматическая установка остановлена.");
            canApply = false;
        }
        if (!audit.hasSupportedInit()) {
            lines.add("Init-система " + valueOrUnknown(audit.initSystem())
                    + " не поддерживается автонастройкой.");
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
                    lines.add("curl/wget не найден: автонастройка установит загрузчик пакетом ОС.");
                }
                if (isNo(audit.value("tar"))) {
                    lines.add("tar не найден: автонастройка установит его пакетом ОС.");
                }

                if (request.reverseProxyMode()) {
                    decision = planReverseProxy(request, audit, lines);
                } else {
                    decision = planStandalone(request, audit, lines);
                }
                canApply = decision.canApply && canApply;
            }
        }

        lines.add("Создать backup Relay, службы автозапуска, firewall и TG Proxy web-конфигов перед изменениями.");
        if (decision.installMode == InstallMode.EXISTING_RELAY_ADD_TOKEN) {
            lines.add("Не переустанавливать Relay: добавить новый token в существующий config и перезапустить service.");
        } else if (decision.installMode == InstallMode.EXISTING_RELAY_UPDATE) {
            lines.add("Обновить tgproxy-relay binary из GitHub Release, сохранить существующий config и перезапустить service.");
        } else {
            lines.add("Установить tgproxy-relay из GitHub Release в /opt/tgproxy-relay.");
            lines.add("Записать /etc/tgproxy-relay/config.json и службу автозапуска для "
                    + valueOrUnknown(audit.initSystem()) + ".");
        }
        lines.add("Проверить /healthz, /version и /test-routes, затем сохранить Relay.");
        return new VpsSetupPlan(canApply, lines, decision.installMode,
                decision.targetPath, decision.targetContainer, decision.routeRepairMode,
                decision.routeTargetPath, decision.routeTargetContainer,
                decision.routeRepairRequest, decision.effectiveRequest);
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

    InstallMode routeRepairMode() {
        return routeRepairMode;
    }

    String routeTargetPath() {
        return routeTargetPath;
    }

    String routeTargetContainer() {
        return routeTargetContainer;
    }

    VpsSetupRequest routeRepairRequest() {
        return routeRepairRequest;
    }

    VpsSetupRequest effectiveRequest() {
        return effectiveRequest;
    }

    boolean hasRouteRepair() {
        return routeRepairMode != null;
    }

    String summary() {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    String blockingSummary() {
        if (canApply) return "";
        List<String> blockers = userBlockers();
        return blockers.isEmpty() ? "Автонастройку пока нельзя продолжить."
                : joinLines(blockers);
    }

    List<String> userBlockers() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String line : lines) {
            if (!isBlockingLine(line)) continue;
            result.add(friendlyBlocker(line));
        }
        if (!canApply && result.isEmpty()) {
            result.add("Автонастройку пока нельзя продолжить. Вернитесь назад и проверьте выбранный адрес сервера.");
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    List<String> userWarnings() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String line : lines) {
            String lower = lower(line);
            if (lower.contains("существующий relay опубликован как")) {
                result.add("На VPS уже работает Relay с другим публичным адресом. Приложение не перезапишет рабочее подключение и применит изменения только после безопасной проверки нового адреса.");
            } else if (lower.contains("curl/wget не найден") || lower.contains("tar не найден")) {
                result.add("Некоторые системные утилиты отсутствуют. Приложение установит их автоматически штатным пакетным менеджером сервера.");
            } else if (lower.contains("найден существующий web stack")
                    || lower.contains("обнаружен nginx/apache/caddy")) {
                result.add("На VPS уже есть сайты. Автонастройка не будет их перезаписывать и добавит только отдельный маршрут TG Proxy.");
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    List<String> userActions() {
        ArrayList<String> result = new ArrayList<>();
        if (installMode == InstallMode.EXISTING_RELAY_ADD_TOKEN) {
            result.add("Сохранит установленный Relay и добавит выбранный токен без переустановки сервера.");
        } else if (installMode == InstallMode.EXISTING_RELAY_UPDATE) {
            result.add("Создаст резервную копию, обновит Relay и сохранит действующие токены.");
        } else if (installMode == InstallMode.STANDALONE) {
            result.add("Установит Relay как отдельную службу и включит автоматический запуск после перезагрузки.");
        } else if (installMode != null) {
            result.add("Установит Relay и добавит отдельный HTTPS-маршрут, не изменяя содержимое существующих сайтов.");
        }
        if (hasRouteRepair()) {
            result.add("Восстановит отсутствующий публичный маршрут Relay в найденной конфигурации веб-сервера.");
        }
        result.add("Перед изменениями создаст резервную копию и автоматически откатит её при ошибке.");
        result.add("Проверит HTTPS, токен, основные и медиамаршруты рабочих Telegram DC.");
        return Collections.unmodifiableList(result);
    }

    String technicalSummary() {
        return summary();
    }

    private static boolean isBlockingLine(String line) {
        String lower = lower(line);
        if (lower.contains("автонастройка установит")
                || lower.contains("установит загрузчик пакетом ос")) return false;
        return lower.startsWith("нужно ")
                || lower.contains("не найден")
                || lower.contains("не поддерж")
                || lower.contains("останов")
                || lower.contains(" занят")
                || lower.contains("невозмож")
                || lower.contains("не указывает")
                || lower.contains("не совпадает")
                || lower.contains("нет root")
                || lower.contains("не проходит validate")
                || (lower.contains("уже найден") && lower.contains("не будет"))
                || (lower.startsWith("для ") && lower.contains(" нужен "));
    }

    private static String friendlyBlocker(String line) {
        String lower = lower(line);
        if (lower.startsWith("нужно заполнить")) {
            return "Заполните адрес VPS, SSH-логин и параметры Relay.";
        }
        if (lower.contains("не является linux")) {
            return "На сервере не обнаружен Linux. Автонастройка работает только на Linux-серверах.";
        }
        if (lower.contains("init-система") && lower.contains("не поддерж")) {
            return "Система запуска служб на этом сервере не распознана. Нужен systemd, OpenRC, runit или SysV init.";
        }
        if (lower.contains("архитектура") && lower.contains("не поддерж")) {
            return "Для архитектуры этого VPS пока нет готовой сборки Relay.";
        }
        if (lower.contains("dns домена не указывает")) {
            return "Домен пока ведёт не на этот VPS. Исправьте A/AAAA-запись и повторите проверку после обновления DNS.";
        }
        if (lower.contains("выбранный ip не совпадает")) {
            return "Выбранный IP не совпадает с публичным IP VPS. Вернитесь назад и выберите найденный адрес сервера.";
        }
        if (lower.contains("внутренний порт") && lower.contains("занят")) {
            return "Внутренний порт Relay уже занят другой программой. Освободите порт или выберите другой.";
        }
        if (lower.contains("порт 443 занят")) {
            return "Порт 443 занят неизвестной программой. Приложение не будет перехватывать чужой HTTPS-трафик.";
        }
        if (lower.contains("порт 80 занят")) {
            return "Порт 80 занят неизвестной программой, поэтому нельзя безопасно выпустить HTTPS-сертификат.";
        }
        if (lower.contains("нет root/passwordless sudo")) {
            return "У SSH-пользователя нет прав на установку пакетов. Используйте root или пользователя с sudo без дополнительного запроса пароля.";
        }
        if (lower.contains("пакетн") && lower.contains("не найден")) {
            return "Не найден поддерживаемый пакетный менеджер Linux. Поддерживаются apt, dnf, yum, zypper, apk, pacman, xbps и portage.";
        }
        if (lower.contains("не найден безопасный web stack")) {
            return "Приложение не нашло безопасного места для добавления маршрута Relay и не станет менять чужие сайты.";
        }
        if (lower.contains("существующий relay") && lower.contains("друг")
                && lower.contains("нельзя")) {
            return "На VPS уже работает Relay с другим адресом. Выберите его существующий адрес либо отдельный домен, который ведёт на этот VPS.";
        }
        if (lower.contains("python3")) {
            return "Для безопасного изменения конфигурации нужен Python 3. Установите его на VPS и повторите проверку.";
        }
        if (lower.contains("validate")) {
            return "Текущая конфигурация веб-сервера содержит ошибку. Исправьте её, затем повторите автонастройку.";
        }
        if (lower.contains("config.json")) {
            return "Relay найден, но его файл настроек определить не удалось. Обновите или восстановите установку Relay.";
        }
        if (lower.contains("apache/caddy") || lower.contains("встраивание остановлено")) {
            return "Существующая конфигурация сайта слишком сложная для безопасного автоматического изменения. Приложение не будет её перезаписывать.";
        }
        return line == null || line.trim().isEmpty()
                ? "Исправьте отмеченное условие и повторите проверку." : line.trim();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String joinLines(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(value.trim());
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
        boolean endpointMismatch = !publicUrl.isEmpty()
                && !samePublicEndpoint(publicUrl, request.publicUrl());
        if (endpointMismatch) {
            lines.add("Существующий Relay опубликован как " + publicUrl
                    + ", новый профиль будет использовать " + request.publicUrl() + ".");
        }
        Decision routeRepair = planExistingRelayRouteRepair(request, audit, lines);
        VpsSetupRequest effectiveRequest = existingRelayRouteRequest(request, audit);
        if (routeRepair != null) {
            canApply = routeRepair.canApply && canApply;
        }
        if (endpointMismatch && routeRepair == null
                && effectiveRequest != null
                && samePublicEndpoint(effectiveRequest.publicUrl(), request.publicUrl())) {
            lines.add("Существующий Relay использует другой публичный адрес; безопасный маршрут для нового адреса не найден, поэтому применять план нельзя.");
            canApply = false;
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
            return new Decision(canApply, InstallMode.EXISTING_RELAY_UPDATE, config,
                    "", routeRepair, effectiveRequest);
        }
        lines.add("Режим установки: Relay уже установлен, можно добавить новый token без полной автонастройки.");
        return new Decision(canApply, InstallMode.EXISTING_RELAY_ADD_TOKEN, config,
                "", routeRepair, effectiveRequest);
    }

    private static Decision planExistingRelayRouteRepair(VpsSetupRequest request,
                                                         VpsSetupAudit audit,
                                                         ArrayList<String> lines) {
        VpsSetupRequest routeRequest = existingRelayRouteRequest(request, audit);
        if (routeRequest == null || !routeRequest.reverseProxyMode()) return null;
        boolean hasWebRouteCandidate = audit.intValue("docker_caddy_domain_match_count") > 0
                || audit.intValue("caddy_domain_match_count") > 0
                || audit.intValue("nginx_domain_match_count") > 0;
        if (!hasWebRouteCandidate) return null;
        if (isYes(audit.value("docker_caddy_path_exists"))
                || isYes(audit.value("caddy_path_exists"))
                || isYes(audit.value("nginx_path_exists"))) {
            lines.add("Public route " + routeRequest.relayPath() + " уже найден в web stack.");
            return null;
        }
        lines.add("Public route " + routeRequest.relayPath()
                + " не найден; автонастройка должна восстановить public route.");
        boolean canApply = true;
        if (!audit.domainPointsToVps()) {
            lines.add("DNS домена не указывает на этот VPS; route repair остановлен.");
            canApply = false;
        }
        Decision dockerCaddy = planDockerCaddy(routeRequest, audit, lines, canApply);
        if (dockerCaddy != null) return dockerCaddy.withRouteRepairRequest(routeRequest);
        Decision hostCaddy = planHostCaddy(routeRequest, audit, lines, canApply);
        if (hostCaddy != null) return hostCaddy.withRouteRepairRequest(routeRequest);
        if (isYes(audit.value("nginx"))
                && audit.intValue("nginx_domain_match_count") == 1
                && isYes(audit.value("nginx_safe_embed"))) {
            String target = firstCsvValue(audit.value("nginx_domain_matches"));
            if (!target.isEmpty()) {
                lines.add("nginx: можно восстановить location " + routeRequest.relayPath()
                        + " в " + valueOrDash(target) + ".");
                return new Decision(canApply, InstallMode.NGINX_EXISTING_LOCATION, target)
                        .withRouteRepairRequest(routeRequest);
            }
        }
        lines.add("Не найден безопасный web stack для восстановления public route.");
        return new Decision(false, null, "");
    }

    private static VpsSetupRequest existingRelayRouteRequest(VpsSetupRequest request,
                                                             VpsSetupAudit audit) {
        if (request == null) return null;
        ExistingEndpoint endpoint = ExistingEndpoint.parse(audit.value("existing_relay_public_url"));
        if (endpoint == null || !endpoint.reverseProxyMode()) return request;
        // An IP equal to the SSH host is also the legacy fallback for an empty endpoint field.
        // Never migrate an already published Relay away from its working HTTPS endpoint merely
        // because that fallback became a valid IP-certificate mode.
        if (request.reverseProxyMode() && request.relayHostIsDomain()) return request;
        return request.withRelayEndpoint(endpoint.host, endpoint.port, endpoint.tls, endpoint.path);
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
        boolean ipEndpoint = request.relayHostIsIp();
        boolean domainEndpoint = request.relayHostIsDomain();
        lines.add("Ports: 443=" + valueOrUnknown(audit.value("port_443"))
                + ", 80=" + valueOrUnknown(audit.value("port_80"))
                + ", internal " + request.internalRelayPort() + "="
                + valueOrUnknown(audit.value("port_" + request.internalRelayPort())) + ".");
        if (ipEndpoint) {
            lines.add("Публичный IP: " + valueOrDash(domain)
                    + " (VPS сообщает " + valueOrDash(audit.publicIp()) + ").");
        } else {
            lines.add("DNS: " + valueOrDash(domain) + " -> " + valueOrDash(ips)
                    + " (VPS public IP: " + valueOrDash(audit.publicIp()) + ").");
        }
        if (audit.hasWebServer()) {
            lines.add("Найден существующий web stack: изменяться будет только изолированный TG Proxy route.");
        }

        if (!domainEndpoint && !ipEndpoint) {
            lines.add("Для HTTPS нужен корректный IP, домен или поддомен.");
            canApply = false;
        }
        if (domainEndpoint && !audit.domainPointsToVps()) {
            lines.add("DNS домена не указывает на этот VPS; исправьте A/AAAA-запись перед установкой.");
            canApply = false;
        }
        if (ipEndpoint && !sameHost(request.relayHost(), audit.publicIp())) {
            lines.add("Выбранный IP не совпадает с публичным IP VPS; выпуск IP-сертификата остановлен.");
            canApply = false;
        }
        if (audit.isPortBusy(request.internalRelayPort())) {
            lines.add("Внутренний порт " + request.internalRelayPort()
                    + " занят; reverse proxy не сможет проксировать Relay безопасно.");
            canApply = false;
        }

        if (domainEndpoint) {
            Decision dockerCaddy = planDockerCaddy(request, audit, lines, canApply);
            if (dockerCaddy != null) return dockerCaddy;

            Decision hostCaddy = planHostCaddy(request, audit, lines, canApply);
            if (hostCaddy != null) return hostCaddy;
        }

        boolean nginxInstalled = isYes(audit.value("nginx"));
        if (isYes(audit.value("apache")) || isYes(audit.value("caddy"))) {
            lines.add("443 управляется Apache/Caddy без безопасного совпадения endpoint; автоматическое изменение остановлено.");
            canApply = false;
        }
        if (!nginxInstalled && audit.isPortBusy(443)) {
            lines.add("Порт 443 занят неизвестным сервисом; nginx не будет перехватывать его.");
            canApply = false;
        }
        if (!nginxInstalled && audit.isPortBusy(80)) {
            lines.add("Порт 80 занят неизвестным сервисом; HTTP-01 проверка сертификата невозможна.");
            canApply = false;
        }

        int nginxMatches = audit.intValue("nginx_domain_match_count");
        if (nginxMatches > 0) {
            targetPath = firstCsvValue(audit.value("nginx_domain_matches"));
            if (audit.certificateExists()
                    && nginxMatches == 1 && isYes(audit.value("nginx_safe_embed"))
                    && !isYes(audit.value("nginx_path_exists"))) {
                mode = InstallMode.NGINX_EXISTING_LOCATION;
                lines.add("nginx: домен найден в одном простом server block, можно встроить location "
                        + request.relayPath() + " в " + valueOrDash(targetPath) + ".");
            } else {
                lines.add("Endpoint уже найден в nginx (" + nginxMatches
                        + "), но безопасное изолированное встраивание невозможно.");
                if (isYes(audit.value("nginx_path_exists"))) {
                    lines.add("Path " + request.relayPath()
                            + " уже найден в nginx; автонастройка не будет перезаписывать его.");
                }
                if (!audit.certificateExists()) {
                    lines.add("Для существующего server block не найден готовый сертификат; конфиг сайта не будет перестраиваться.");
                }
                canApply = false;
            }
        } else {
            if (!nginxInstalled || !audit.certificateExists()) {
                mode = InstallMode.NGINX_MANAGED_TLS;
                lines.add("Автонастройка установит nginx и Certbot, выпустит бесплатный HTTPS-сертификат и включит автоматическое продление.");
                if (ipEndpoint) {
                    lines.add("Для IP будет использован короткоживущий Let's Encrypt IP-сертификат и автоматическое продление.");
                }
                if (isNo(audit.value("root_or_passwordless_sudo"))) {
                    lines.add("У SSH-пользователя нет root/passwordless sudo для установки пакетов.");
                    canApply = false;
                }
                if (noPackageManager(audit)) {
                    lines.add("Не найден поддерживаемый пакетный менеджер apt/dnf/microdnf/yum/zypper/apk/pacman/xbps/portage.");
                    canApply = false;
                }
            } else {
                lines.add("nginx и сертификат уже готовы; будет создан отдельный server block TG Proxy.");
            }
        }
        if (!audit.value("nginx_domain_matches").isEmpty()) {
            lines.add("nginx matches: " + audit.value("nginx_domain_matches"));
        }
        lines.add("Режим установки: HTTPS " + valueOrDash(domain)
                + " -> nginx:443 -> 127.0.0.1:" + request.internalRelayPort()
                + " path " + request.relayPath() + ".");
        return new Decision(canApply, mode, targetPath);
    }

    private static boolean noPackageManager(VpsSetupAudit audit) {
        String value = audit.value("package_manager");
        return "none".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value);
    }

    private static boolean sameHost(String left, String right) {
        return VpsEndpointPolicy.normalizeHost(left)
                .equalsIgnoreCase(VpsEndpointPolicy.normalizeHost(right));
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
        ExistingEndpoint endpoint = ExistingEndpoint.parse(value);
        if (endpoint == null) return value == null ? "" : value.trim();
        return (endpoint.tls ? "https" : "http") + "://" + endpoint.host.toLowerCase(Locale.ROOT)
                + ":" + endpoint.port + VpsRelayConfig.manual(true, "Relay", endpoint.host,
                endpoint.port, endpoint.tls, endpoint.path, "token", "").path();
    }

    private static final class ExistingEndpoint {
        final String host;
        final int port;
        final boolean tls;
        final String path;

        private ExistingEndpoint(String host, int port, boolean tls, String path) {
            this.host = host;
            this.port = port;
            this.tls = tls;
            this.path = path;
        }

        static ExistingEndpoint parse(String publicUrl) {
            String value = publicUrl == null ? "" : publicUrl.trim();
            if (value.isEmpty()) return null;
            try {
                java.net.URI uri = new java.net.URI(value);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.US);
                boolean tls = "https".equals(scheme);
                if (!tls && !"http".equals(scheme)) return null;
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.US);
                if (host.isEmpty()) return null;
                int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
                String path = uri.getPath() == null || uri.getPath().trim().isEmpty()
                        ? "/apiws"
                        : uri.getPath().trim();
                return new ExistingEndpoint(host, port, tls, path);
            } catch (Exception ignored) {
                return null;
            }
        }

        boolean reverseProxyMode() {
            return tls && port == 443
                    && (VpsEndpointPolicy.isDomain(host) || VpsEndpointPolicy.isIpLiteral(host));
        }
    }

    private static final class Decision {
        final boolean canApply;
        final InstallMode installMode;
        final String targetPath;
        final String targetContainer;
        final InstallMode routeRepairMode;
        final String routeTargetPath;
        final String routeTargetContainer;
        final VpsSetupRequest routeRepairRequest;
        final VpsSetupRequest effectiveRequest;

        Decision(boolean canApply, InstallMode installMode, String targetPath) {
            this(canApply, installMode, targetPath, "");
        }

        Decision(boolean canApply, InstallMode installMode,
                 String targetPath, String targetContainer) {
            this(canApply, installMode, targetPath, targetContainer, null);
        }

        Decision(boolean canApply, InstallMode installMode,
                 String targetPath, String targetContainer, Decision routeRepair) {
            this(canApply, installMode, targetPath, targetContainer, routeRepair, null);
        }

        Decision(boolean canApply, InstallMode installMode,
                 String targetPath, String targetContainer, Decision routeRepair,
                 VpsSetupRequest effectiveRequest) {
            this(canApply, installMode, targetPath, targetContainer,
                    routeRepair, effectiveRequest, false);
        }

        private Decision(boolean canApply, InstallMode installMode,
                         String targetPath, String targetContainer,
                         Decision routeRepair, VpsSetupRequest effectiveRequest,
                         boolean unused) {
            this.canApply = canApply;
            this.installMode = installMode;
            this.targetPath = targetPath == null ? "" : targetPath;
            this.targetContainer = targetContainer == null ? "" : targetContainer;
            this.routeRepairMode = routeRepair == null ? null : routeRepair.installMode;
            this.routeTargetPath = routeRepair == null ? "" : routeRepair.targetPath;
            this.routeTargetContainer = routeRepair == null ? "" : routeRepair.targetContainer;
            this.routeRepairRequest = routeRepair == null ? null : routeRepair.effectiveRequest;
            this.effectiveRequest = effectiveRequest;
        }

        Decision withRouteRepairRequest(VpsSetupRequest request) {
            return new Decision(canApply, installMode, targetPath, targetContainer,
                    null, request, false);
        }

        static Decision empty() {
            return new Decision(false, null, "");
        }
    }
}
