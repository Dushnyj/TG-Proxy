package com.dushnyj.tgproxy;

final class VpsSetupScripts {
    static final String RELAY_VERSION = "1.0.1";
    private static final String RELEASE_BASE =
            "https://github.com/Dushnyj/TG-Proxy-Relay/releases/download";
    private static final String RELAY_DC_MAP_JSON =
            "{\"1\": \"149.154.175.50\", "
                    + "\"2\": \"149.154.167.51\", "
                    + "\"3\": \"149.154.175.100\", "
                    + "\"4\": \"149.154.167.91\", "
                    + "\"5\": \"149.154.171.5\"}";

    private VpsSetupScripts() {}

    static String audit() {
        return audit(null);
    }

    static String audit(VpsSetupRequest request) {
        if (request == null) request = VpsSetupRequest.builder().build();
        return "#!/bin/sh\n"
                + "set +e\n"
                + "DOMAIN=" + shellQuote(request.relayHostIsDomain() ? request.relayHost() : "") + "\n"
                + "RELAY_PATH=" + shellQuote(request.relayPath()) + "\n"
                + "SSH_HOST=" + shellQuote(request.sshCredentials().host()) + "\n"
                + "RELAY_PORT=" + request.relayPort() + "\n"
                + "INTERNAL_RELAY_PORT=" + request.internalRelayPort() + "\n"
                + "yn() { command -v \"$1\" >/dev/null 2>&1 && echo yes || echo no; }\n"
                + "port_state() {\n"
                + "  if command -v ss >/dev/null 2>&1; then ss -ltn | awk '{print $4}' | grep -Eq \"(^|:)${1}$\" && echo busy && return; fi\n"
                + "  if command -v netstat >/dev/null 2>&1; then netstat -ltn | awk '{print $4}' | grep -Eq \"(^|:)${1}$\" && echo busy && return; fi\n"
                + "  echo free\n"
                + "}\n"
                + "count_csv() { [ -z \"$1\" ] && echo 0 || printf '%s\\n' \"$1\" | tr ',' '\\n' | grep -c .; }\n"
                + "contains_word() { printf ' %s ' \"$1\" | grep -Fq \" $2 \"; }\n"
                + "domain_ips() { [ -z \"$DOMAIN\" ] && return; getent ahosts \"$DOMAIN\" 2>/dev/null | awk '{print $1}' | sort -u | tr '\\n' ' '; }\n"
                + "public_ip() {\n"
                + "  if command -v curl >/dev/null 2>&1; then curl -fsS --max-time 4 https://api.ipify.org && return; fi\n"
                + "  if command -v wget >/dev/null 2>&1; then wget -qO- -T 4 https://api.ipify.org && return; fi\n"
                + "  echo unknown\n"
                + "}\n"
                + "grep_matches() { [ -z \"$DOMAIN\" ] && return; grep -Rsl -- \"$DOMAIN\" \"$@\" 2>/dev/null | tr '\\n' ',' | sed 's/,$//'; }\n"
                + "docker_caddy_containers() {\n"
                + "  command -v docker >/dev/null 2>&1 || return\n"
                + "  docker ps --format '{{.Names}}\\t{{.Image}}' 2>/dev/null | awk 'tolower($0) ~ /caddy/ {print $1}'\n"
                + "}\n"
                + "docker_caddy_container_for_domain() {\n"
                + "  [ -z \"$DOMAIN\" ] && return\n"
                + "  for c in $(docker_caddy_containers); do\n"
                + "    docker exec -e TGPROXY_DOMAIN=\"$DOMAIN\" \"$c\" sh -c 'env | awk -F= -v dom=\"$TGPROXY_DOMAIN\" \"tolower(\\$2)==tolower(dom){found=1} END{exit found?0:1}\"' >/dev/null 2>&1 && { printf '%s\\n' \"$c\"; return; }\n"
                + "    docker exec -e TGPROXY_DOMAIN=\"$DOMAIN\" \"$c\" sh -c 'grep -Fq -- \"$TGPROXY_DOMAIN\" /etc/caddy/Caddyfile 2>/dev/null' >/dev/null 2>&1 && { printf '%s\\n' \"$c\"; return; }\n"
                + "  done\n"
                + "}\n"
                + "docker_caddy_target_for_container() {\n"
                + "  [ -z \"$1\" ] && return\n"
                + "  docker inspect \"$1\" --format '{{range .Mounts}}{{if eq .Destination \"/etc/caddy/Caddyfile\"}}{{.Source}}{{end}}{{end}}' 2>/dev/null\n"
                + "}\n"
                + "docker_caddy_validate() {\n"
                + "  [ -z \"$1\" ] && echo no && return\n"
                + "  docker exec \"$1\" caddy validate --config /etc/caddy/Caddyfile >/dev/null 2>&1 && echo yes || echo no\n"
                + "}\n"
                + "caddy_validate_config() {\n"
                + "  [ -z \"$1\" ] && echo no && return\n"
                + "  command -v caddy >/dev/null 2>&1 || { echo no; return; }\n"
                + "  caddy validate --config \"$1\" >/dev/null 2>&1 && echo yes || echo no\n"
                + "}\n"
                + "discover_domains() {\n"
                + "  {\n"
                + "    grep -RhoE '^[[:space:]]*server_name[[:space:]]+[^;]+' /etc/nginx/sites-enabled /etc/nginx/conf.d /etc/nginx/sites-available 2>/dev/null | sed -E 's/^[[:space:]]*server_name[[:space:]]+//' | tr ' ' '\\n'\n"
                + "    grep -RhoE '^[[:space:]]*(ServerName|ServerAlias)[[:space:]]+[^[:space:]]+' /etc/apache2/sites-enabled /etc/apache2/sites-available /etc/httpd/conf.d 2>/dev/null | awk '{print $2}'\n"
                + "    grep -RhoE '^[[:space:]]*[A-Za-z0-9*_.-]+\\.[A-Za-z]{2,}([[:space:]]*[,{:]|$)' /etc/caddy 2>/dev/null | sed -E 's/[,{:[:space:]]+$//'\n"
                + "    find /opt /srv /root /home -maxdepth 6 \\( -iname Caddyfile -o -iname '*.caddy' \\) -type f -exec grep -hoE '^[[:space:]]*[A-Za-z0-9*_.-]+\\.[A-Za-z]{2,}([[:space:]]*[,{:]|$)' {} + 2>/dev/null | sed -E 's/[,{:[:space:]]+$//'\n"
                + "    find /opt /srv /root /home -maxdepth 6 \\( -name '.env' -o -iname 'docker-compose.yml' -o -iname 'compose.yml' \\) -type f -exec grep -hoE '^[[:space:]-]*[A-Za-z0-9_]*DOMAIN[[:space:]]*[:=][[:space:]]*[^[:space:]#]+' {} + 2>/dev/null | sed -E 's/.*[:=][[:space:]]*//' | tr -d '\"'\n"
                + "    find /etc/letsencrypt/live -mindepth 1 -maxdepth 1 -type d -printf '%f\\n' 2>/dev/null\n"
                + "  } | sed -E 's/^\\*\\.//' | sed -E 's/\\.$//' | grep -E '^[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}$' | grep -Ev '^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$' | sort -u | tr '\\n' ',' | sed 's/,$//'\n"
                + "}\n"
                + "json_value() {\n"
                + "  [ -f \"$1\" ] || return\n"
                + "  command -v python3 >/dev/null 2>&1 || return\n"
                + "  python3 - \"$1\" \"$2\" <<'PY'\n"
                + "import json, sys\n"
                + "try:\n"
                + "    with open(sys.argv[1], 'r', encoding='utf-8') as fh:\n"
                + "        data = json.load(fh)\n"
                + "    value = data.get(sys.argv[2], '')\n"
                + "    print('' if value is None else value)\n"
                + "except Exception:\n"
                + "    pass\n"
                + "PY\n"
                + "}\n"
                + "public_url_host() {\n"
                + "  v=$(printf '%s' \"$1\" | sed -E 's#^[A-Za-z][A-Za-z0-9+.-]*://##; s#/.*$##; s#:[0-9]+$##')\n"
                + "  printf '%s\\n' \"$v\" | grep -E '^[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}$' | grep -Ev '^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$' | head -n1\n"
                + "}\n"
                + "public_url_path() {\n"
                + "  v=$(printf '%s' \"$1\" | sed -E 's#^[A-Za-z][A-Za-z0-9+.-]*://##')\n"
                + "  case \"$v\" in */*) p=\"/${v#*/}\" ;; *) return ;; esac\n"
                + "  p=${p%%\\?*}; p=${p%%#*}\n"
                + "  [ \"$p\" = \"/\" ] || printf '%s\\n' \"$p\"\n"
                + "}\n"
                + "is_ipv4() {\n"
                + "  printf '%s' \"$1\" | grep -Eq '^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$'\n"
                + "}\n"
                + "EXISTING_CONFIG=/etc/tgproxy-relay/config.json\n"
                + "EXISTING_RELAY=no\n"
                + "[ -f \"$EXISTING_CONFIG\" ] && EXISTING_RELAY=yes\n"
                + "EXISTING_PUBLIC_URL=$(json_value \"$EXISTING_CONFIG\" publicUrl)\n"
                + "EXISTING_LISTEN=$(json_value \"$EXISTING_CONFIG\" listen)\n"
                + "EXISTING_PUBLIC_HOST=$(public_url_host \"$EXISTING_PUBLIC_URL\")\n"
                + "if [ -n \"$EXISTING_PUBLIC_HOST\" ]; then\n"
                + "  if [ -z \"$DOMAIN\" ] || is_ipv4 \"$DOMAIN\"; then\n"
                + "    DOMAIN=\"$EXISTING_PUBLIC_HOST\"\n"
                + "    RELAY_PATH=$(public_url_path \"$EXISTING_PUBLIC_URL\")\n"
                + "    [ -z \"$RELAY_PATH\" ] && RELAY_PATH=/apiws\n"
                + "  fi\n"
                + "fi\n"
                + "printf 'os=%s\\n' \"$(. /etc/os-release 2>/dev/null; echo ${PRETTY_NAME:-unknown})\"\n"
                + "printf 'arch=%s\\n' \"$(uname -m)\"\n"
                + "printf 'systemd=%s\\n' \"$(yn systemctl)\"\n"
                + "printf 'nginx=%s\\n' \"$(yn nginx)\"\n"
                + "printf 'apache=%s\\n' \"$(yn apache2)\"\n"
                + "printf 'caddy=%s\\n' \"$(yn caddy)\"\n"
                + "printf 'docker=%s\\n' \"$(yn docker)\"\n"
                + "printf 'ufw=%s\\n' \"$(yn ufw)\"\n"
                + "printf 'curl=%s\\n' \"$(yn curl)\"\n"
                + "printf 'wget=%s\\n' \"$(yn wget)\"\n"
                + "printf 'tar=%s\\n' \"$(yn tar)\"\n"
                + "printf 'python3=%s\\n' \"$(yn python3)\"\n"
                + "printf 'port_443=%s\\n' \"$(port_state 443)\"\n"
                + "printf 'port_%s=%s\\n' \"$RELAY_PORT\" \"$(port_state \"$RELAY_PORT\")\"\n"
                + "printf 'port_%s=%s\\n' \"$INTERNAL_RELAY_PORT\" \"$(port_state \"$INTERNAL_RELAY_PORT\")\"\n"
                + "PUBLIC_IP=$(public_ip)\n"
                + "DOMAIN_IPS=$(domain_ips)\n"
                + "LOCAL_IPS=$(hostname -I 2>/dev/null | tr '\\n' ' ')\n"
                + "POINTS=unknown\n"
                + "if [ -n \"$DOMAIN\" ]; then\n"
                + "  if [ -z \"$DOMAIN_IPS\" ]; then POINTS=no;\n"
                + "  elif contains_word \"$DOMAIN_IPS\" \"$SSH_HOST\" || contains_word \"$DOMAIN_IPS\" \"$PUBLIC_IP\"; then POINTS=yes;\n"
                + "  else for ip in $LOCAL_IPS; do contains_word \"$DOMAIN_IPS\" \"$ip\" && POINTS=yes; done; [ \"$POINTS\" = unknown ] && POINTS=no; fi\n"
                + "fi\n"
                + "NGINX_MATCHES=$(grep_matches /etc/nginx/sites-enabled /etc/nginx/conf.d /etc/nginx/sites-available)\n"
                + "CADDY_MATCHES=$(grep_matches /etc/caddy)\n"
                + "APACHE_MATCHES=$(grep_matches /etc/apache2/sites-enabled /etc/apache2/sites-available /etc/httpd/conf.d)\n"
                + "CADDY_PATH_EXISTS=no\n"
                + "CADDY_VALIDATE=no\n"
                + "CADDY_SAFE_EMBED=no\n"
                + "if [ \"$(count_csv \"$CADDY_MATCHES\")\" = \"1\" ]; then\n"
                + "  CADDY_FILE=$(printf '%s' \"$CADDY_MATCHES\" | cut -d, -f1)\n"
                + "  if [ -f \"$CADDY_FILE\" ]; then\n"
                + "    [ -n \"$RELAY_PATH\" ] && grep -Fq -- \"$RELAY_PATH\" \"$CADDY_FILE\" 2>/dev/null && CADDY_PATH_EXISTS=yes\n"
                + "    CADDY_VALIDATE=$(caddy_validate_config \"$CADDY_FILE\")\n"
                + "    [ \"$CADDY_VALIDATE\" = yes ] && CADDY_SAFE_EMBED=yes\n"
                + "  fi\n"
                + "fi\n"
                + "DOCKER_CADDY_CONTAINER=$(docker_caddy_container_for_domain)\n"
                + "DOCKER_CADDY_TARGET=$(docker_caddy_target_for_container \"$DOCKER_CADDY_CONTAINER\")\n"
                + "DOCKER_CADDY_MATCHES=\n"
                + "[ -n \"$DOCKER_CADDY_TARGET\" ] && DOCKER_CADDY_MATCHES=\"$DOCKER_CADDY_TARGET\"\n"
                + "DOCKER_CADDY_PATH_EXISTS=no\n"
                + "[ -n \"$RELAY_PATH\" ] && [ -n \"$DOCKER_CADDY_TARGET\" ] && grep -Fq -- \"$RELAY_PATH\" \"$DOCKER_CADDY_TARGET\" 2>/dev/null && DOCKER_CADDY_PATH_EXISTS=yes\n"
                + "DOCKER_CADDY_VALIDATE=$(docker_caddy_validate \"$DOCKER_CADDY_CONTAINER\")\n"
                + "DOCKER_CADDY_SAFE_EMBED=no\n"
                + "[ -n \"$DOCKER_CADDY_TARGET\" ] && [ \"$DOCKER_CADDY_VALIDATE\" = yes ] && DOCKER_CADDY_SAFE_EMBED=yes\n"
                + "DISCOVERED_DOMAINS=$(discover_domains)\n"
                + "NGINX_SAFE_EMBED=unknown\n"
                + "NGINX_PATH_EXISTS=no\n"
                + "if [ \"$(count_csv \"$NGINX_MATCHES\")\" = \"1\" ]; then\n"
                + "  NGINX_FILE=$(printf '%s' \"$NGINX_MATCHES\" | cut -d, -f1)\n"
                + "  if [ -f \"$NGINX_FILE\" ]; then\n"
                + "    SERVER_COUNT=$(grep -Ec '^[[:space:]]*server[[:space:]]*\\{' \"$NGINX_FILE\" 2>/dev/null)\n"
                + "    [ \"$SERVER_COUNT\" = \"1\" ] && NGINX_SAFE_EMBED=yes || NGINX_SAFE_EMBED=no\n"
                + "    [ -n \"$RELAY_PATH\" ] && grep -Fq -- \"$RELAY_PATH\" \"$NGINX_FILE\" 2>/dev/null && NGINX_PATH_EXISTS=yes\n"
                + "  fi\n"
                + "fi\n"
                + "CERT_EXISTS=no\n"
                + "[ -n \"$DOMAIN\" ] && [ -f \"/etc/letsencrypt/live/$DOMAIN/fullchain.pem\" ] && [ -f \"/etc/letsencrypt/live/$DOMAIN/privkey.pem\" ] && CERT_EXISTS=yes\n"
                + "printf 'public_ip=%s\\n' \"$PUBLIC_IP\"\n"
                + "printf 'domain=%s\\n' \"$DOMAIN\"\n"
                + "printf 'domain_ips=%s\\n' \"$DOMAIN_IPS\"\n"
                + "printf 'domain_points_to_vps=%s\\n' \"$POINTS\"\n"
                + "printf 'discovered_domains=%s\\n' \"$DISCOVERED_DOMAINS\"\n"
                + "printf 'existing_relay=%s\\n' \"$EXISTING_RELAY\"\n"
                + "printf 'existing_relay_config=%s\\n' \"$EXISTING_CONFIG\"\n"
                + "printf 'existing_relay_public_url=%s\\n' \"$EXISTING_PUBLIC_URL\"\n"
                + "printf 'existing_relay_listen=%s\\n' \"$EXISTING_LISTEN\"\n"
                + "printf 'cert_exists=%s\\n' \"$CERT_EXISTS\"\n"
                + "printf 'cert_path=%s\\n' \"/etc/letsencrypt/live/$DOMAIN/fullchain.pem\"\n"
                + "printf 'nginx_domain_matches=%s\\n' \"$NGINX_MATCHES\"\n"
                + "printf 'nginx_domain_match_count=%s\\n' \"$(count_csv \"$NGINX_MATCHES\")\"\n"
                + "printf 'nginx_safe_embed=%s\\n' \"$NGINX_SAFE_EMBED\"\n"
                + "printf 'nginx_path_exists=%s\\n' \"$NGINX_PATH_EXISTS\"\n"
                + "printf 'caddy_domain_matches=%s\\n' \"$CADDY_MATCHES\"\n"
                + "printf 'caddy_domain_match_count=%s\\n' \"$(count_csv \"$CADDY_MATCHES\")\"\n"
                + "printf 'caddy_safe_embed=%s\\n' \"$CADDY_SAFE_EMBED\"\n"
                + "printf 'caddy_path_exists=%s\\n' \"$CADDY_PATH_EXISTS\"\n"
                + "printf 'caddy_validate=%s\\n' \"$CADDY_VALIDATE\"\n"
                + "printf 'docker_caddy_container=%s\\n' \"$DOCKER_CADDY_CONTAINER\"\n"
                + "printf 'docker_caddy_domain_matches=%s\\n' \"$DOCKER_CADDY_MATCHES\"\n"
                + "printf 'docker_caddy_domain_match_count=%s\\n' \"$(count_csv \"$DOCKER_CADDY_MATCHES\")\"\n"
                + "printf 'docker_caddy_safe_embed=%s\\n' \"$DOCKER_CADDY_SAFE_EMBED\"\n"
                + "printf 'docker_caddy_path_exists=%s\\n' \"$DOCKER_CADDY_PATH_EXISTS\"\n"
                + "printf 'docker_caddy_validate=%s\\n' \"$DOCKER_CADDY_VALIDATE\"\n"
                + "printf 'apache_domain_matches=%s\\n' \"$APACHE_MATCHES\"\n"
                + "printf 'apache_domain_match_count=%s\\n' \"$(count_csv \"$APACHE_MATCHES\")\"\n";
    }

    static String backup() {
        return "#!/bin/sh\n"
                + "set -eu\n"
                + sudoPrelude()
                + "BACKUP_DIR=/var/backups/tgproxy-relay/$(date +%Y%m%d-%H%M%S)\n"
                + "$SUDO mkdir -p \"$BACKUP_DIR\" /opt/tgproxy-relay /etc/tgproxy-relay /var/log/tgproxy-relay\n"
                + "for f in /etc/tgproxy-relay/config.json /etc/systemd/system/tgproxy-relay.service /etc/nginx/conf.d/tgproxy-relay-*.conf /etc/nginx/snippets/tgproxy-relay-*.conf /etc/nginx/sites-enabled/* /etc/nginx/sites-available/* /etc/caddy/Caddyfile /etc/caddy/*.caddy; do\n"
                + "  [ -f \"$f\" ] && $SUDO cp -p \"$f\" \"$BACKUP_DIR/$(basename \"$f\")\" || true\n"
                + "done\n"
                + "find /etc/caddy /opt /srv /root /home -maxdepth 6 \\( -iname Caddyfile -o -iname '*.caddy' \\) -type f 2>/dev/null | while IFS= read -r f; do\n"
                + "  safe=$(printf '%s' \"$f\" | sed 's#^/##; s#[^A-Za-z0-9._-]#_#g')\n"
                + "  $SUDO cp -p \"$f\" \"$BACKUP_DIR/$safe\" || true\n"
                + "done\n"
                + "printf '%s\\n' \"$BACKUP_DIR\"\n";
    }

    static String install(VpsSetupRequest request) {
        return install(request, null);
    }

    static String install(VpsSetupRequest request, VpsSetupPlan plan) {
        if (request == null) request = VpsSetupRequest.builder().build();
        if (plan != null && plan.installMode() == VpsSetupPlan.InstallMode.EXISTING_RELAY_ADD_TOKEN) {
            return addTokenToExistingRelay(request, plan);
        }
        if (plan != null && plan.installMode() == VpsSetupPlan.InstallMode.EXISTING_RELAY_UPDATE) {
            return updateExistingRelay(request, plan);
        }
        boolean dockerCaddyMode = plan != null
                && plan.installMode() == VpsSetupPlan.InstallMode.DOCKER_CADDY_EXISTING_SITE;
        boolean hostCaddyMode = plan != null
                && plan.installMode() == VpsSetupPlan.InstallMode.CADDY_EXISTING_SITE;
        String version = request.releaseVersion().isEmpty() ? RELAY_VERSION : request.releaseVersion();
        String token = request.relayToken();
        String archive = "TG-Proxy-Relay-v" + version + "-linux-${RELAY_ARCH}.tar.gz";
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/sh\n")
                .append("set -eu\n")
                .append(sudoPrelude())
                .append("TOKEN=").append(shellQuote(token)).append('\n')
                .append("PUBLIC_URL=").append(shellQuote(request.publicUrl())).append('\n')
                .append("DOMAIN=").append(shellQuote(request.relayHost())).append('\n')
                .append("RELAY_PATH=").append(shellQuote(request.relayPath())).append('\n')
                .append("INTERNAL_RELAY_PORT=").append(request.internalRelayPort()).append('\n')
                .append("LISTEN=").append(shellQuote(request.relayListenAddress())).append('\n')
                .append("VERSION=").append(shellQuote(version)).append('\n')
                .append("case \"$(uname -m)\" in\n")
                .append("  x86_64|amd64) RELAY_ARCH=amd64 ;;\n")
                .append("  aarch64|arm64) RELAY_ARCH=arm64 ;;\n")
                .append("  *) echo unsupported_arch >&2; exit 42 ;;\n")
                .append("esac\n")
                .append("ARCHIVE=\"").append(archive).append("\"\n")
                .append("URL=\"").append(RELEASE_BASE).append("/v${VERSION}/${ARCHIVE}\"\n")
                .append("TMPDIR=$(mktemp -d)\n")
                .append("cleanup() { rm -rf \"$TMPDIR\"; }\n")
                .append("trap cleanup EXIT\n");
        if (dockerCaddyMode) {
            script.append(dockerCaddyPrelude(plan.targetPath(), plan.targetContainer()));
        }
        script
                .append("if command -v curl >/dev/null 2>&1; then curl -fsSL \"$URL\" -o \"$TMPDIR/relay.tar.gz\";\n")
                .append("elif command -v wget >/dev/null 2>&1; then wget -qO \"$TMPDIR/relay.tar.gz\" \"$URL\";\n")
                .append("else echo curl_or_wget_required >&2; exit 43; fi\n")
                .append("tar -xzf \"$TMPDIR/relay.tar.gz\" -C \"$TMPDIR\"\n")
                .append("$SUDO install -d -m 0755 /opt/tgproxy-relay /etc/tgproxy-relay /var/log/tgproxy-relay\n")
                .append("if ! id tgproxy-relay >/dev/null 2>&1; then $SUDO useradd --system --home /nonexistent --shell /usr/sbin/nologin tgproxy-relay; fi\n")
                .append("$SUDO install -m 0755 \"$TMPDIR/tgproxy-relay\" /opt/tgproxy-relay/tgproxy-relay\n")
                .append("$SUDO chown -R tgproxy-relay:tgproxy-relay /var/log/tgproxy-relay || true\n")
                .append("TOKEN_HASH=$($SUDO /opt/tgproxy-relay/tgproxy-relay -token \"$TOKEN\" -print-token-hash)\n")
                .append("$SUDO sh -c 'cat > /etc/tgproxy-relay/config.json' <<EOF\n")
                .append("{\n")
                .append("  \"listen\": \"$LISTEN\",\n")
                .append("  \"publicUrl\": \"$PUBLIC_URL\",\n")
                .append("  \"tokens\": [{\"name\": \"phone\", \"hash\": \"$TOKEN_HASH\"}],\n")
                .append("  \"telegram\": {\n")
                .append("    \"connectTimeoutMs\": 7000,\n")
                .append("    \"idleTimeoutSec\": 125,\n")
                .append("    \"dcMap\": ").append(RELAY_DC_MAP_JSON).append("\n")
                .append("  }\n")
                .append("}\n")
                .append("EOF\n")
                .append("$SUDO chmod 0640 /etc/tgproxy-relay/config.json\n")
                .append("$SUDO chown root:tgproxy-relay /etc/tgproxy-relay/config.json || $SUDO chmod 0644 /etc/tgproxy-relay/config.json\n")
                .append("$SUDO sh -c 'cat > /etc/systemd/system/tgproxy-relay.service' <<'EOF'\n")
                .append("[Unit]\n")
                .append("Description=TG Proxy VPS Relay\n")
                .append("After=network-online.target\n")
                .append("Wants=network-online.target\n\n")
                .append("[Service]\n")
                .append("Type=simple\n")
                .append("User=tgproxy-relay\n")
                .append("Group=tgproxy-relay\n")
                .append("ExecStart=/opt/tgproxy-relay/tgproxy-relay -config /etc/tgproxy-relay/config.json\n")
                .append("Restart=on-failure\n")
                .append("RestartSec=5s\n")
                .append("NoNewPrivileges=true\n")
                .append("PrivateTmp=true\n")
                .append("ReadWritePaths=/var/log/tgproxy-relay\n\n")
                .append("[Install]\n")
                .append("WantedBy=multi-user.target\n")
                .append("EOF\n");
        if (request.reverseProxyMode()) {
            if (dockerCaddyMode) {
                script.append(dockerCaddyExistingSiteConfig(request));
            } else if (hostCaddyMode) {
                script.append(caddyExistingSiteConfig(request, plan.targetPath()));
            } else if (plan != null && plan.installMode() == VpsSetupPlan.InstallMode.NGINX_EXISTING_LOCATION) {
                script.append(nginxExistingLocationConfig(request, plan.targetPath()));
            } else {
                script.append(nginxReverseProxyConfig(request));
            }
        }
        script.append("$SUDO systemctl daemon-reload\n")
                .append("$SUDO systemctl enable --now tgproxy-relay\n")
                .append("if command -v ufw >/dev/null 2>&1; then $SUDO ufw allow ")
                .append(request.reverseProxyMode() ? 443 : request.relayPort())
                .append("/tcp || true; fi\n");
        return script.toString();
    }

    private static String dockerCaddyPrelude(String targetPath, String container) {
        return "DOCKER_CADDY_TARGET=" + shellQuote(targetPath) + "\n"
                + "DOCKER_CADDY_CONTAINER=" + shellQuote(container) + "\n"
                + "[ -n \"$DOCKER_CADDY_TARGET\" ] || { echo docker_caddy_target_missing >&2; exit 60; }\n"
                + "[ -f \"$DOCKER_CADDY_TARGET\" ] || { echo docker_caddy_target_missing >&2; exit 60; }\n"
                + "if [ -z \"$DOCKER_CADDY_CONTAINER\" ]; then\n"
                + "  DOCKER_CADDY_CONTAINER=$($SUDO docker ps --format '{{.Names}}\\t{{.Image}}' 2>/dev/null | awk 'tolower($0) ~ /caddy/ {print $1; exit}')\n"
                + "fi\n"
                + "[ -n \"$DOCKER_CADDY_CONTAINER\" ] || { echo docker_caddy_container_missing >&2; exit 61; }\n"
                + "DOCKER_HOST_GATEWAY=$($SUDO docker exec \"$DOCKER_CADDY_CONTAINER\" sh -c \"ip route show default | sed -n 's/^default.* via \\([^ ]*\\).*/\\1/p' | head -n1\" 2>/dev/null)\n"
                + "[ -n \"$DOCKER_HOST_GATEWAY\" ] || { echo docker_caddy_gateway_missing >&2; exit 62; }\n"
                + "LISTEN=\"${DOCKER_HOST_GATEWAY}:${INTERNAL_RELAY_PORT}\"\n"
                + "DOCKER_CADDY_BRIDGE=\n"
                + "DOCKER_CADDY_SUBNET=\n"
                + "if command -v ip >/dev/null 2>&1; then\n"
                + "  DOCKER_CADDY_BRIDGE=$(ip -o -4 addr show | awk -v gw=\"$DOCKER_HOST_GATEWAY\" 'index($4, gw \"/\") == 1 {print $2; exit}')\n"
                + "  if [ -n \"$DOCKER_CADDY_BRIDGE\" ]; then\n"
                + "    DOCKER_CADDY_CIDR=$(ip -o -4 addr show \"$DOCKER_CADDY_BRIDGE\" | awk '{print $4; exit}')\n"
                + "    DOCKER_CADDY_SUBNET=$(python3 - \"$DOCKER_CADDY_CIDR\" <<'PY'\n"
                + "import ipaddress, sys\n"
                + "try:\n"
                + "    print(ipaddress.ip_interface(sys.argv[1]).network)\n"
                + "except Exception:\n"
                + "    pass\n"
                + "PY\n"
                + ")\n"
                + "  fi\n"
                + "fi\n"
                + "if command -v ufw >/dev/null 2>&1 && [ -n \"$DOCKER_CADDY_BRIDGE\" ] && [ -n \"$DOCKER_CADDY_SUBNET\" ]; then\n"
                + "  $SUDO ufw allow in on \"$DOCKER_CADDY_BRIDGE\" proto tcp from \"$DOCKER_CADDY_SUBNET\" to \"$DOCKER_HOST_GATEWAY\" port \"$INTERNAL_RELAY_PORT\" comment 'TG Proxy Relay Docker Caddy' || true\n"
                + "fi\n";
    }

    private static String dockerCaddyExistingSiteConfig(VpsSetupRequest request) {
        String marker = "# TGPROXY-RELAY " + request.relayHost() + " " + request.relayPath();
        String healthPath = managementPath(request.relayPath(), "/healthz");
        String versionPath = managementPath(request.relayPath(), "/version");
        String testRoutesPath = managementPath(request.relayPath(), "/test-routes");
        return "CADDY_MARKER=" + shellQuote(marker) + "\n"
                + "CADDY_ORIGINAL=\"$TMPDIR/Caddyfile.original\"\n"
                + "CADDY_TMP=\"$TMPDIR/Caddyfile.tgproxy\"\n"
                + "$SUDO cp -p \"$DOCKER_CADDY_TARGET\" \"$CADDY_ORIGINAL\"\n"
                + "CADDY_SITE_LABEL=$($SUDO docker exec -e TGPROXY_DOMAIN=\"$DOMAIN\" \"$DOCKER_CADDY_CONTAINER\" sh -c 'env | awk -F= -v dom=\"$TGPROXY_DOMAIN\" \"tolower(\\$2)==tolower(dom) && \\$1 ~ /DOMAIN$/ {print \\\"{\\$\\\" \\$1 \\\"}\\\"; exit}\"' 2>/dev/null || true)\n"
                + "[ -n \"$CADDY_SITE_LABEL\" ] || CADDY_SITE_LABEL=\"$DOMAIN\"\n"
                + "UPSTREAM=\"http://${DOCKER_HOST_GATEWAY}:${INTERNAL_RELAY_PORT}\"\n"
                + "$SUDO env CADDY_TARGET=\"$DOCKER_CADDY_TARGET\" CADDY_TMP=\"$CADDY_TMP\" CADDY_SITE_LABEL=\"$CADDY_SITE_LABEL\" CADDY_MARKER=\"$CADDY_MARKER\" RELAY_PATH=\"$RELAY_PATH\" UPSTREAM=\"$UPSTREAM\" python3 - <<'PY'\n"
                + "import os, re, sys\n"
                + "target = os.environ['CADDY_TARGET']\n"
                + "tmp = os.environ['CADDY_TMP']\n"
                + "label = os.environ['CADDY_SITE_LABEL'].strip()\n"
                + "marker = os.environ['CADDY_MARKER']\n"
                + "relay_path = os.environ['RELAY_PATH']\n"
                + "upstream = os.environ['UPSTREAM']\n"
                + "with open(target, 'r', encoding='utf-8') as fh:\n"
                + "    lines = fh.readlines()\n"
                + "if any(marker in line for line in lines):\n"
                + "    with open(tmp, 'w', encoding='utf-8') as fh:\n"
                + "        fh.writelines(lines)\n"
                + "    raise SystemExit(0)\n"
                + "def clean(line):\n"
                + "    return line.strip()\n"
                + "def is_target_header(line):\n"
                + "    stripped = clean(line)\n"
                + "    return stripped == label + ' {' or stripped.startswith(label + ' ') and '{' in stripped\n"
                + "start = -1\n"
                + "for i, line in enumerate(lines):\n"
                + "    if is_target_header(line):\n"
                + "        start = i\n"
                + "        break\n"
                + "if start < 0:\n"
                + "    raise SystemExit('docker_caddy_site_not_found')\n"
                + "depth = 0\n"
                + "end = -1\n"
                + "for i in range(start, len(lines)):\n"
                + "    depth += lines[i].count('{') - lines[i].count('}')\n"
                + "    if i > start and depth == 0:\n"
                + "        end = i\n"
                + "        break\n"
                + "if end < 0:\n"
                + "    raise SystemExit('docker_caddy_site_block_unclosed')\n"
                + "block = [\n"
                + "    '\\t' + marker + '\\n',\n"
                + "    '\\thandle " + healthPath + " {\\n',\n"
                + "    '\\t\\trewrite * /healthz\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "    '\\thandle " + versionPath + " {\\n',\n"
                + "    '\\t\\trewrite * /version\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "    '\\thandle " + testRoutesPath + " {\\n',\n"
                + "    '\\t\\trewrite * /test-routes\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "    '\\thandle ' + relay_path + '* {\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "]\n"
                + "if any(relay_path in line for line in lines[start + 1:end]):\n"
                + "    raise SystemExit('docker_caddy_path_exists')\n"
                + "out = lines[:start + 1] + block + lines[start + 1:]\n"
                + "with open(tmp, 'w', encoding='utf-8') as fh:\n"
                + "    fh.writelines(out)\n"
                + "PY\n"
                + dockerCaddyWriteFileInPlaceFunction()
                + "restore_caddy() { write_file_in_place \"$CADDY_ORIGINAL\" \"$DOCKER_CADDY_TARGET\" >/dev/null 2>&1 || true; $SUDO docker exec \"$DOCKER_CADDY_CONTAINER\" caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 || $SUDO docker restart \"$DOCKER_CADDY_CONTAINER\" >/dev/null 2>&1 || true; }\n"
                + "$SUDO docker cp \"$CADDY_TMP\" \"$DOCKER_CADDY_CONTAINER\":/tmp/tgproxy-caddy-validate\n"
                + "$SUDO docker exec \"$DOCKER_CADDY_CONTAINER\" caddy validate --config /tmp/tgproxy-caddy-validate || { restore_caddy; exit 63; }\n"
                + "write_file_in_place \"$CADDY_TMP\" \"$DOCKER_CADDY_TARGET\"\n"
                + "if $SUDO docker exec \"$DOCKER_CADDY_CONTAINER\" grep -Fq \"$CADDY_MARKER\" /etc/caddy/Caddyfile 2>/dev/null; then\n"
                + "  $SUDO docker exec \"$DOCKER_CADDY_CONTAINER\" caddy reload --config /etc/caddy/Caddyfile || { restore_caddy; exit 64; }\n"
                + "else\n"
                + "  $SUDO docker restart \"$DOCKER_CADDY_CONTAINER\" || { restore_caddy; exit 64; }\n"
                + "fi\n"
                + "$SUDO docker exec \"$DOCKER_CADDY_CONTAINER\" caddy validate --config /etc/caddy/Caddyfile || { restore_caddy; exit 63; }\n";
    }

    private static String dockerCaddyWriteFileInPlaceFunction() {
        return "write_file_in_place() {\n"
                + "  SRC=\"$1\"\n"
                + "  DST=\"$2\"\n"
                + "  $SUDO env SRC=\"$SRC\" DST=\"$DST\" python3 - <<'PY'\n"
                + "import os\n"
                + "src = os.environ['SRC']\n"
                + "dst = os.environ['DST']\n"
                + "with open(src, 'rb') as sf:\n"
                + "    data = sf.read()\n"
                + "with open(dst, 'r+b') as df:\n"
                + "    df.truncate(0)\n"
                + "    df.write(data)\n"
                + "    df.flush()\n"
                + "    os.fsync(df.fileno())\n"
                + "PY\n"
                + "}\n";
    }

    private static String caddyExistingSiteConfig(VpsSetupRequest request, String targetPath) {
        String domain = request.relayHost();
        String target = (targetPath == null || targetPath.trim().isEmpty())
                ? "/etc/caddy/Caddyfile"
                : targetPath.trim();
        String marker = "# TGPROXY-RELAY " + domain + " " + request.relayPath();
        String upstream = "http://127.0.0.1:" + request.internalRelayPort();
        String healthPath = managementPath(request.relayPath(), "/healthz");
        String versionPath = managementPath(request.relayPath(), "/version");
        String testRoutesPath = managementPath(request.relayPath(), "/test-routes");
        return "CADDY_TARGET=" + shellQuote(target) + "\n"
                + "CADDY_MARKER=" + shellQuote(marker) + "\n"
                + "CADDY_ORIGINAL=\"$TMPDIR/Caddyfile.original\"\n"
                + "CADDY_TMP=\"$TMPDIR/Caddyfile.tgproxy\"\n"
                + "[ -f \"$CADDY_TARGET\" ] || { echo caddy_target_missing >&2; exit 65; }\n"
                + "$SUDO cp -p \"$CADDY_TARGET\" \"$CADDY_ORIGINAL\"\n"
                + "$SUDO env CADDY_TARGET=\"$CADDY_TARGET\" CADDY_TMP=\"$CADDY_TMP\" DOMAIN=\"$DOMAIN\" CADDY_MARKER=\"$CADDY_MARKER\" RELAY_PATH=\"$RELAY_PATH\" UPSTREAM=" + shellQuote(upstream) + " python3 - <<'PY'\n"
                + "import os\n"
                + "target = os.environ['CADDY_TARGET']\n"
                + "tmp = os.environ['CADDY_TMP']\n"
                + "domain = os.environ['DOMAIN'].strip().lower()\n"
                + "marker = os.environ['CADDY_MARKER']\n"
                + "relay_path = os.environ['RELAY_PATH']\n"
                + "upstream = os.environ['UPSTREAM']\n"
                + "with open(target, 'r', encoding='utf-8') as fh:\n"
                + "    lines = fh.readlines()\n"
                + "if any(marker in line for line in lines):\n"
                + "    with open(tmp, 'w', encoding='utf-8') as fh:\n"
                + "        fh.writelines(lines)\n"
                + "    raise SystemExit(0)\n"
                + "def header_hosts(line):\n"
                + "    stripped = line.strip()\n"
                + "    if '{' not in stripped:\n"
                + "        return []\n"
                + "    header = stripped.split('{', 1)[0]\n"
                + "    header = header.replace(',', ' ').replace('https://', '').replace('http://', '')\n"
                + "    return [part.split('/')[0].lower() for part in header.split()]\n"
                + "start = -1\n"
                + "for i, line in enumerate(lines):\n"
                + "    if domain in header_hosts(line):\n"
                + "        start = i\n"
                + "        break\n"
                + "if start < 0:\n"
                + "    raise SystemExit('caddy_site_not_found')\n"
                + "depth = 0\n"
                + "end = -1\n"
                + "for i in range(start, len(lines)):\n"
                + "    depth += lines[i].count('{') - lines[i].count('}')\n"
                + "    if i > start and depth == 0:\n"
                + "        end = i\n"
                + "        break\n"
                + "if end < 0:\n"
                + "    raise SystemExit('caddy_site_block_unclosed')\n"
                + "if any(relay_path in line for line in lines[start + 1:end]):\n"
                + "    raise SystemExit('caddy_path_exists')\n"
                + "block = [\n"
                + "    '\\t' + marker + '\\n',\n"
                + "    '\\thandle " + healthPath + " {\\n',\n"
                + "    '\\t\\trewrite * /healthz\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "    '\\thandle " + versionPath + " {\\n',\n"
                + "    '\\t\\trewrite * /version\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "    '\\thandle " + testRoutesPath + " {\\n',\n"
                + "    '\\t\\trewrite * /test-routes\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "    '\\thandle ' + relay_path + '* {\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "]\n"
                + "out = lines[:start + 1] + block + lines[start + 1:]\n"
                + "with open(tmp, 'w', encoding='utf-8') as fh:\n"
                + "    fh.writelines(out)\n"
                + "PY\n"
                + "$SUDO install -m 0644 \"$CADDY_TMP\" \"$CADDY_TARGET\"\n"
                + "restore_caddy() { $SUDO install -m 0644 \"$CADDY_ORIGINAL\" \"$CADDY_TARGET\" >/dev/null 2>&1 || true; $SUDO caddy reload --config \"$CADDY_TARGET\" >/dev/null 2>&1 || $SUDO systemctl reload caddy >/dev/null 2>&1 || true; }\n"
                + "$SUDO caddy validate --config \"$CADDY_TARGET\" || { restore_caddy; exit 66; }\n"
                + "$SUDO caddy reload --config \"$CADDY_TARGET\" || $SUDO systemctl reload caddy || { restore_caddy; exit 67; }\n";
    }

    private static String addTokenToExistingRelay(VpsSetupRequest request, VpsSetupPlan plan) {
        String configPath = plan.targetPath().isEmpty()
                ? "/etc/tgproxy-relay/config.json"
                : plan.targetPath();
        return "#!/bin/sh\n"
                + "set -eu\n"
                + sudoPrelude()
                + "TOKEN=" + shellQuote(request.relayToken()) + "\n"
                + "EXISTING_CONFIG=" + shellQuote(configPath) + "\n"
                + "DOMAIN=" + shellQuote(request.relayHost()) + "\n"
                + "RELAY_PATH=" + shellQuote(request.relayPath()) + "\n"
                + "INTERNAL_RELAY_PORT=" + request.internalRelayPort() + "\n"
                + "[ -f \"$EXISTING_CONFIG\" ] || { echo existing_relay_config_missing >&2; exit 51; }\n"
                + "[ -x /opt/tgproxy-relay/tgproxy-relay ] || { echo tgproxy_relay_binary_missing >&2; exit 52; }\n"
                + "command -v python3 >/dev/null 2>&1 || { echo python3_required >&2; exit 53; }\n"
                + "TOKEN_HASH=$($SUDO /opt/tgproxy-relay/tgproxy-relay -token \"$TOKEN\" -print-token-hash)\n"
                + "TMP_CONFIG=$(mktemp)\n"
                + "TMPDIR=$(mktemp -d)\n"
                + "cleanup() { rm -f \"$TMP_CONFIG\"; rm -rf \"$TMPDIR\"; }\n"
                + "trap cleanup EXIT\n"
                + "$SUDO env TOKEN_HASH=\"$TOKEN_HASH\" EXISTING_CONFIG=\"$EXISTING_CONFIG\" TMP_CONFIG=\"$TMP_CONFIG\" python3 - <<'PY'\n"
                + "import json, os, time\n"
                + "path = os.environ['EXISTING_CONFIG']\n"
                + "tmp = os.environ['TMP_CONFIG']\n"
                + "token_hash = os.environ['TOKEN_HASH']\n"
                + "with open(path, 'r', encoding='utf-8') as fh:\n"
                + "    cfg = json.load(fh)\n"
                + "tokens = cfg.setdefault('tokens', [])\n"
                + "if not isinstance(tokens, list):\n"
                + "    raise SystemExit('invalid_tokens')\n"
                + "exists = any(isinstance(item, dict) and item.get('hash') == token_hash for item in tokens)\n"
                + "if not exists:\n"
                + "    tokens.append({'name': 'phone-' + time.strftime('%Y%m%d%H%M%S'), 'hash': token_hash})\n"
                + relayDcMapPythonRepair()
                + "with open(tmp, 'w', encoding='utf-8') as fh:\n"
                + "    json.dump(cfg, fh, indent=2, ensure_ascii=False)\n"
                + "    fh.write('\\n')\n"
                + "PY\n"
                + "$SUDO install -m 0640 \"$TMP_CONFIG\" \"$EXISTING_CONFIG\"\n"
                + existingConfigPermissions()
                + routeRepairScript(request, plan)
                + "$SUDO systemctl restart tgproxy-relay\n";
    }

    private static String updateExistingRelay(VpsSetupRequest request, VpsSetupPlan plan) {
        String configPath = plan.targetPath().isEmpty()
                ? "/etc/tgproxy-relay/config.json"
                : plan.targetPath();
        String version = request.releaseVersion().isEmpty() ? RELAY_VERSION : request.releaseVersion();
        String archive = "TG-Proxy-Relay-v" + version + "-linux-${RELAY_ARCH}.tar.gz";
        return "#!/bin/sh\n"
                + "set -eu\n"
                + sudoPrelude()
                + "TOKEN=" + shellQuote(request.relayToken()) + "\n"
                + "EXISTING_CONFIG=" + shellQuote(configPath) + "\n"
                + "DOMAIN=" + shellQuote(request.relayHost()) + "\n"
                + "RELAY_PATH=" + shellQuote(request.relayPath()) + "\n"
                + "INTERNAL_RELAY_PORT=" + request.internalRelayPort() + "\n"
                + "VERSION=" + shellQuote(version) + "\n"
                + "[ -f \"$EXISTING_CONFIG\" ] || { echo existing_relay_config_missing >&2; exit 51; }\n"
                + "command -v python3 >/dev/null 2>&1 || { echo python3_required >&2; exit 53; }\n"
                + "case \"$(uname -m)\" in\n"
                + "  x86_64|amd64) RELAY_ARCH=amd64 ;;\n"
                + "  aarch64|arm64) RELAY_ARCH=arm64 ;;\n"
                + "  *) echo unsupported_arch >&2; exit 42 ;;\n"
                + "esac\n"
                + "ARCHIVE=\"" + archive + "\"\n"
                + "URL=\"" + RELEASE_BASE + "/v${VERSION}/${ARCHIVE}\"\n"
                + "TMPDIR=$(mktemp -d)\n"
                + "TMP_CONFIG=$(mktemp)\n"
                + "cleanup() { rm -rf \"$TMPDIR\"; rm -f \"$TMP_CONFIG\"; }\n"
                + "trap cleanup EXIT\n"
                + "if command -v curl >/dev/null 2>&1; then curl -fsSL \"$URL\" -o \"$TMPDIR/relay.tar.gz\";\n"
                + "elif command -v wget >/dev/null 2>&1; then wget -qO \"$TMPDIR/relay.tar.gz\" \"$URL\";\n"
                + "else echo curl_or_wget_required >&2; exit 43; fi\n"
                + "tar -xzf \"$TMPDIR/relay.tar.gz\" -C \"$TMPDIR\"\n"
                + "$SUDO install -d -m 0755 /opt/tgproxy-relay\n"
                + "$SUDO install -m 0755 \"$TMPDIR/tgproxy-relay\" /opt/tgproxy-relay/tgproxy-relay\n"
                + "TOKEN_HASH=$($SUDO /opt/tgproxy-relay/tgproxy-relay -token \"$TOKEN\" -print-token-hash)\n"
                + "$SUDO env TOKEN_HASH=\"$TOKEN_HASH\" EXISTING_CONFIG=\"$EXISTING_CONFIG\" TMP_CONFIG=\"$TMP_CONFIG\" python3 - <<'PY'\n"
                + "import json, os, time\n"
                + "path = os.environ['EXISTING_CONFIG']\n"
                + "tmp = os.environ['TMP_CONFIG']\n"
                + "token_hash = os.environ['TOKEN_HASH']\n"
                + "with open(path, 'r', encoding='utf-8') as fh:\n"
                + "    cfg = json.load(fh)\n"
                + "tokens = cfg.setdefault('tokens', [])\n"
                + "if not isinstance(tokens, list):\n"
                + "    raise SystemExit('invalid_tokens')\n"
                + "exists = any(isinstance(item, dict) and item.get('hash') == token_hash for item in tokens)\n"
                + "if not exists:\n"
                + "    tokens.append({'name': 'phone-' + time.strftime('%Y%m%d%H%M%S'), 'hash': token_hash})\n"
                + relayDcMapPythonRepair()
                + "with open(tmp, 'w', encoding='utf-8') as fh:\n"
                + "    json.dump(cfg, fh, indent=2, ensure_ascii=False)\n"
                + "    fh.write('\\n')\n"
                + "PY\n"
                + "$SUDO install -m 0640 \"$TMP_CONFIG\" \"$EXISTING_CONFIG\"\n"
                + existingConfigPermissions()
                + routeRepairScript(request, plan)
                + "$SUDO systemctl restart tgproxy-relay\n";
    }

    private static String existingConfigPermissions() {
        return "$SUDO chmod 0640 \"$EXISTING_CONFIG\"\n"
                + "$SUDO chown root:tgproxy-relay \"$EXISTING_CONFIG\" || $SUDO chmod 0644 \"$EXISTING_CONFIG\"\n";
    }

    private static String relayDcMapPythonRepair() {
        return "relay_dc_map = {\n"
                + "    '1': '149.154.175.50',\n"
                + "    '2': '149.154.167.51',\n"
                + "    '3': '149.154.175.100',\n"
                + "    '4': '149.154.167.91',\n"
                + "    '5': '149.154.171.5',\n"
                + "}\n"
                + "telegram = cfg.setdefault('telegram', {})\n"
                + "if not isinstance(telegram, dict):\n"
                + "    raise SystemExit('invalid_telegram_config')\n"
                + "dc_map = telegram.get('dcMap')\n"
                + "if not isinstance(dc_map, dict):\n"
                + "    dc_map = {}\n"
                + "for dc, ip in relay_dc_map.items():\n"
                + "    current = str(dc_map.get(dc, '')).strip()\n"
                + "    if not current or current == '149.154.167.220':\n"
                + "        dc_map[dc] = ip\n"
                + "telegram['dcMap'] = dc_map\n";
    }

    private static String routeRepairScript(VpsSetupRequest request, VpsSetupPlan plan) {
        if (request == null || plan == null || !plan.hasRouteRepair()) return "";
        VpsSetupRequest routeRequest = plan.routeRepairRequest() == null
                ? request
                : plan.routeRepairRequest();
        VpsSetupPlan.InstallMode mode = plan.routeRepairMode();
        String prelude = "DOMAIN=" + shellQuote(routeRequest.relayHost()) + "\n"
                + "RELAY_PATH=" + shellQuote(routeRequest.relayPath()) + "\n"
                + "INTERNAL_RELAY_PORT=" + routeRequest.internalRelayPort() + "\n";
        if (mode == VpsSetupPlan.InstallMode.DOCKER_CADDY_EXISTING_SITE) {
            return prelude
                    + dockerCaddyPrelude(plan.routeTargetPath(), plan.routeTargetContainer())
                    + dockerCaddyExistingSiteConfig(routeRequest);
        }
        if (mode == VpsSetupPlan.InstallMode.CADDY_EXISTING_SITE) {
            return prelude + caddyExistingSiteConfig(routeRequest, plan.routeTargetPath());
        }
        if (mode == VpsSetupPlan.InstallMode.NGINX_EXISTING_LOCATION) {
            return prelude + nginxExistingLocationConfig(routeRequest, plan.routeTargetPath());
        }
        if (mode == VpsSetupPlan.InstallMode.NGINX_NEW_SERVER) {
            return prelude + nginxReverseProxyConfig(routeRequest);
        }
        return "";
    }

    static String rollback() {
        return "#!/bin/sh\n"
                + "set +e\n"
                + sudoPrelude()
                + "LATEST=$($SUDO sh -c 'ls -td /var/backups/tgproxy-relay/* 2>/dev/null | head -n1')\n"
                + "[ -z \"$LATEST\" ] && exit 0\n"
                + "[ -f \"$LATEST/config.json\" ] && $SUDO cp -p \"$LATEST/config.json\" /etc/tgproxy-relay/config.json\n"
                + "[ -f \"$LATEST/tgproxy-relay.service\" ] && $SUDO cp -p \"$LATEST/tgproxy-relay.service\" /etc/systemd/system/tgproxy-relay.service\n"
                + "for f in /etc/nginx/conf.d/tgproxy-relay-*.conf; do [ -f \"$f\" ] && $SUDO rm -f \"$f\"; done\n"
                + "find /etc/caddy /opt /srv /root /home -maxdepth 6 \\( -iname Caddyfile -o -iname '*.caddy' \\) -type f 2>/dev/null | while IFS= read -r f; do\n"
                + "  grep -Fq 'TGPROXY-RELAY' \"$f\" 2>/dev/null || continue\n"
                + "  safe=$(printf '%s' \"$f\" | sed 's#^/##; s#[^A-Za-z0-9._-]#_#g')\n"
                + "  [ -f \"$LATEST/$safe\" ] && $SUDO cp -p \"$LATEST/$safe\" \"$f\" || true\n"
                + "done\n"
                + "$SUDO systemctl daemon-reload || true\n"
                + "$SUDO systemctl restart tgproxy-relay || true\n"
                + "if command -v nginx >/dev/null 2>&1; then $SUDO nginx -t && $SUDO systemctl reload nginx || true; fi\n"
                + "if command -v docker >/dev/null 2>&1; then\n"
                + "  $SUDO docker ps --format '{{.Names}}\\t{{.Image}}' 2>/dev/null | awk 'tolower($0) ~ /caddy/ {print $1}' | while IFS= read -r c; do\n"
                + "    [ -n \"$c\" ] && $SUDO docker exec \"$c\" caddy validate --config /etc/caddy/Caddyfile >/dev/null 2>&1 && $SUDO docker exec \"$c\" caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 || true\n"
                + "  done\n"
                + "fi\n";
    }

    private static String nginxReverseProxyConfig(VpsSetupRequest request) {
        String domain = request.relayHost();
        String path = request.relayPath();
        String upstream = "http://127.0.0.1:" + request.internalRelayPort();
        String configPath = "/etc/nginx/conf.d/tgproxy-relay-" + safeName(domain) + ".conf";
        String healthPath = managementPath(path, "/healthz");
        String versionPath = managementPath(path, "/version");
        String testRoutesPath = managementPath(path, "/test-routes");
        return "NGINX_CONF=" + shellQuote(configPath) + "\n"
                + "$SUDO install -d -m 0755 /etc/nginx/conf.d\n"
                + "$SUDO tee \"$NGINX_CONF\" >/dev/null <<'EOF'\n"
                + "server {\n"
                + "    listen 443 ssl http2;\n"
                + "    server_name " + domain + ";\n\n"
                + "    ssl_certificate /etc/letsencrypt/live/" + domain + "/fullchain.pem;\n"
                + "    ssl_certificate_key /etc/letsencrypt/live/" + domain + "/privkey.pem;\n\n"
                + "    location = " + healthPath + " { proxy_pass " + upstream + "/healthz; }\n"
                + "    location = " + versionPath + " { proxy_pass " + upstream + "/version; }\n"
                + "    location = " + testRoutesPath + " { proxy_pass " + upstream + "/test-routes; }\n\n"
                + "    location ^~ " + path + " {\n"
                + "        proxy_http_version 1.1;\n"
                + "        proxy_set_header Host $host;\n"
                + "        proxy_set_header X-Real-IP $remote_addr;\n"
                + "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
                + "        proxy_set_header X-Forwarded-Proto https;\n"
                + "        proxy_set_header Upgrade $http_upgrade;\n"
                + "        proxy_set_header Connection \"upgrade\";\n"
                + "        proxy_pass " + upstream + ";\n"
                + "    }\n"
                + "}\n"
                + "EOF\n"
                + "$SUDO nginx -t\n"
                + "$SUDO systemctl reload nginx\n";
    }

    private static String nginxExistingLocationConfig(VpsSetupRequest request, String targetPath) {
        String domain = request.relayHost();
        String path = request.relayPath();
        String upstream = "http://127.0.0.1:" + request.internalRelayPort();
        String healthPath = managementPath(path, "/healthz");
        String versionPath = managementPath(path, "/version");
        String testRoutesPath = managementPath(path, "/test-routes");
        String target = (targetPath == null || targetPath.trim().isEmpty())
                ? "/etc/nginx/sites-enabled/" + safeName(domain) + ".conf"
                : targetPath.trim();
        String snippet = "/etc/nginx/snippets/tgproxy-relay-" + safeName(domain) + ".conf";
        String marker = "# TGPROXY-RELAY " + domain + " " + path;
        return "NGINX_TARGET=" + shellQuote(target) + "\n"
                + "NGINX_SNIPPET=" + shellQuote(snippet) + "\n"
                + "NGINX_MARKER=" + shellQuote(marker) + "\n"
                + "[ -f \"$NGINX_TARGET\" ] || { echo nginx_target_missing >&2; exit 54; }\n"
                + "$SUDO install -d -m 0755 /etc/nginx/snippets\n"
                + "$SUDO tee \"$NGINX_SNIPPET\" >/dev/null <<'EOF'\n"
                + "location = " + healthPath + " { proxy_pass " + upstream + "/healthz; }\n"
                + "location = " + versionPath + " { proxy_pass " + upstream + "/version; }\n"
                + "location = " + testRoutesPath + " { proxy_pass " + upstream + "/test-routes; }\n\n"
                + "location ^~ " + path + " {\n"
                + "    proxy_http_version 1.1;\n"
                + "    proxy_set_header Host $host;\n"
                + "    proxy_set_header X-Real-IP $remote_addr;\n"
                + "    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
                + "    proxy_set_header X-Forwarded-Proto https;\n"
                + "    proxy_set_header Upgrade $http_upgrade;\n"
                + "    proxy_set_header Connection \"upgrade\";\n"
                + "    proxy_pass " + upstream + ";\n"
                + "}\n"
                + "EOF\n"
                + "if ! $SUDO grep -Fq \"$NGINX_MARKER\" \"$NGINX_TARGET\"; then\n"
                + "  TMP_NGINX=$(mktemp)\n"
                + "  $SUDO awk -v inc=\"    include " + snippet + "; " + marker + "\" '\n"
                + "    { lines[NR] = $0 }\n"
                + "    END {\n"
                + "      inserted = 0\n"
                + "      for (i = NR; i >= 1; i--) {\n"
                + "        if (!inserted && lines[i] ~ /^[[:space:]]*}[[:space:]]*$/) {\n"
                + "          lines[i] = inc \"\\n\" lines[i]\n"
                + "          inserted = 1\n"
                + "          break\n"
                + "        }\n"
                + "      }\n"
                + "      if (!inserted) exit 44\n"
                + "      for (i = 1; i <= NR; i++) print lines[i]\n"
                + "    }' \"$NGINX_TARGET\" > \"$TMP_NGINX\"\n"
                + "  $SUDO install -m 0644 \"$TMP_NGINX\" \"$NGINX_TARGET\"\n"
                + "  rm -f \"$TMP_NGINX\"\n"
                + "fi\n"
                + "$SUDO nginx -t\n"
                + "$SUDO systemctl reload nginx\n";
    }

    private static String sudoPrelude() {
        return "if [ \"$(id -u)\" -eq 0 ]; then SUDO=\"\"; else SUDO=\"sudo -n\"; fi\n"
                + "$SUDO true >/dev/null 2>&1 || { echo root_or_passwordless_sudo_required >&2; exit 20; }\n";
    }

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) return "''";
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String managementPath(String basePath, String endpoint) {
        String base = basePath == null ? "" : basePath.trim();
        String suffix = endpoint == null ? "" : endpoint.trim();
        if (!suffix.startsWith("/")) suffix = "/" + suffix;
        while (base.endsWith("/") && base.length() > 1) {
            base = base.substring(0, base.length() - 1);
        }
        return base.isEmpty() || "/".equals(base) ? suffix : base + suffix;
    }

    private static String safeName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        normalized = normalized.replaceAll("[^a-z0-9]+", "_");
        while (normalized.startsWith("_")) normalized = normalized.substring(1);
        while (normalized.endsWith("_")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.isEmpty() ? "relay" : normalized;
    }
}


