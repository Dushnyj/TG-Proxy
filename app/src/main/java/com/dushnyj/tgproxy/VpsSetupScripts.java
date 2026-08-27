package com.dushnyj.tgproxy;

import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

final class VpsSetupScripts {
    static final String RELAY_VERSION = "1.2.0";
    private static final String RELEASE_BASE =
            "https://github.com/Dushnyj/TG-Proxy-Relay/releases/download";
    private static final String RELAY_DC_MAP_JSON =
            "{\"1\": \"149.154.175.50\", "
                    + "\"2\": \"149.154.167.51\", "
                    + "\"3\": \"149.154.175.100\", "
                    + "\"4\": \"149.154.167.91\", "
                    + "\"5\": \"149.154.171.5\", "
                    + "\"203\": \"91.105.192.100\"}";
    private static final String RELAY_TEST_DC_MAP_JSON =
            "{\"1\": \"149.154.175.10\", "
                    + "\"2\": \"149.154.167.40\", "
                    + "\"3\": \"149.154.175.117\"}";

    private VpsSetupScripts() {}

    static String audit() {
        return audit(null);
    }

    static String audit(VpsSetupRequest request) {
        if (request == null) request = VpsSetupRequest.builder().build();
        return "#!/bin/sh\n"
                + "set +e\n"
                + "DOMAIN=" + shellQuote(request.relayHost()) + "\n"
                + "RELAY_PATH=" + shellQuote(request.relayPath()) + "\n"
                + "SSH_HOST=" + shellQuote(request.sshCredentials().host()) + "\n"
                + "RELAY_PORT=" + request.relayPort() + "\n"
                + "INTERNAL_RELAY_PORT=" + request.internalRelayPort() + "\n"
                + "yn() { command -v \"$1\" >/dev/null 2>&1 && echo yes || echo no; }\n"
                + "package_manager() { command -v apt-get >/dev/null 2>&1 && { echo apt; return; }; command -v dnf >/dev/null 2>&1 && { echo dnf; return; }; command -v microdnf >/dev/null 2>&1 && { echo microdnf; return; }; command -v yum >/dev/null 2>&1 && { echo yum; return; }; command -v zypper >/dev/null 2>&1 && { echo zypper; return; }; command -v apk >/dev/null 2>&1 && { echo apk; return; }; command -v pacman >/dev/null 2>&1 && { echo pacman; return; }; command -v xbps-install >/dev/null 2>&1 && { echo xbps; return; }; command -v emerge >/dev/null 2>&1 && { echo portage; return; }; echo none; }\n"
                + "init_system() {\n"
                + "  if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then echo systemd; return; fi\n"
                + "  if command -v rc-service >/dev/null 2>&1 && command -v rc-update >/dev/null 2>&1; then echo openrc; return; fi\n"
                + "  if command -v sv >/dev/null 2>&1 && { [ -d /etc/sv ] || [ -d /var/service ] || [ -d /etc/service ]; }; then echo runit; return; fi\n"
                + "  if [ -d /etc/init.d ]; then echo sysv; return; fi\n"
                + "  echo portable\n"
                + "}\n"
                + "root_or_passwordless_sudo() { [ \"$(id -u)\" -eq 0 ] && { echo yes; return; }; command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1 && { echo yes; return; }; echo no; }\n"
                + "port_state() {\n"
                + "  if command -v ss >/dev/null 2>&1; then ss -ltn | awk '{print $4}' | grep -Eq \"(^|:)${1}$\" && echo busy && return; fi\n"
                + "  if command -v netstat >/dev/null 2>&1; then netstat -ltn | awk '{print $4}' | grep -Eq \"(^|:)${1}$\" && echo busy && return; fi\n"
                + "  echo free\n"
                + "}\n"
                + "count_csv() { [ -z \"$1\" ] && echo 0 || printf '%s\\n' \"$1\" | tr ',' '\\n' | grep -c .; }\n"
                + "contains_word() { printf ' %s ' \"$1\" | grep -Fq \" $2 \"; }\n"
                + "domain_ips() {\n"
                + "  [ -z \"$DOMAIN\" ] && return\n"
                + "  if command -v getent >/dev/null 2>&1; then getent ahosts \"$DOMAIN\" 2>/dev/null | awk '{print $1}'\n"
                + "  elif command -v dig >/dev/null 2>&1; then { dig +short A \"$DOMAIN\"; dig +short AAAA \"$DOMAIN\"; } 2>/dev/null\n"
                + "  elif command -v host >/dev/null 2>&1; then host \"$DOMAIN\" 2>/dev/null | awk '/has address|has IPv6 address/{print $NF}'\n"
                + "  elif command -v nslookup >/dev/null 2>&1; then nslookup \"$DOMAIN\" 2>/dev/null | awk '/^Address: /{print $2}'\n"
                + "  fi | sort -u | tr '\\n' ' '\n"
                + "}\n"
                + "public_ip() {\n"
                + "  for u in https://api.ipify.org https://checkip.amazonaws.com https://ifconfig.me/ip; do\n"
                + "    v=\n"
                + "    if command -v curl >/dev/null 2>&1; then v=$(curl -fsS --max-time 4 \"$u\" 2>/dev/null | tr -d ' \\r\\n');\n"
                + "    elif command -v wget >/dev/null 2>&1; then v=$(wget -qO- -T 4 \"$u\" 2>/dev/null | tr -d ' \\r\\n'); fi\n"
                + "    printf '%s' \"$v\" | grep -Eq '^[0-9]{1,3}(\\.[0-9]{1,3}){3}$' && { printf '%s\\n' \"$v\"; return; }\n"
                + "  done\n"
                + "  printf '%s' \"$SSH_HOST\" | grep -Eq '^[0-9]{1,3}(\\.[0-9]{1,3}){3}$' && { printf '%s\\n' \"$SSH_HOST\"; return; }\n"
                + "  v=\n"
                + "  command -v getent >/dev/null 2>&1 && v=$(getent ahostsv4 \"$SSH_HOST\" 2>/dev/null | awk 'NR==1{print $1}')\n"
                + "  printf '%s' \"$v\" | grep -Eq '^[0-9]{1,3}(\\.[0-9]{1,3}){3}$' && { printf '%s\\n' \"$v\"; return; }\n"
                + "  echo unknown\n"
                + "}\n"
                + "active_matches() {\n"
                + "  [ -z \"$DOMAIN\" ] && return\n"
                + "  for root in \"$@\"; do\n"
                + "    [ -d \"$root\" ] || continue\n"
                + "    find -L \"$root\" -type f ! -name '*.bak' ! -name '*.backup' ! -name '*.disabled' ! -name '*.old' ! -name '*.orig' ! -name '*.save' ! -name '*~' ! -name '*.dpkg-*' -exec grep -Fl -- \"$DOMAIN\" {} + 2>/dev/null\n"
                + "  done | sort -u | tr '\\n' ',' | sed 's/,$//'\n"
                + "}\n"
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
                + "relay_token_ids() {\n"
                + "  [ -f \"$1\" ] || return 1\n"
                + "  command -v python3 >/dev/null 2>&1 || return 1\n"
                + "  python3 - \"$1\" <<'PY'\n"
                + "import json, os, string, sys\n"
                + "def read_json(path):\n"
                + "    with open(path, 'r', encoding='utf-8') as fh:\n"
                + "        return json.load(fh)\n"
                + "def normalized_hash(value):\n"
                + "    value = str(value or '').strip().lower()\n"
                + "    if value.startswith('sha256:'):\n"
                + "        value = value[7:]\n"
                + "    if len(value) != 64 or any(ch not in string.hexdigits for ch in value):\n"
                + "        return ''\n"
                + "    return 'sha256:' + value\n"
                + "try:\n"
                + "    config = read_json(sys.argv[1])\n"
                + "    admin = config.get('admin') if isinstance(config.get('admin'), dict) else {}\n"
                + "    state_path = str(admin.get('statePath') or '/var/lib/tgproxy-relay/state.json')\n"
                + "    state = read_json(state_path) if os.path.isfile(state_path) else {}\n"
                + "    revoked = {normalized_hash(value) for value in state.get('revokedHashes', [])}\n"
                + "    tokens = list(config.get('tokens') or []) + list(state.get('addedTokens') or [])\n"
                + "    ids = []\n"
                + "    for item in tokens:\n"
                + "        if not isinstance(item, dict):\n"
                + "            continue\n"
                + "        token_hash = normalized_hash(item.get('hash'))\n"
                + "        if not token_hash or token_hash in revoked:\n"
                + "            continue\n"
                + "        explicit = str(item.get('id') or '').strip()\n"
                + "        derived = 'cfg_' + token_hash[7:23]\n"
                + "        for candidate in (explicit, derived):\n"
                + "            if candidate and candidate not in ids:\n"
                + "                ids.append(candidate)\n"
                + "    print(','.join(ids))\n"
                + "except Exception:\n"
                + "    sys.exit(1)\n"
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
                + "EXISTING_TOKEN_IDS=\n"
                + "EXISTING_TOKEN_IDS_KNOWN=no\n"
                + "if [ \"$EXISTING_RELAY\" = yes ]; then\n"
                + "  if EXISTING_TOKEN_IDS=$(relay_token_ids \"$EXISTING_CONFIG\"); then\n"
                + "    EXISTING_TOKEN_IDS_KNOWN=yes\n"
                + "  fi\n"
                + "fi\n"
                + "EXISTING_PUBLIC_HOST=$(public_url_host \"$EXISTING_PUBLIC_URL\")\n"
                + "if [ -n \"$EXISTING_PUBLIC_HOST\" ]; then\n"
                + "  if [ -z \"$DOMAIN\" ] || is_ipv4 \"$DOMAIN\"; then\n"
                + "    DOMAIN=\"$EXISTING_PUBLIC_HOST\"\n"
                + "    RELAY_PATH=$(public_url_path \"$EXISTING_PUBLIC_URL\")\n"
                + "    [ -z \"$RELAY_PATH\" ] && RELAY_PATH=/apiws\n"
                + "  fi\n"
                + "fi\n"
                + "printf 'kernel=%s\\n' \"$(uname -s 2>/dev/null | tr '[:upper:]' '[:lower:]')\"\n"
                + "printf 'os=%s\\n' \"$(. /etc/os-release 2>/dev/null; echo ${PRETTY_NAME:-unknown})\"\n"
                + "printf 'arch=%s\\n' \"$(uname -m)\"\n"
                + "INIT_SYSTEM=$(init_system)\n"
                + "printf 'init_system=%s\\n' \"$INIT_SYSTEM\"\n"
                + "[ \"$INIT_SYSTEM\" = systemd ] && SYSTEMD=yes || SYSTEMD=no\n"
                + "printf 'systemd=%s\\n' \"$SYSTEMD\"\n"
                + "printf 'nginx=%s\\n' \"$(yn nginx)\"\n"
                + "printf 'apache=%s\\n' \"$(yn apache2)\"\n"
                + "printf 'caddy=%s\\n' \"$(yn caddy)\"\n"
                + "printf 'docker=%s\\n' \"$(yn docker)\"\n"
                + "printf 'ufw=%s\\n' \"$(yn ufw)\"\n"
                + "printf 'curl=%s\\n' \"$(yn curl)\"\n"
                + "printf 'wget=%s\\n' \"$(yn wget)\"\n"
                + "printf 'tar=%s\\n' \"$(yn tar)\"\n"
                + "printf 'python3=%s\\n' \"$(yn python3)\"\n"
                + "printf 'package_manager=%s\\n' \"$(package_manager)\"\n"
                + "printf 'root_or_passwordless_sudo=%s\\n' \"$(root_or_passwordless_sudo)\"\n"
                + "printf 'port_80=%s\\n' \"$(port_state 80)\"\n"
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
                + "NGINX_MATCHES=$(active_matches /etc/nginx/sites-enabled /etc/nginx/conf.d)\n"
                + "CADDY_MATCHES=$(active_matches /etc/caddy)\n"
                + "APACHE_MATCHES=$(active_matches /etc/apache2/sites-enabled /etc/httpd/conf.d)\n"
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
                + "printf 'existing_relay_token_ids_known=%s\\n' \"$EXISTING_TOKEN_IDS_KNOWN\"\n"
                + "printf 'existing_relay_token_ids=%s\\n' \"$EXISTING_TOKEN_IDS\"\n"
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

    static String backup(String transactionId) {
        return backup(transactionId, null, null);
    }

    static String backup(String transactionId, VpsSetupRequest request, VpsSetupPlan plan) {
        String transaction = safeTransactionId(transactionId);
        return "#!/bin/sh\n"
                + "set -eu\n"
                + sudoPrelude()
                + servicePrelude()
                + "$SUDO mkdir -p /var/backups/tgproxy-relay\n"
                + "BACKUP_DIR=" + shellQuote("/var/backups/tgproxy-relay/txn-" + transaction) + "\n"
                + "$SUDO test ! -e \"$BACKUP_DIR\" || { echo backup_transaction_exists >&2; exit 71; }\n"
                + "$SUDO mkdir \"$BACKUP_DIR\"\n"
                + "$SUDO chmod 0700 \"$BACKUP_DIR\"\n"
                + "$SUDO touch \"$BACKUP_DIR/path-map.tsv\"\n"
                + "$SUDO chmod 0600 \"$BACKUP_DIR/path-map.tsv\"\n"
                + "$SUDO touch \"$BACKUP_DIR/mutation-paths.txt\" \"$BACKUP_DIR/absent-paths.txt\"\n"
                + "$SUDO chmod 0600 \"$BACKUP_DIR/mutation-paths.txt\" \"$BACKUP_DIR/absent-paths.txt\"\n"
                + "if relay_service_is_enabled >/dev/null 2>&1; then $SUDO touch \"$BACKUP_DIR/service.was-enabled\"; fi\n"
                + "if relay_service_is_active >/dev/null 2>&1; then $SUDO touch \"$BACKUP_DIR/service.was-active\"; fi\n"
                + "if ! $SUDO test -d /opt/tgproxy-relay; then $SUDO touch \"$BACKUP_DIR/opt-dir.absent\"; fi\n"
                + "if ! $SUDO test -d /etc/tgproxy-relay; then $SUDO touch \"$BACKUP_DIR/etc-dir.absent\"; fi\n"
                + "if ! $SUDO test -d /var/log/tgproxy-relay; then $SUDO touch \"$BACKUP_DIR/log-dir.absent\"; fi\n"
                + "if ! $SUDO test -d /var/lib/tgproxy-relay; then $SUDO touch \"$BACKUP_DIR/state-dir.absent\"; else $SUDO cp -a /var/lib/tgproxy-relay \"$BACKUP_DIR/state-dir\"; fi\n"
                + "if ! $SUDO test -d /etc/systemd/system/tgproxy-relay.service.d; then $SUDO touch \"$BACKUP_DIR/service-dropin-dir.absent\"; fi\n"
                + "if ! id tgproxy-relay >/dev/null 2>&1; then $SUDO touch \"$BACKUP_DIR/user.absent\"; fi\n"
                + "if ! group_exists tgproxy-relay; then $SUDO touch \"$BACKUP_DIR/group.absent\"; fi\n"
                + "if $SUDO test -f /opt/tgproxy-relay/tgproxy-relay; then $SUDO cp -p /opt/tgproxy-relay/tgproxy-relay \"$BACKUP_DIR/tgproxy-relay\"; else $SUDO touch \"$BACKUP_DIR/binary.absent\"; fi\n"
                + "if $SUDO test -f /etc/tgproxy-relay/config.json; then $SUDO cp -p /etc/tgproxy-relay/config.json \"$BACKUP_DIR/config.json\"; else $SUDO touch \"$BACKUP_DIR/config.absent\"; fi\n"
                + "if $SUDO test -f /etc/systemd/system/tgproxy-relay.service; then $SUDO cp -p /etc/systemd/system/tgproxy-relay.service \"$BACKUP_DIR/tgproxy-relay.service\"; else $SUDO touch \"$BACKUP_DIR/service.absent\"; fi\n"
                + "if $SUDO test -f /etc/systemd/system/tgproxy-relay.service.d/20-owner-state.conf; then $SUDO cp -p /etc/systemd/system/tgproxy-relay.service.d/20-owner-state.conf \"$BACKUP_DIR/20-owner-state.conf\"; else $SUDO touch \"$BACKUP_DIR/service-dropin.absent\"; fi\n"
                + "if $SUDO test -f /etc/init.d/tgproxy-relay; then $SUDO cp -p /etc/init.d/tgproxy-relay \"$BACKUP_DIR/tgproxy-relay.init\"; else $SUDO touch \"$BACKUP_DIR/init-script.absent\"; fi\n"
                + "if $SUDO test -d /etc/sv/tgproxy-relay; then $SUDO cp -a /etc/sv/tgproxy-relay \"$BACKUP_DIR/runit-service\"; else $SUDO touch \"$BACKUP_DIR/runit-service.absent\"; fi\n"
                + "if $SUDO test -f /etc/cron.d/tgproxy-relay; then $SUDO cp -p /etc/cron.d/tgproxy-relay \"$BACKUP_DIR/tgproxy-relay.cron\"; else $SUDO touch \"$BACKUP_DIR/portable-cron.absent\"; fi\n"
                + "backup_path() {\n"
                + "  f=$1\n"
                + "  safe=path-$(printf '%s' \"$f\" | sha256sum | awk '{print $1}')\n"
                + "  $SUDO cp -p \"$f\" \"$BACKUP_DIR/$safe\"\n"
                + "  printf '%s\\t%s\\n' \"$safe\" \"$f\" | $SUDO tee -a \"$BACKUP_DIR/path-map.tsv\" >/dev/null\n"
                + "}\n"
                + "if command -v ufw >/dev/null 2>&1; then\n"
                + "  if $SUDO ufw status 2>/dev/null | grep -qi '^Status: active'; then $SUDO touch \"$BACKUP_DIR/ufw.was-active\"; fi\n"
                + "  for f in /etc/ufw/user.rules /etc/ufw/user6.rules; do\n"
                + "    printf '%s\\n' \"$f\" | $SUDO tee -a \"$BACKUP_DIR/mutation-paths.txt\" >/dev/null\n"
                + "    if $SUDO test -e \"$f\"; then backup_path \"$f\"; else printf '%s\\n' \"$f\" | $SUDO tee -a \"$BACKUP_DIR/absent-paths.txt\" >/dev/null; fi\n"
                + "  done\n"
                + "fi\n"
                + mutationTrackingScript(request, plan)
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
        boolean managedTlsMode = plan != null
                && plan.installMode() == VpsSetupPlan.InstallMode.NGINX_MANAGED_TLS;
        String version = request.releaseVersion().isEmpty() ? RELAY_VERSION : request.releaseVersion();
        String token = request.relayToken();
        String adminToken = request.adminToken();
        String archive = "TG-Proxy-Relay-v" + version + "-linux-${RELAY_ARCH}.tar.gz";
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/sh\n")
                .append("set -eu\n")
                .append(sudoPrelude())
                .append(servicePrelude())
                .append("TOKEN=").append(shellQuote(token)).append('\n')
                .append("ADMIN_TOKEN=").append(shellQuote(adminToken)).append('\n')
                .append("PUBLIC_URL=").append(shellQuote(request.publicUrl())).append('\n')
                .append("DOMAIN=").append(shellQuote(request.relayHost())).append('\n')
                .append("RELAY_PATH=").append(shellQuote(request.relayPath())).append('\n')
                .append("INTERNAL_RELAY_PORT=").append(request.internalRelayPort()).append('\n')
                .append("LISTEN=").append(shellQuote(request.relayListenAddress())).append('\n')
                .append("VERSION=").append(shellQuote(version)).append('\n')
                .append(architectureSelection())
                .append("ARCHIVE=\"").append(archive).append("\"\n")
                .append("URL=\"").append(RELEASE_BASE).append("/v${VERSION}/${ARCHIVE}\"\n")
                .append("CHECKSUM_URL=\"").append(RELEASE_BASE).append("/v${VERSION}/SHA256SUMS.txt\"\n")
                .append("TMPDIR=$(mktemp -d)\n")
                .append("cleanup() { rm -rf \"$TMPDIR\"; }\n")
                .append("trap cleanup EXIT\n");
        if (dockerCaddyMode) {
            script.append(dockerCaddyPrelude(plan.targetPath(), plan.targetContainer()));
        }
        script.append(ensureBaseDependencies())
                .append("if command -v curl >/dev/null 2>&1; then curl -fsSL \"$URL\" -o \"$TMPDIR/$ARCHIVE\"; curl -fsSL \"$CHECKSUM_URL\" -o \"$TMPDIR/SHA256SUMS.txt\";\n")
                .append("elif command -v wget >/dev/null 2>&1; then wget -qO \"$TMPDIR/$ARCHIVE\" \"$URL\"; wget -qO \"$TMPDIR/SHA256SUMS.txt\" \"$CHECKSUM_URL\";\n")
                .append("else echo curl_or_wget_required >&2; exit 43; fi\n")
                .append("command -v sha256sum >/dev/null 2>&1 || { echo sha256sum_required >&2; exit 43; }\n")
                .append("(cd \"$TMPDIR\" && grep -E \"[ *]${ARCHIVE}$\" SHA256SUMS.txt | sha256sum -c -) || { echo relay_checksum_failed >&2; exit 44; }\n")
                .append("tar -xzf \"$TMPDIR/$ARCHIVE\" -C \"$TMPDIR\"\n")
                .append("[ \"$(\"$TMPDIR/tgproxy-relay\" -version)\" = \"$VERSION\" ] || { echo relay_version_mismatch >&2; exit 45; }\n")
                .append("$SUDO install -d -m 0755 /opt/tgproxy-relay /etc/tgproxy-relay /var/log/tgproxy-relay\n")
                .append(ensureServiceAccount())
                .append("$SUDO install -m 0755 \"$TMPDIR/tgproxy-relay\" /opt/tgproxy-relay/tgproxy-relay\n")
                .append("$SUDO chown -R tgproxy-relay:tgproxy-relay /var/log/tgproxy-relay || true\n")
                .append("$SUDO install -d -m 0750 -o tgproxy-relay -g tgproxy-relay /var/lib/tgproxy-relay\n")
                .append("TOKEN_HASH=sha256:$(printf '%s' \"$TOKEN\" | sha256sum | awk '{print $1}')\n")
                .append("ADMIN_HASH=sha256:$(printf '%s' \"$ADMIN_TOKEN\" | sha256sum | awk '{print $1}')\n")
                .append("$SUDO sh -c 'cat > /etc/tgproxy-relay/config.json' <<EOF\n")
                .append("{\n")
                .append("  \"listen\": \"$LISTEN\",\n")
                .append("  \"publicUrl\": \"$PUBLIC_URL\",\n")
                .append("  \"tokens\": [{\"name\": \"phone\", \"hash\": \"$TOKEN_HASH\"}],\n")
                .append("  \"admin\": {\"tokens\": [{\"name\": \"owner\", \"hash\": \"$ADMIN_HASH\"}], \"statePath\": \"/var/lib/tgproxy-relay/state.json\", \"geoIpUrl\": \"https://ipwho.is/%s?lang=ru&fields=success,country,city\"},\n")
                .append("  \"telegram\": {\n")
                .append("    \"connectTimeoutMs\": 7000,\n")
                .append("    \"idleTimeoutSec\": 0,\n")
                .append("    \"dcMap\": ").append(RELAY_DC_MAP_JSON).append(",\n")
                .append("    \"testDcMap\": ").append(RELAY_TEST_DC_MAP_JSON).append("\n")
                .append("  },\n")
                .append("  \"websocket\": {\n")
                .append("    \"path\": \"$RELAY_PATH\",\n")
                .append("    \"pingIntervalSec\": 25,\n")
                .append("    \"pongTimeoutSec\": 12,\n")
                .append("    \"writeTimeoutSec\": 15,\n")
                .append("    \"maxMessageBytes\": 16777216\n")
                .append("  }\n")
                .append("}\n")
                .append("EOF\n")
                .append("$SUDO chmod 0640 /etc/tgproxy-relay/config.json\n")
                .append("$SUDO chown root:tgproxy-relay /etc/tgproxy-relay/config.json || $SUDO chmod 0644 /etc/tgproxy-relay/config.json\n")
                .append("$SUDO /opt/tgproxy-relay/tgproxy-relay -config /etc/tgproxy-relay/config.json -check-config >/dev/null\n")
                .append(serviceDefinitionScript());
        if (request.reverseProxyMode()) {
            if (managedTlsMode) {
                script.append(nginxManagedTlsConfig(request));
            } else if (dockerCaddyMode) {
                script.append(dockerCaddyExistingSiteConfig(request));
            } else if (hostCaddyMode) {
                script.append(caddyExistingSiteConfig(request, plan.targetPath()));
            } else if (plan != null && plan.installMode() == VpsSetupPlan.InstallMode.NGINX_EXISTING_LOCATION) {
                script.append(nginxExistingLocationConfig(request, plan.targetPath()));
            } else {
                script.append(nginxReverseProxyConfig(request));
            }
        }
        script.append(managedTlsMode ? "cert_renewal_enable\n" : "")
                .append("relay_service_enable\n")
                .append("relay_service_restart\n")
                .append("sleep 1\n")
                .append("relay_service_is_active || { echo relay_start_failed >&2; exit 70; }\n")
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
        String capabilitiesPath = managementPath(request.relayPath(), "/capabilities");
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
                + "    '\\thandle " + capabilitiesPath + " {\\n',\n"
                + "    '\\t\\trewrite * /capabilities\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "    '\\thandle " + testRoutesPath + " {\\n',\n"
                + "    '\\t\\trewrite * /test-routes\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "    '\\thandle ' + relay_path + '* {\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + ' {\\n',\n"
                + "    '\\t\\t\\tflush_interval -1\\n',\n"
                + "    '\\t\\t\\tstream_timeout 0\\n',\n"
                + "    '\\t\\t}\\n',\n"
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
        String capabilitiesPath = managementPath(request.relayPath(), "/capabilities");
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
                + "    '\\thandle " + capabilitiesPath + " {\\n',\n"
                + "    '\\t\\trewrite * /capabilities\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "    '\\thandle " + testRoutesPath + " {\\n',\n"
                + "    '\\t\\trewrite * /test-routes\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + '\\n',\n"
                + "    '\\t}\\n',\n"
                + "    '\\thandle ' + relay_path + '* {\\n',\n"
                + "    '\\t\\treverse_proxy ' + upstream + ' {\\n',\n"
                + "    '\\t\\t\\tflush_interval -1\\n',\n"
                + "    '\\t\\t\\tstream_timeout 0\\n',\n"
                + "    '\\t\\t}\\n',\n"
                + "    '\\t}\\n',\n"
                + "]\n"
                + "out = lines[:start + 1] + block + lines[start + 1:]\n"
                + "with open(tmp, 'w', encoding='utf-8') as fh:\n"
                + "    fh.writelines(out)\n"
                + "PY\n"
                + "$SUDO install -m 0644 \"$CADDY_TMP\" \"$CADDY_TARGET\"\n"
                + "restore_caddy() { $SUDO install -m 0644 \"$CADDY_ORIGINAL\" \"$CADDY_TARGET\" >/dev/null 2>&1 || true; caddy_service_reload \"$CADDY_TARGET\" >/dev/null 2>&1 || true; }\n"
                + "$SUDO caddy validate --config \"$CADDY_TARGET\" || { restore_caddy; exit 66; }\n"
                + "caddy_service_reload \"$CADDY_TARGET\" || { restore_caddy; exit 67; }\n";
    }

    private static String addTokenToExistingRelay(VpsSetupRequest request, VpsSetupPlan plan) {
        String configPath = plan.targetPath().isEmpty()
                ? "/etc/tgproxy-relay/config.json"
                : plan.targetPath();
        return "#!/bin/sh\n"
                + "set -eu\n"
                + sudoPrelude()
                + servicePrelude()
                + "TOKEN=" + shellQuote(request.relayToken()) + "\n"
                + "ADMIN_TOKEN=" + shellQuote(request.adminToken()) + "\n"
                + "EXISTING_CONFIG=" + shellQuote(configPath) + "\n"
                + "DOMAIN=" + shellQuote(request.relayHost()) + "\n"
                + "RELAY_PATH=" + shellQuote(request.relayPath()) + "\n"
                + "INTERNAL_RELAY_PORT=" + request.internalRelayPort() + "\n"
                + "[ -f \"$EXISTING_CONFIG\" ] || { echo existing_relay_config_missing >&2; exit 51; }\n"
                + "[ -x /opt/tgproxy-relay/tgproxy-relay ] || { echo tgproxy_relay_binary_missing >&2; exit 52; }\n"
                + "command -v python3 >/dev/null 2>&1 || { echo python3_required >&2; exit 53; }\n"
                + "command -v sha256sum >/dev/null 2>&1 || { echo sha256sum_required >&2; exit 43; }\n"
                + "TOKEN_HASH=sha256:$(printf '%s' \"$TOKEN\" | sha256sum | awk '{print $1}')\n"
                + "ADMIN_HASH=sha256:$(printf '%s' \"$ADMIN_TOKEN\" | sha256sum | awk '{print $1}')\n"
                + "TMP_CONFIG=$(mktemp)\n"
                + "TMPDIR=$(mktemp -d)\n"
                + "cleanup() { rm -f \"$TMP_CONFIG\"; rm -rf \"$TMPDIR\"; }\n"
                + "trap cleanup EXIT\n"
	                + "$SUDO env TOKEN_HASH=\"$TOKEN_HASH\" ADMIN_HASH=\"$ADMIN_HASH\" EXISTING_CONFIG=\"$EXISTING_CONFIG\" TMP_CONFIG=\"$TMP_CONFIG\" python3 - <<'PY'\n"
                + "import json, os, time\n"
                + "path = os.environ['EXISTING_CONFIG']\n"
                + "tmp = os.environ['TMP_CONFIG']\n"
                + "token_hash = os.environ['TOKEN_HASH']\n"
                + "admin_hash = os.environ['ADMIN_HASH']\n"
                + "with open(path, 'r', encoding='utf-8') as fh:\n"
                + "    cfg = json.load(fh)\n"
                + "tokens = cfg.setdefault('tokens', [])\n"
                + "if not isinstance(tokens, list):\n"
                + "    raise SystemExit('invalid_tokens')\n"
                + "exists = any(isinstance(item, dict) and item.get('hash') == token_hash for item in tokens)\n"
                + "if not exists:\n"
                + "    tokens.append({'name': 'phone-' + time.strftime('%Y%m%d%H%M%S'), 'hash': token_hash})\n"
                + ownerConfigPythonRepair()
                + "with open(tmp, 'w', encoding='utf-8') as fh:\n"
                + "    json.dump(cfg, fh, indent=2, ensure_ascii=False)\n"
                + "    fh.write('\\n')\n"
                + "PY\n"
                + "$SUDO /opt/tgproxy-relay/tgproxy-relay -config \"$TMP_CONFIG\" -check-config >/dev/null\n"
                + "$SUDO cp -p \"$EXISTING_CONFIG\" \"$TMPDIR/config.previous\"\n"
                + "$SUDO install -m 0640 \"$TMP_CONFIG\" \"$EXISTING_CONFIG\"\n"
                + existingConfigPermissions()
                + ownerRuntimePermissions()
                + "rollback_config() { $SUDO install -m 0640 \"$TMPDIR/config.previous\" \"$EXISTING_CONFIG\"; "
                + existingConfigPermissions().replace("\n", "; ")
                + "relay_service_restart >/dev/null 2>&1 || true; }\n"
                + "if ! relay_service_restart; then rollback_config; echo relay_restart_failed_rolled_back >&2; exit 70; fi\n"
                + "sleep 1\n"
                + "if ! relay_service_is_active; then rollback_config; echo relay_restart_failed_rolled_back >&2; exit 70; fi\n"
                + routeRepairScript(request, plan);
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
                + servicePrelude()
                + "TOKEN=" + shellQuote(request.relayToken()) + "\n"
                + "ADMIN_TOKEN=" + shellQuote(request.adminToken()) + "\n"
                + "EXISTING_CONFIG=" + shellQuote(configPath) + "\n"
                + "PUBLIC_URL=" + shellQuote(request.publicUrl()) + "\n"
                + "DOMAIN=" + shellQuote(request.relayHost()) + "\n"
                + "RELAY_PATH=" + shellQuote(request.relayPath()) + "\n"
                + "INTERNAL_RELAY_PORT=" + request.internalRelayPort() + "\n"
                + "VERSION=" + shellQuote(version) + "\n"
                + "[ -f \"$EXISTING_CONFIG\" ] || { echo existing_relay_config_missing >&2; exit 51; }\n"
                + "command -v python3 >/dev/null 2>&1 || { echo python3_required >&2; exit 53; }\n"
                + architectureSelection()
                + "ARCHIVE=\"" + archive + "\"\n"
                + "URL=\"" + RELEASE_BASE + "/v${VERSION}/${ARCHIVE}\"\n"
                + "CHECKSUM_URL=\"" + RELEASE_BASE + "/v${VERSION}/SHA256SUMS.txt\"\n"
                + "TMPDIR=$(mktemp -d)\n"
                + "TMP_CONFIG=$(mktemp)\n"
                + "cleanup() { rm -rf \"$TMPDIR\"; rm -f \"$TMP_CONFIG\"; }\n"
                + "trap cleanup EXIT\n"
                + "if command -v curl >/dev/null 2>&1; then curl -fsSL \"$URL\" -o \"$TMPDIR/$ARCHIVE\"; curl -fsSL \"$CHECKSUM_URL\" -o \"$TMPDIR/SHA256SUMS.txt\";\n"
                + "elif command -v wget >/dev/null 2>&1; then wget -qO \"$TMPDIR/$ARCHIVE\" \"$URL\"; wget -qO \"$TMPDIR/SHA256SUMS.txt\" \"$CHECKSUM_URL\";\n"
                + "else echo curl_or_wget_required >&2; exit 43; fi\n"
                + "command -v sha256sum >/dev/null 2>&1 || { echo sha256sum_required >&2; exit 43; }\n"
                + "(cd \"$TMPDIR\" && grep -E \"[ *]${ARCHIVE}$\" SHA256SUMS.txt | sha256sum -c -) || { echo relay_checksum_failed >&2; exit 44; }\n"
                + "tar -xzf \"$TMPDIR/$ARCHIVE\" -C \"$TMPDIR\"\n"
                + "[ \"$(\"$TMPDIR/tgproxy-relay\" -version)\" = \"$VERSION\" ] || { echo relay_version_mismatch >&2; exit 45; }\n"
                + "TOKEN_HASH=sha256:$(printf '%s' \"$TOKEN\" | sha256sum | awk '{print $1}')\n"
                + "ADMIN_HASH=sha256:$(printf '%s' \"$ADMIN_TOKEN\" | sha256sum | awk '{print $1}')\n"
	                + "$SUDO env TOKEN_HASH=\"$TOKEN_HASH\" ADMIN_HASH=\"$ADMIN_HASH\" RELAY_PATH=\"$RELAY_PATH\" PUBLIC_URL=\"$PUBLIC_URL\" EXISTING_CONFIG=\"$EXISTING_CONFIG\" TMP_CONFIG=\"$TMP_CONFIG\" python3 - <<'PY'\n"
                + "import json, os, time\n"
                + "path = os.environ['EXISTING_CONFIG']\n"
                + "tmp = os.environ['TMP_CONFIG']\n"
                + "token_hash = os.environ['TOKEN_HASH']\n"
                + "admin_hash = os.environ['ADMIN_HASH']\n"
                + "with open(path, 'r', encoding='utf-8') as fh:\n"
                + "    cfg = json.load(fh)\n"
                + "tokens = cfg.setdefault('tokens', [])\n"
                + "if not isinstance(tokens, list):\n"
                + "    raise SystemExit('invalid_tokens')\n"
                + "exists = any(isinstance(item, dict) and item.get('hash') == token_hash for item in tokens)\n"
                + "if not exists:\n"
                + "    tokens.append({'name': 'phone-' + time.strftime('%Y%m%d%H%M%S'), 'hash': token_hash})\n"
                + ownerConfigPythonRepair()
                + relayDcMapPythonRepair()
                + "with open(tmp, 'w', encoding='utf-8') as fh:\n"
                + "    json.dump(cfg, fh, indent=2, ensure_ascii=False)\n"
                + "    fh.write('\\n')\n"
                + "PY\n"
                + "\"$TMPDIR/tgproxy-relay\" -config \"$TMP_CONFIG\" -check-config >/dev/null\n"
                + "$SUDO install -d -m 0755 /opt/tgproxy-relay\n"
                + "[ ! -f /opt/tgproxy-relay/tgproxy-relay ] || $SUDO cp -p /opt/tgproxy-relay/tgproxy-relay \"$TMPDIR/tgproxy-relay.previous\"\n"
                + "$SUDO cp -p \"$EXISTING_CONFIG\" \"$TMPDIR/config.previous\"\n"
                + "$SUDO install -m 0755 \"$TMPDIR/tgproxy-relay\" /opt/tgproxy-relay/tgproxy-relay\n"
                + "$SUDO install -m 0640 \"$TMP_CONFIG\" \"$EXISTING_CONFIG\"\n"
                + existingConfigPermissions()
                + ownerRuntimePermissions()
                + "rollback_relay() { [ ! -f \"$TMPDIR/tgproxy-relay.previous\" ] || $SUDO install -m 0755 \"$TMPDIR/tgproxy-relay.previous\" /opt/tgproxy-relay/tgproxy-relay; $SUDO install -m 0640 \"$TMPDIR/config.previous\" \"$EXISTING_CONFIG\"; "
                + existingConfigPermissions().replace("\n", "; ")
                + "relay_service_restart >/dev/null 2>&1 || true; }\n"
                + "if ! relay_service_restart; then rollback_relay; echo relay_restart_failed_rolled_back >&2; exit 70; fi\n"
                + "sleep 1\n"
                + "if ! relay_service_is_active; then rollback_relay; echo relay_restart_failed_rolled_back >&2; exit 70; fi\n"
                + routeRepairScript(request, plan);
    }

    private static String existingConfigPermissions() {
        return "$SUDO chmod 0640 \"$EXISTING_CONFIG\"\n"
                + "$SUDO chown root:tgproxy-relay \"$EXISTING_CONFIG\" || $SUDO chmod 0644 \"$EXISTING_CONFIG\"\n";
    }

    private static String ownerConfigPythonRepair() {
        return "admin = cfg.setdefault('admin', {})\n"
                + "if not isinstance(admin, dict):\n"
                + "    raise SystemExit('invalid_admin_config')\n"
                + "admin_tokens = admin.setdefault('tokens', [])\n"
                + "if not isinstance(admin_tokens, list):\n"
                + "    raise SystemExit('invalid_admin_tokens')\n"
                + "admin_exists = any(isinstance(item, dict) and item.get('hash') == admin_hash for item in admin_tokens)\n"
                + "if not admin_exists:\n"
                + "    admin_tokens.append({'name': 'owner-' + time.strftime('%Y%m%d%H%M%S'), 'hash': admin_hash})\n"
                + "admin['statePath'] = '/var/lib/tgproxy-relay/state.json'\n"
                + "admin.setdefault('geoIpUrl', 'https://ipwho.is/%s?lang=ru&fields=success,country,city')\n";
    }

    private static String ownerRuntimePermissions() {
        return "$SUDO install -d -m 0750 -o tgproxy-relay -g tgproxy-relay /var/lib/tgproxy-relay\n"
                + "if [ \"$INIT_SYSTEM\" = systemd ]; then\n"
                + "$SUDO install -d -m 0755 /etc/systemd/system/tgproxy-relay.service.d\n"
                + "$SUDO tee /etc/systemd/system/tgproxy-relay.service.d/20-owner-state.conf >/dev/null <<'EOF'\n"
                + "[Unit]\n"
                + "StartLimitIntervalSec=0\n"
                + "\n"
                + "[Service]\n"
                + "Restart=always\n"
                + "RestartSec=2s\n"
                + "TimeoutStopSec=15s\n"
                + "LimitNOFILE=65536\n"
                + "ReadWritePaths=/var/lib/tgproxy-relay\n"
                + "EOF\n"
                + "$SUDO systemctl daemon-reload\n"
                + "fi\n";
    }

    private static String relayDcMapPythonRepair() {
        return "relay_dc_map = {\n"
                + "    '1': '149.154.175.50',\n"
                + "    '2': '149.154.167.51',\n"
                + "    '3': '149.154.175.100',\n"
                + "    '4': '149.154.167.91',\n"
                + "    '5': '149.154.171.5',\n"
                + "    '203': '91.105.192.100',\n"
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
                + "telegram['dcMap'] = dc_map\n"
                + "relay_test_dc_map = {\n"
                + "    '1': '149.154.175.10',\n"
                + "    '2': '149.154.167.40',\n"
                + "    '3': '149.154.175.117',\n"
                + "}\n"
                + "test_dc_map = telegram.get('testDcMap')\n"
                + "if not isinstance(test_dc_map, dict):\n"
                + "    test_dc_map = {}\n"
                + "for dc, ip in relay_test_dc_map.items():\n"
                + "    if not str(test_dc_map.get(dc, '')).strip():\n"
                + "        test_dc_map[dc] = ip\n"
                + "telegram['testDcMap'] = test_dc_map\n"
                + "def as_int(value):\n"
                + "    try:\n"
                + "        return int(value or 0)\n"
                + "    except (TypeError, ValueError):\n"
                + "        return 0\n"
                + "if as_int(telegram.get('idleTimeoutSec')) <= 125:\n"
                + "    telegram['idleTimeoutSec'] = 0\n"
                + "websocket = cfg.setdefault('websocket', {})\n"
                + "if not isinstance(websocket, dict):\n"
                + "    raise SystemExit('invalid_websocket_config')\n"
                + "websocket['path'] = os.environ.get('RELAY_PATH', '/apiws')\n"
                + "public_url = os.environ.get('PUBLIC_URL', '').strip()\n"
                + "if public_url:\n"
                + "    cfg['publicUrl'] = public_url\n"
                + "if as_int(websocket.get('pingIntervalSec')) <= 0:\n"
                + "    websocket['pingIntervalSec'] = 25\n"
                + "if as_int(websocket.get('pongTimeoutSec')) <= 0:\n"
                + "    websocket['pongTimeoutSec'] = 12\n"
                + "if as_int(websocket.get('writeTimeoutSec')) <= 0:\n"
                + "    websocket['writeTimeoutSec'] = 15\n"
                + "if as_int(websocket.get('maxMessageBytes')) < 65536:\n"
                + "    websocket['maxMessageBytes'] = 16777216\n";
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

    private static String mutationTrackingScript(VpsSetupRequest request, VpsSetupPlan plan) {
        if (request == null || plan == null) return "";
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        collectMutationPaths(paths, request, plan.installMode(), plan.targetPath());
        if (plan.hasRouteRepair()) {
            VpsSetupRequest routeRequest = plan.routeRepairRequest() == null
                    ? request : plan.routeRepairRequest();
            collectMutationPaths(paths, routeRequest, plan.routeRepairMode(),
                    plan.routeTargetPath());
        }
        StringBuilder script = new StringBuilder();
        script.append("track_mutation() {\n")
                .append("  f=$1\n")
                .append("  printf '%s\\n' \"$f\" | $SUDO tee -a \"$BACKUP_DIR/mutation-paths.txt\" >/dev/null\n")
                .append("  if $SUDO test -e \"$f\"; then backup_path \"$f\"; else printf '%s\\n' \"$f\" | $SUDO tee -a \"$BACKUP_DIR/absent-paths.txt\" >/dev/null; fi\n")
                .append("}\n");
        for (String path : paths) {
            if (path != null && !path.trim().isEmpty()) {
                script.append("track_mutation ").append(shellQuote(path.trim())).append('\n');
            }
        }
        if (usesMode(plan, VpsSetupPlan.InstallMode.NGINX_MANAGED_TLS)
                || usesMode(plan, VpsSetupPlan.InstallMode.NGINX_NEW_SERVER)
                || usesMode(plan, VpsSetupPlan.InstallMode.NGINX_EXISTING_LOCATION)) {
            script.append("$SUDO touch \"$BACKUP_DIR/reload-nginx\"\n");
        }
        if (usesMode(plan, VpsSetupPlan.InstallMode.NGINX_MANAGED_TLS)) {
            script.append("$SUDO touch \"$BACKUP_DIR/managed-tls\"\n");
        }
        if (usesMode(plan, VpsSetupPlan.InstallMode.CADDY_EXISTING_SITE)) {
            String target = plan.installMode() == VpsSetupPlan.InstallMode.CADDY_EXISTING_SITE
                    ? plan.targetPath() : plan.routeTargetPath();
            script.append("printf '%s\\n' ").append(shellQuote(target))
                    .append(" | $SUDO tee \"$BACKUP_DIR/reload-caddy-target\" >/dev/null\n");
        }
        if (usesMode(plan, VpsSetupPlan.InstallMode.DOCKER_CADDY_EXISTING_SITE)) {
            String container = plan.installMode()
                    == VpsSetupPlan.InstallMode.DOCKER_CADDY_EXISTING_SITE
                    ? plan.targetContainer() : plan.routeTargetContainer();
            script.append("printf '%s\\n' ").append(shellQuote(container))
                    .append(" | $SUDO tee \"$BACKUP_DIR/reload-docker-caddy-container\" >/dev/null\n");
        }
        return script.toString();
    }

    private static String architectureSelection() {
        return "case \"$(uname -m)\" in\n"
                + "  x86_64|amd64) RELAY_ARCH=amd64 ;;\n"
                + "  i386|i486|i586|i686|x86) RELAY_ARCH=386 ;;\n"
                + "  aarch64|arm64) RELAY_ARCH=arm64 ;;\n"
                + "  armv5*) RELAY_ARCH=armv5 ;;\n"
                + "  armv6*) RELAY_ARCH=armv6 ;;\n"
                + "  armv7*|armv8l) RELAY_ARCH=armv7 ;;\n"
                + "  riscv64) RELAY_ARCH=riscv64 ;;\n"
                + "  ppc64) RELAY_ARCH=ppc64 ;;\n"
                + "  ppc64le) RELAY_ARCH=ppc64le ;;\n"
                + "  s390x) RELAY_ARCH=s390x ;;\n"
                + "  loong64|loongarch64) RELAY_ARCH=loong64 ;;\n"
                + "  mips) RELAY_ARCH=mips ;;\n"
                + "  mipsel|mipsle) RELAY_ARCH=mipsle ;;\n"
                + "  mips64) RELAY_ARCH=mips64 ;;\n"
                + "  mips64el|mips64le) RELAY_ARCH=mips64le ;;\n"
                + "  *) echo unsupported_arch >&2; exit 42 ;;\n"
                + "esac\n";
    }

    /** POSIX service abstraction used by systemd, OpenRC, runit, SysV, and minimal Linux. */
    private static String servicePrelude() {
        return "detect_init() {\n"
                + "  if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then echo systemd; return; fi\n"
                + "  if command -v rc-service >/dev/null 2>&1 && command -v rc-update >/dev/null 2>&1; then echo openrc; return; fi\n"
                + "  if command -v sv >/dev/null 2>&1 && { [ -d /etc/sv ] || [ -d /var/service ] || [ -d /etc/service ]; }; then echo runit; return; fi\n"
                + "  if [ -d /etc/init.d ]; then echo sysv; return; fi\n"
                + "  echo portable\n"
                + "}\n"
                + "INIT_SYSTEM=$(detect_init)\n"
                + "group_exists() { command -v getent >/dev/null 2>&1 && getent group \"$1\" >/dev/null 2>&1 && return 0; grep -q \"^$1:\" /etc/group 2>/dev/null; }\n"
                + "runit_service_root() { [ -d /var/service ] && { echo /var/service; return; }; [ -d /etc/service ] && { echo /etc/service; return; }; echo /var/service; }\n"
                + "relay_service_is_active() {\n"
                + "  case \"$INIT_SYSTEM\" in\n"
                + "    systemd) $SUDO systemctl is-active --quiet tgproxy-relay ;;\n"
                + "    openrc) $SUDO rc-service tgproxy-relay status >/dev/null 2>&1 ;;\n"
                + "    runit) $SUDO sv status tgproxy-relay 2>/dev/null | grep -q '^run:' ;;\n"
                + "    *) $SUDO /etc/init.d/tgproxy-relay status >/dev/null 2>&1 ;;\n"
                + "  esac\n"
                + "}\n"
                + "relay_service_is_enabled() {\n"
                + "  case \"$INIT_SYSTEM\" in\n"
                + "    systemd) $SUDO systemctl is-enabled --quiet tgproxy-relay ;;\n"
                + "    openrc) $SUDO rc-update show default 2>/dev/null | grep -Eq '(^|[[:space:]])tgproxy-relay([[:space:]]|$)' ;;\n"
                + "    runit) root=$(runit_service_root); [ -L \"$root/tgproxy-relay\" ] ;;\n"
                + "    sysv) find /etc/rc.d /etc/rc?.d -type l -name 'S*tgproxy-relay' 2>/dev/null | grep -q . ;;\n"
                + "    portable) [ -f /etc/cron.d/tgproxy-relay ] ;;\n"
                + "  esac\n"
                + "}\n"
                + "relay_service_start() {\n"
                + "  case \"$INIT_SYSTEM\" in\n"
                + "    systemd) $SUDO systemctl start tgproxy-relay ;;\n"
                + "    openrc) $SUDO rc-service tgproxy-relay start ;;\n"
                + "    runit) $SUDO sv up tgproxy-relay ;;\n"
                + "    sysv) command -v service >/dev/null 2>&1 && $SUDO service tgproxy-relay start || $SUDO /etc/init.d/tgproxy-relay start ;;\n"
                + "    portable) $SUDO /etc/init.d/tgproxy-relay start ;;\n"
                + "  esac\n"
                + "}\n"
                + "relay_service_stop() {\n"
                + "  case \"$INIT_SYSTEM\" in\n"
                + "    systemd) $SUDO systemctl stop tgproxy-relay ;;\n"
                + "    openrc) $SUDO rc-service tgproxy-relay stop ;;\n"
                + "    runit) $SUDO sv down tgproxy-relay ;;\n"
                + "    sysv) command -v service >/dev/null 2>&1 && $SUDO service tgproxy-relay stop || $SUDO /etc/init.d/tgproxy-relay stop ;;\n"
                + "    portable) $SUDO /etc/init.d/tgproxy-relay stop ;;\n"
                + "  esac\n"
                + "}\n"
                + "relay_service_restart() {\n"
                + "  case \"$INIT_SYSTEM\" in\n"
                + "    systemd) $SUDO systemctl restart tgproxy-relay ;;\n"
                + "    openrc) $SUDO rc-service tgproxy-relay restart ;;\n"
                + "    runit) $SUDO sv restart tgproxy-relay ;;\n"
                + "    sysv) command -v service >/dev/null 2>&1 && $SUDO service tgproxy-relay restart || $SUDO /etc/init.d/tgproxy-relay restart ;;\n"
                + "    portable) $SUDO /etc/init.d/tgproxy-relay restart ;;\n"
                + "  esac\n"
                + "}\n"
                + "relay_service_enable() {\n"
                + "  case \"$INIT_SYSTEM\" in\n"
                + "    systemd) $SUDO systemctl daemon-reload; $SUDO systemctl enable --now tgproxy-relay ;;\n"
                + "    openrc) $SUDO rc-update add tgproxy-relay default >/dev/null 2>&1 || true ;;\n"
                + "    runit) root=$(runit_service_root); $SUDO mkdir -p \"$root\"; $SUDO ln -sfn /etc/sv/tgproxy-relay \"$root/tgproxy-relay\" ;;\n"
                + "    sysv) if command -v update-rc.d >/dev/null 2>&1; then $SUDO update-rc.d tgproxy-relay defaults; elif command -v chkconfig >/dev/null 2>&1; then $SUDO chkconfig --add tgproxy-relay; fi ;;\n"
                + "    portable) $SUDO install -d -m 0755 /etc/cron.d; printf '%s\\n' '@reboot root /etc/init.d/tgproxy-relay start >/dev/null 2>&1' | $SUDO tee /etc/cron.d/tgproxy-relay >/dev/null; $SUDO chmod 0644 /etc/cron.d/tgproxy-relay ;;\n"
                + "  esac\n"
                + "}\n"
                + "relay_service_disable() {\n"
                + "  case \"$INIT_SYSTEM\" in\n"
                + "    systemd) $SUDO systemctl disable --now tgproxy-relay >/dev/null 2>&1 || true ;;\n"
                + "    openrc) $SUDO rc-update del tgproxy-relay default >/dev/null 2>&1 || true; $SUDO rc-service tgproxy-relay stop >/dev/null 2>&1 || true ;;\n"
                + "    runit) $SUDO sv down tgproxy-relay >/dev/null 2>&1 || true; root=$(runit_service_root); $SUDO rm -f \"$root/tgproxy-relay\" ;;\n"
                + "    sysv) command -v update-rc.d >/dev/null 2>&1 && $SUDO update-rc.d -f tgproxy-relay remove >/dev/null 2>&1 || true; command -v chkconfig >/dev/null 2>&1 && $SUDO chkconfig --del tgproxy-relay >/dev/null 2>&1 || true ;;\n"
                + "    portable) $SUDO rm -f /etc/cron.d/tgproxy-relay ;;\n"
                + "  esac\n"
                + "}\n"
                + "nginx_service_start() {\n"
                + "  case \"$INIT_SYSTEM\" in\n"
                + "    systemd) $SUDO systemctl enable --now nginx ;;\n"
                + "    openrc) $SUDO rc-update add nginx default >/dev/null 2>&1 || true; $SUDO rc-service nginx start >/dev/null 2>&1 || $SUDO rc-service nginx restart ;;\n"
                + "    *) if [ -x /etc/init.d/nginx ]; then $SUDO /etc/init.d/nginx start >/dev/null 2>&1 || $SUDO /etc/init.d/nginx restart; elif command -v service >/dev/null 2>&1; then $SUDO service nginx start; else $SUDO nginx; fi ;;\n"
                + "  esac\n"
                + "}\n"
                + "nginx_service_reload() {\n"
                + "  case \"$INIT_SYSTEM\" in\n"
                + "    systemd) $SUDO systemctl reload nginx ;;\n"
                + "    openrc) $SUDO rc-service nginx reload >/dev/null 2>&1 || $SUDO rc-service nginx restart ;;\n"
                + "    *) $SUDO nginx -s reload >/dev/null 2>&1 || { [ -x /etc/init.d/nginx ] && $SUDO /etc/init.d/nginx reload; } ;;\n"
                + "  esac\n"
                + "}\n"
                + "caddy_service_reload() {\n"
                + "  config=$1\n"
                + "  $SUDO caddy reload --config \"$config\" >/dev/null 2>&1 && return 0\n"
                + "  case \"$INIT_SYSTEM\" in\n"
                + "    systemd) $SUDO systemctl reload caddy ;;\n"
                + "    openrc) $SUDO rc-service caddy reload >/dev/null 2>&1 || $SUDO rc-service caddy restart ;;\n"
                + "    *) [ -x /etc/init.d/caddy ] && { $SUDO /etc/init.d/caddy reload >/dev/null 2>&1 || $SUDO /etc/init.d/caddy restart; } ;;\n"
                + "  esac\n"
                + "}\n"
                + "cert_renewal_enable() {\n"
                + "  if [ \"$INIT_SYSTEM\" = systemd ]; then $SUDO systemctl daemon-reload; $SUDO systemctl enable --now tgproxy-certbot-renew.timer; return; fi\n"
                + "  if [ \"$INIT_SYSTEM\" = openrc ]; then service=crond; [ -x /etc/init.d/crond ] || service=cron; $SUDO rc-update add \"$service\" default >/dev/null 2>&1 || true; $SUDO rc-service \"$service\" start >/dev/null 2>&1 || true; return; fi\n"
                + "  if [ \"$INIT_SYSTEM\" = runit ]; then root=$(runit_service_root); for service in cronie crond cron; do if [ -d \"/etc/sv/$service\" ]; then $SUDO mkdir -p \"$root\"; $SUDO ln -sfn \"/etc/sv/$service\" \"$root/$service\"; $SUDO sv up \"$service\" >/dev/null 2>&1 || true; return; fi; done; fi\n"
                + "  if command -v service >/dev/null 2>&1; then $SUDO service cron start >/dev/null 2>&1 || $SUDO service crond start >/dev/null 2>&1 || true; elif command -v crond >/dev/null 2>&1; then pgrep -x crond >/dev/null 2>&1 || $SUDO crond; fi\n"
                + "}\n"
                + "cert_renewal_disable() {\n"
                + "  if [ \"$INIT_SYSTEM\" = systemd ]; then $SUDO systemctl disable --now tgproxy-certbot-renew.timer >/dev/null 2>&1 || true; else $SUDO rm -f /etc/cron.d/tgproxy-certbot-renew; fi\n"
                + "}\n";
    }

    private static String ensureServiceAccount() {
        return "if ! group_exists tgproxy-relay; then\n"
                + "  if command -v groupadd >/dev/null 2>&1; then $SUDO groupadd --system tgproxy-relay;\n"
                + "  elif command -v addgroup >/dev/null 2>&1; then $SUDO addgroup -S tgproxy-relay >/dev/null 2>&1 || $SUDO addgroup --system tgproxy-relay;\n"
                + "  else echo group_creation_tool_required >&2; exit 46; fi\n"
                + "fi\n"
                + "if ! id tgproxy-relay >/dev/null 2>&1; then\n"
                + "  if command -v useradd >/dev/null 2>&1; then $SUDO useradd --system --gid tgproxy-relay --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin tgproxy-relay;\n"
                + "  elif command -v adduser >/dev/null 2>&1; then $SUDO adduser -S -D -H -h /nonexistent -s /sbin/nologin -G tgproxy-relay tgproxy-relay >/dev/null 2>&1 || $SUDO adduser --system --ingroup tgproxy-relay --home /nonexistent --no-create-home --shell /usr/sbin/nologin tgproxy-relay;\n"
                + "  else echo user_creation_tool_required >&2; exit 46; fi\n"
                + "fi\n";
    }

    private static String serviceDefinitionScript() {
        return "if [ \"$INIT_SYSTEM\" = systemd ]; then\n"
                + "  $SUDO install -d -m 0755 /etc/systemd/system\n"
                + "  $SUDO sh -c 'cat > /etc/systemd/system/tgproxy-relay.service' <<'EOF'\n"
                + "[Unit]\nDescription=TG Proxy VPS Relay\nAfter=network-online.target\nWants=network-online.target\nStartLimitIntervalSec=0\n\n"
                + "[Service]\nType=simple\nUser=tgproxy-relay\nGroup=tgproxy-relay\nExecStart=/opt/tgproxy-relay/tgproxy-relay -config /etc/tgproxy-relay/config.json\nRestart=always\nRestartSec=2s\nTimeoutStopSec=15s\nLimitNOFILE=65536\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=strict\nProtectHome=true\nCapabilityBoundingSet=\nAmbientCapabilities=\nReadWritePaths=/var/log/tgproxy-relay /var/lib/tgproxy-relay\n\n"
                + "[Install]\nWantedBy=multi-user.target\nEOF\n"
                + "elif [ \"$INIT_SYSTEM\" = openrc ]; then\n"
                + "  $SUDO install -d -m 0755 /etc/init.d\n"
                + "  $SUDO tee /etc/init.d/tgproxy-relay >/dev/null <<'EOF'\n"
                + "#!/sbin/openrc-run\nname=\"TG Proxy VPS Relay\"\ncommand=/opt/tgproxy-relay/tgproxy-relay\ncommand_args=\"-config /etc/tgproxy-relay/config.json\"\ncommand_user=tgproxy-relay:tgproxy-relay\ncommand_background=yes\npidfile=/run/tgproxy-relay.pid\noutput_log=/var/log/tgproxy-relay/service.log\nerror_log=/var/log/tgproxy-relay/service.log\nretry=\"TERM/15/KILL/5\"\ndepend() { need net; }\nEOF\n"
                + "  $SUDO chmod 0755 /etc/init.d/tgproxy-relay\n"
                + "elif [ \"$INIT_SYSTEM\" = runit ]; then\n"
                + "  $SUDO install -d -m 0755 /etc/sv/tgproxy-relay\n"
                + "  $SUDO tee /etc/sv/tgproxy-relay/run >/dev/null <<'EOF'\n"
                + "#!/bin/sh\nexec >>/var/log/tgproxy-relay/service.log 2>&1\nif command -v chpst >/dev/null 2>&1; then exec chpst -u tgproxy-relay:tgproxy-relay /opt/tgproxy-relay/tgproxy-relay -config /etc/tgproxy-relay/config.json; fi\nif command -v setpriv >/dev/null 2>&1; then exec setpriv --reuid=tgproxy-relay --regid=tgproxy-relay --init-groups /opt/tgproxy-relay/tgproxy-relay -config /etc/tgproxy-relay/config.json; fi\nexec su -s /bin/sh tgproxy-relay -c 'exec /opt/tgproxy-relay/tgproxy-relay -config /etc/tgproxy-relay/config.json'\nEOF\n"
                + "  $SUDO chmod 0755 /etc/sv/tgproxy-relay/run\n"
                + "else\n"
                + "  $SUDO install -d -m 0755 /etc/init.d /run\n"
                + "  $SUDO tee /etc/init.d/tgproxy-relay >/dev/null <<'EOF'\n"
                + "#!/bin/sh\n### BEGIN INIT INFO\n# Provides: tgproxy-relay\n# Required-Start: $network\n# Required-Stop: $network\n# Default-Start: 2 3 4 5\n# Default-Stop: 0 1 6\n# Short-Description: TG Proxy VPS Relay\n### END INIT INFO\nDAEMON=/opt/tgproxy-relay/tgproxy-relay\nCONFIG=/etc/tgproxy-relay/config.json\nPIDFILE=/run/tgproxy-relay.pid\nLOGFILE=/var/log/tgproxy-relay/service.log\nstart_relay() {\n  [ -x \"$DAEMON\" ] || return 1\n  if [ -s \"$PIDFILE\" ] && kill -0 \"$(cat \"$PIDFILE\")\" 2>/dev/null; then return 0; fi\n  rm -f \"$PIDFILE\"\n  if command -v start-stop-daemon >/dev/null 2>&1; then start-stop-daemon -S -b -m -p \"$PIDFILE\" -x \"$DAEMON\" -c tgproxy-relay -- -config \"$CONFIG\";\n  elif command -v su >/dev/null 2>&1; then su -s /bin/sh tgproxy-relay -c \"nohup '$DAEMON' -config '$CONFIG' >>'$LOGFILE' 2>&1 & echo \\$! >'$PIDFILE'\";\n  else nohup \"$DAEMON\" -config \"$CONFIG\" >>\"$LOGFILE\" 2>&1 & echo $! >\"$PIDFILE\"; fi\n}\nstop_relay() {\n  [ -s \"$PIDFILE\" ] || return 0\n  pid=$(cat \"$PIDFILE\")\n  kill \"$pid\" 2>/dev/null || true\n  n=0; while kill -0 \"$pid\" 2>/dev/null && [ \"$n\" -lt 15 ]; do sleep 1; n=$((n + 1)); done\n  kill -9 \"$pid\" 2>/dev/null || true\n  rm -f \"$PIDFILE\"\n}\ncase \"$1\" in\n  start) start_relay ;;\n  stop) stop_relay ;;\n  restart|force-reload) stop_relay; start_relay ;;\n  status) [ -s \"$PIDFILE\" ] && kill -0 \"$(cat \"$PIDFILE\")\" 2>/dev/null ;;\n  *) echo \"Usage: $0 {start|stop|restart|status}\" >&2; exit 2 ;;\nesac\nEOF\n"
                + "  $SUDO chmod 0755 /etc/init.d/tgproxy-relay\n"
                + "fi\n";
    }

    private static String ensureBaseDependencies() {
        return "if ! command -v tar >/dev/null 2>&1 || ! command -v sha256sum >/dev/null 2>&1 || "
                + "{ ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; }; then\n"
                + "  if command -v apt-get >/dev/null 2>&1; then\n"
                + "    $SUDO env DEBIAN_FRONTEND=noninteractive apt-get update -y\n"
                + "    $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl tar coreutils\n"
                + "  elif command -v dnf >/dev/null 2>&1; then\n"
                + "    $SUDO dnf install -y ca-certificates curl tar coreutils\n"
                + "  elif command -v microdnf >/dev/null 2>&1; then\n"
                + "    $SUDO microdnf install -y ca-certificates curl tar coreutils\n"
                + "  elif command -v yum >/dev/null 2>&1; then\n"
                + "    $SUDO yum install -y ca-certificates curl tar coreutils\n"
                + "  elif command -v zypper >/dev/null 2>&1; then\n"
                + "    $SUDO zypper --non-interactive install -y ca-certificates curl tar coreutils\n"
                + "  elif command -v apk >/dev/null 2>&1; then\n"
                + "    $SUDO apk add --no-cache ca-certificates curl tar coreutils shadow\n"
                + "  elif command -v pacman >/dev/null 2>&1; then\n"
                + "    $SUDO pacman -Sy --noconfirm --needed ca-certificates curl tar coreutils shadow\n"
                + "  elif command -v xbps-install >/dev/null 2>&1; then\n"
                + "    $SUDO xbps-install -Sy ca-certificates curl tar coreutils shadow\n"
                + "  elif command -v emerge >/dev/null 2>&1; then\n"
                + "    $SUDO emerge --noreplace app-misc/ca-certificates net-misc/curl app-arch/tar sys-apps/coreutils sys-apps/shadow\n"
                + "  else echo supported_package_manager_required >&2; exit 43; fi\n"
                + "fi\n";
    }

    private static void collectMutationPaths(Set<String> paths, VpsSetupRequest request,
                                             VpsSetupPlan.InstallMode mode,
                                             String targetPath) {
        if (paths == null || request == null || mode == null) return;
        if (mode == VpsSetupPlan.InstallMode.EXISTING_RELAY_ADD_TOKEN
                || mode == VpsSetupPlan.InstallMode.EXISTING_RELAY_UPDATE) {
            paths.add(targetPath == null || targetPath.trim().isEmpty()
                    ? "/etc/tgproxy-relay/config.json" : targetPath.trim());
        } else if (mode == VpsSetupPlan.InstallMode.NGINX_MANAGED_TLS) {
            paths.add("/etc/nginx/conf.d/tgproxy-relay-"
                    + safeName(request.relayHost()) + ".conf");
            paths.add("/usr/local/sbin/tgproxy-certbot-renew");
            paths.add("/etc/systemd/system/tgproxy-certbot-renew.service");
            paths.add("/etc/systemd/system/tgproxy-certbot-renew.timer");
            paths.add("/etc/cron.d/tgproxy-certbot-renew");
        } else if (mode == VpsSetupPlan.InstallMode.NGINX_NEW_SERVER) {
            paths.add("/etc/nginx/conf.d/tgproxy-relay-"
                    + safeName(request.relayHost()) + ".conf");
        } else if (mode == VpsSetupPlan.InstallMode.NGINX_EXISTING_LOCATION) {
            if (targetPath != null && !targetPath.trim().isEmpty()) paths.add(targetPath.trim());
            paths.add("/etc/nginx/snippets/tgproxy-relay-"
                    + safeName(request.relayHost()) + ".conf");
        } else if (mode == VpsSetupPlan.InstallMode.CADDY_EXISTING_SITE
                || mode == VpsSetupPlan.InstallMode.DOCKER_CADDY_EXISTING_SITE) {
            if (targetPath != null && !targetPath.trim().isEmpty()) paths.add(targetPath.trim());
        }
    }

    private static boolean usesMode(VpsSetupPlan plan, VpsSetupPlan.InstallMode mode) {
        return plan != null && mode != null
                && (plan.installMode() == mode || plan.routeRepairMode() == mode);
    }

    static String rollback(String transactionId) {
        String transaction = safeTransactionId(transactionId);
        return "#!/bin/sh\n"
                + "set -eu\n"
                + sudoPrelude()
                + servicePrelude()
                + "LATEST=" + shellQuote("/var/backups/tgproxy-relay/txn-" + transaction) + "\n"
                + "$SUDO test -d \"$LATEST\" || { echo backup_transaction_missing >&2; exit 72; }\n"
                + "if $SUDO test -f \"$LATEST/managed-tls\"; then cert_renewal_disable; fi\n"
                + "relay_service_stop >/dev/null 2>&1 || true\n"
                + "if $SUDO test -f \"$LATEST/binary.absent\"; then $SUDO rm -f /opt/tgproxy-relay/tgproxy-relay; elif $SUDO test -f \"$LATEST/tgproxy-relay\"; then $SUDO install -m 0755 \"$LATEST/tgproxy-relay\" /opt/tgproxy-relay/tgproxy-relay; fi\n"
                + "if $SUDO test -f \"$LATEST/config.absent\"; then $SUDO rm -f /etc/tgproxy-relay/config.json; elif $SUDO test -f \"$LATEST/config.json\"; then $SUDO cp -p \"$LATEST/config.json\" /etc/tgproxy-relay/config.json; fi\n"
                + "if $SUDO test -f \"$LATEST/state-dir.absent\"; then $SUDO rm -rf -- /var/lib/tgproxy-relay; elif $SUDO test -d \"$LATEST/state-dir\"; then $SUDO rm -rf -- /var/lib/tgproxy-relay; $SUDO cp -a \"$LATEST/state-dir\" /var/lib/tgproxy-relay; else echo rollback_state_snapshot_missing >&2; exit 79; fi\n"
                + "if $SUDO test -f \"$LATEST/service-dropin.absent\"; then $SUDO rm -f /etc/systemd/system/tgproxy-relay.service.d/20-owner-state.conf; elif $SUDO test -f \"$LATEST/20-owner-state.conf\"; then $SUDO install -d -m 0755 /etc/systemd/system/tgproxy-relay.service.d; $SUDO cp -p \"$LATEST/20-owner-state.conf\" /etc/systemd/system/tgproxy-relay.service.d/20-owner-state.conf; else echo rollback_service_dropin_snapshot_missing >&2; exit 80; fi\n"
                + "if $SUDO test -f \"$LATEST/service-dropin-dir.absent\"; then $SUDO rmdir /etc/systemd/system/tgproxy-relay.service.d >/dev/null 2>&1 || true; fi\n"
                + "if $SUDO test -f \"$LATEST/absent-paths.txt\"; then\n"
                + "  $SUDO cat \"$LATEST/absent-paths.txt\" | while IFS= read -r target; do\n"
                + "    [ -n \"$target\" ] || continue\n"
                + "    case \"$target\" in /etc/nginx/*|/etc/caddy/*|/etc/systemd/system/tgproxy-certbot-renew.*|/etc/cron.d/tgproxy-certbot-renew|/usr/local/sbin/tgproxy-certbot-renew|/etc/ufw/user.rules|/etc/ufw/user6.rules|/var/lib/docker/volumes/*|/opt/*|/srv/*|/root/*|/home/*) ;; *) echo unsafe_rollback_path >&2; exit 73 ;; esac\n"
                + "    $SUDO rm -f -- \"$target\"\n"
                + "  done\n"
                + "fi\n"
                + "if $SUDO test -f \"$LATEST/path-map.tsv\"; then\n"
                + "  $SUDO cat \"$LATEST/path-map.tsv\" | while IFS=\"$(printf '\\t')\" read -r safe target; do\n"
                + "    [ -n \"$safe\" ] && [ -n \"$target\" ] || continue\n"
                + "    $SUDO grep -Fxq \"$target\" \"$LATEST/mutation-paths.txt\" || continue\n"
                + "    $SUDO test -f \"$LATEST/$safe\" || { echo rollback_snapshot_missing >&2; exit 74; }\n"
                + "    $SUDO cp -p \"$LATEST/$safe\" \"$target\"\n"
                + "  done\n"
                + "fi\n"
                + "if $SUDO test -f \"$LATEST/service.absent\"; then\n"
                + "  $SUDO rm -f /etc/systemd/system/tgproxy-relay.service\n"
                + "else\n"
                + "  $SUDO test -f \"$LATEST/tgproxy-relay.service\" || { echo rollback_service_snapshot_missing >&2; exit 75; }\n"
                + "  $SUDO cp -p \"$LATEST/tgproxy-relay.service\" /etc/systemd/system/tgproxy-relay.service\n"
                + "fi\n"
                + "if $SUDO test -f \"$LATEST/init-script.absent\"; then $SUDO rm -f /etc/init.d/tgproxy-relay; else $SUDO test -f \"$LATEST/tgproxy-relay.init\" || { echo rollback_init_snapshot_missing >&2; exit 75; }; $SUDO install -m 0755 \"$LATEST/tgproxy-relay.init\" /etc/init.d/tgproxy-relay; fi\n"
                + "if $SUDO test -f \"$LATEST/runit-service.absent\"; then $SUDO rm -rf /etc/sv/tgproxy-relay; else $SUDO test -d \"$LATEST/runit-service\" || { echo rollback_runit_snapshot_missing >&2; exit 75; }; $SUDO rm -rf /etc/sv/tgproxy-relay; $SUDO cp -a \"$LATEST/runit-service\" /etc/sv/tgproxy-relay; fi\n"
                + "if $SUDO test -f \"$LATEST/portable-cron.absent\"; then $SUDO rm -f /etc/cron.d/tgproxy-relay; else $SUDO test -f \"$LATEST/tgproxy-relay.cron\" || { echo rollback_cron_snapshot_missing >&2; exit 75; }; $SUDO install -m 0644 \"$LATEST/tgproxy-relay.cron\" /etc/cron.d/tgproxy-relay; fi\n"
                + "if [ \"$INIT_SYSTEM\" = systemd ]; then $SUDO systemctl daemon-reload; fi\n"
                + "if $SUDO test -f \"$LATEST/service.was-enabled\"; then relay_service_enable; else relay_service_disable; fi\n"
                + "if $SUDO test -f \"$LATEST/service.was-active\"; then relay_service_restart; else relay_service_stop >/dev/null 2>&1 || true; fi\n"
                + "if $SUDO test -f \"$LATEST/ufw.was-active\"; then command -v ufw >/dev/null 2>&1 || { echo rollback_ufw_missing >&2; exit 78; }; $SUDO ufw reload; fi\n"
                + "if $SUDO test -f \"$LATEST/opt-dir.absent\"; then $SUDO rm -rf -- /opt/tgproxy-relay; fi\n"
                + "if $SUDO test -f \"$LATEST/etc-dir.absent\"; then $SUDO rm -rf -- /etc/tgproxy-relay; fi\n"
                + "if $SUDO test -f \"$LATEST/log-dir.absent\"; then $SUDO rm -rf -- /var/log/tgproxy-relay; fi\n"
                + "if $SUDO test -f \"$LATEST/user.absent\" && id tgproxy-relay >/dev/null 2>&1; then if command -v userdel >/dev/null 2>&1; then $SUDO userdel tgproxy-relay; elif command -v deluser >/dev/null 2>&1; then $SUDO deluser tgproxy-relay; fi; fi\n"
                + "if $SUDO test -f \"$LATEST/group.absent\" && group_exists tgproxy-relay; then if command -v groupdel >/dev/null 2>&1; then $SUDO groupdel tgproxy-relay; elif command -v delgroup >/dev/null 2>&1; then $SUDO delgroup tgproxy-relay; fi; fi\n"
                + "if $SUDO test -f \"$LATEST/reload-nginx\"; then $SUDO nginx -t; nginx_service_reload; fi\n"
                + "if $SUDO test -f \"$LATEST/reload-caddy-target\"; then\n"
                + "  CADDY_TARGET=$($SUDO cat \"$LATEST/reload-caddy-target\")\n"
                + "  [ -n \"$CADDY_TARGET\" ] || { echo rollback_caddy_target_missing >&2; exit 76; }\n"
                + "  $SUDO caddy validate --config \"$CADDY_TARGET\"\n"
                + "  $SUDO caddy reload --config \"$CADDY_TARGET\"\n"
                + "fi\n"
                + "if $SUDO test -f \"$LATEST/reload-docker-caddy-container\"; then\n"
                + "  CADDY_CONTAINER=$($SUDO cat \"$LATEST/reload-docker-caddy-container\")\n"
                + "  [ -n \"$CADDY_CONTAINER\" ] || { echo rollback_caddy_container_missing >&2; exit 77; }\n"
                + "  $SUDO docker exec \"$CADDY_CONTAINER\" caddy validate --config /etc/caddy/Caddyfile\n"
                + "  $SUDO docker exec \"$CADDY_CONTAINER\" caddy reload --config /etc/caddy/Caddyfile\n"
                + "fi\n";
    }

    private static String safeTransactionId(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{7,95}")) {
            throw new IllegalArgumentException("invalid VPS setup transaction id");
        }
        return normalized;
    }

    private static String nginxManagedTlsConfig(VpsSetupRequest request) {
        String endpoint = request.relayHost();
        String path = request.relayPath();
        String upstream = "http://127.0.0.1:" + request.internalRelayPort();
        String configPath = "/etc/nginx/conf.d/tgproxy-relay-" + safeName(endpoint) + ".conf";
        String healthPath = managementPath(path, "/healthz");
        String versionPath = managementPath(path, "/version");
        String capabilitiesPath = managementPath(path, "/capabilities");
        String testRoutesPath = managementPath(path, "/test-routes");
        String certificateCommand = request.relayHostIsIp()
                ? "$SUDO \"$CERTBOT\" certonly --non-interactive --agree-tos --register-unsafely-without-email --keep-until-expiring --webroot -w \"$ACME_ROOT\" --cert-name \"$DOMAIN\" --preferred-profile shortlived --ip-address \"$DOMAIN\"\n"
                : "$SUDO \"$CERTBOT\" certonly --non-interactive --agree-tos --register-unsafely-without-email --keep-until-expiring --webroot -w \"$ACME_ROOT\" --cert-name \"$DOMAIN\" -d \"$DOMAIN\"\n";
        return "NGINX_CONF=" + shellQuote(configPath) + "\n"
                + "ACME_ROOT=/var/lib/tgproxy-acme\n"
                + "CERTBOT=/opt/tgproxy-certbot/bin/certbot\n"
                + "if command -v apt-get >/dev/null 2>&1; then\n"
                + "  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get update -y\n"
                + "  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y nginx python3 python3-venv python3-pip ca-certificates cron\n"
                + "elif command -v dnf >/dev/null 2>&1; then\n"
                + "  $SUDO dnf install -y nginx python3 python3-pip ca-certificates cronie\n"
                + "elif command -v microdnf >/dev/null 2>&1; then\n"
                + "  $SUDO microdnf install -y nginx python3 python3-pip ca-certificates cronie\n"
                + "elif command -v yum >/dev/null 2>&1; then\n"
                + "  $SUDO yum install -y nginx python3 python3-pip ca-certificates cronie\n"
                + "elif command -v zypper >/dev/null 2>&1; then\n"
                + "  $SUDO zypper --non-interactive install -y nginx python3 python3-pip python3-virtualenv ca-certificates cron\n"
                + "elif command -v apk >/dev/null 2>&1; then\n"
                + "  $SUDO apk add --no-cache nginx python3 py3-pip py3-virtualenv ca-certificates dcron\n"
                + "elif command -v pacman >/dev/null 2>&1; then\n"
                + "  $SUDO pacman -Sy --noconfirm --needed nginx python python-pip python-virtualenv ca-certificates cronie\n"
                + "elif command -v xbps-install >/dev/null 2>&1; then\n"
                + "  $SUDO xbps-install -Sy nginx python3 python3-pip python3-virtualenv ca-certificates cronie\n"
                + "elif command -v emerge >/dev/null 2>&1; then\n"
                + "  $SUDO emerge --noreplace www-servers/nginx dev-lang/python dev-python/pip dev-python/virtualenv app-misc/ca-certificates sys-process/cronie\n"
                + "else echo supported_package_manager_required >&2; exit 55; fi\n"
                + "$SUDO install -d -m 0755 /etc/nginx/conf.d \"$ACME_ROOT/.well-known/acme-challenge\"\n"
                + "if [ ! -x \"$CERTBOT\" ]; then\n"
                + "  $SUDO rm -rf /opt/tgproxy-certbot\n"
                + "  $SUDO python3 -m venv /opt/tgproxy-certbot || $SUDO python3 -m virtualenv /opt/tgproxy-certbot\n"
                + "fi\n"
                + "$SUDO /opt/tgproxy-certbot/bin/python -m pip install --disable-pip-version-check --upgrade 'certbot>=5.4'\n"
                + "$SUDO tee \"$NGINX_CONF\" >/dev/null <<'EOF'\n"
                + "server {\n"
                + "    listen 80;\n"
                + "    listen [::]:80;\n"
                + "    server_name " + endpoint + ";\n"
                + "    location ^~ /.well-known/acme-challenge/ { root /var/lib/tgproxy-acme; try_files $uri =404; }\n"
                + "    location / { return 404; }\n"
                + "}\n"
                + "EOF\n"
                + "$SUDO nginx -t\n"
                + "nginx_service_start\n"
                + "nginx_service_reload\n"
                + "if command -v ufw >/dev/null 2>&1; then $SUDO ufw allow 80/tcp || true; $SUDO ufw allow 443/tcp || true; fi\n"
                + "if [ ! -s \"/etc/letsencrypt/live/$DOMAIN/fullchain.pem\" ] || [ ! -s \"/etc/letsencrypt/live/$DOMAIN/privkey.pem\" ]; then\n"
                + certificateCommand
                + "fi\n"
                + "[ -s \"/etc/letsencrypt/live/$DOMAIN/fullchain.pem\" ] || { echo certificate_fullchain_missing >&2; exit 56; }\n"
                + "[ -s \"/etc/letsencrypt/live/$DOMAIN/privkey.pem\" ] || { echo certificate_private_key_missing >&2; exit 56; }\n"
                + "$SUDO tee \"$NGINX_CONF\" >/dev/null <<'EOF'\n"
                + "server {\n"
                + "    listen 80;\n"
                + "    listen [::]:80;\n"
                + "    server_name " + endpoint + ";\n"
                + "    location ^~ /.well-known/acme-challenge/ { root /var/lib/tgproxy-acme; try_files $uri =404; }\n"
                + "    location / { return 404; }\n"
                + "}\n\n"
                + "server {\n"
                + "    listen 443 ssl http2;\n"
                + "    listen [::]:443 ssl http2;\n"
                + "    server_name " + endpoint + ";\n\n"
                + "    ssl_certificate /etc/letsencrypt/live/" + endpoint + "/fullchain.pem;\n"
                + "    ssl_certificate_key /etc/letsencrypt/live/" + endpoint + "/privkey.pem;\n"
                + "    ssl_protocols TLSv1.2 TLSv1.3;\n"
                + "    ssl_session_cache shared:TGProxyTLS:10m;\n"
                + "    ssl_session_timeout 1d;\n\n"
                + "    location = " + healthPath + " { proxy_pass " + upstream + "/healthz; }\n"
                + "    location = " + versionPath + " { proxy_pass " + upstream + "/version; }\n"
                + "    location = " + capabilitiesPath + " { proxy_pass " + upstream + "/capabilities; }\n"
                + "    location = " + testRoutesPath + " { proxy_pass " + upstream + "/test-routes; }\n\n"
                + "    location ^~ " + path + " {\n"
                + "        proxy_http_version 1.1;\n"
                + "        proxy_set_header Host $host;\n"
                + "        proxy_set_header X-Real-IP $remote_addr;\n"
                + "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
                + "        proxy_set_header X-Forwarded-Proto https;\n"
                + "        proxy_set_header Upgrade $http_upgrade;\n"
                + "        proxy_set_header Connection \"upgrade\";\n"
                + "        proxy_read_timeout 3600s;\n"
                + "        proxy_send_timeout 3600s;\n"
                + "        proxy_buffering off;\n"
                + "        proxy_request_buffering off;\n"
                + "        proxy_pass " + upstream + ";\n"
                + "    }\n"
                + "}\n"
                + "EOF\n"
                + "$SUDO tee /usr/local/sbin/tgproxy-certbot-renew >/dev/null <<'EOF'\n"
                + "#!/bin/sh\n"
                + "set -eu\n"
                + "/opt/tgproxy-certbot/bin/certbot renew --quiet\n"
                + "/usr/sbin/nginx -t\n"
                + "if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then systemctl reload nginx\n"
                + "elif command -v rc-service >/dev/null 2>&1; then rc-service nginx reload >/dev/null 2>&1 || rc-service nginx restart\n"
                + "else /usr/sbin/nginx -s reload; fi\n"
                + "EOF\n"
                + "$SUDO chmod 0755 /usr/local/sbin/tgproxy-certbot-renew\n"
                + "if [ \"$INIT_SYSTEM\" = systemd ]; then\n"
                + "$SUDO tee /etc/systemd/system/tgproxy-certbot-renew.service >/dev/null <<'EOF'\n"
                + "[Unit]\nDescription=Renew TG Proxy HTTPS certificate\nAfter=network-online.target nginx.service\nWants=network-online.target\n\n"
                + "[Service]\nType=oneshot\nExecStart=/usr/local/sbin/tgproxy-certbot-renew\n"
                + "EOF\n"
                + "$SUDO tee /etc/systemd/system/tgproxy-certbot-renew.timer >/dev/null <<'EOF'\n"
                + "[Unit]\nDescription=Regular TG Proxy HTTPS certificate renewal\n\n"
                + "[Timer]\nOnBootSec=10m\nOnUnitActiveSec=12h\nRandomizedDelaySec=30m\nPersistent=true\n\n"
                + "[Install]\nWantedBy=timers.target\n"
                + "EOF\n"
                + "$SUDO rm -f /etc/cron.d/tgproxy-certbot-renew\n"
                + "else\n"
                + "$SUDO install -d -m 0755 /etc/cron.d\n"
                + "printf '%s\\n' '17 */12 * * * root /usr/local/sbin/tgproxy-certbot-renew >/dev/null 2>&1' | $SUDO tee /etc/cron.d/tgproxy-certbot-renew >/dev/null\n"
                + "$SUDO chmod 0644 /etc/cron.d/tgproxy-certbot-renew\n"
                + "fi\n"
                + "$SUDO nginx -t\n"
                + "nginx_service_reload\n";
    }

    private static String nginxReverseProxyConfig(VpsSetupRequest request) {
        String domain = request.relayHost();
        String path = request.relayPath();
        String upstream = "http://127.0.0.1:" + request.internalRelayPort();
        String configPath = "/etc/nginx/conf.d/tgproxy-relay-" + safeName(domain) + ".conf";
        String healthPath = managementPath(path, "/healthz");
        String versionPath = managementPath(path, "/version");
        String capabilitiesPath = managementPath(path, "/capabilities");
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
                + "    location = " + capabilitiesPath + " { proxy_pass " + upstream + "/capabilities; }\n"
                + "    location = " + testRoutesPath + " { proxy_pass " + upstream + "/test-routes; }\n\n"
                + "    location ^~ " + path + " {\n"
                + "        proxy_http_version 1.1;\n"
                + "        proxy_set_header Host $host;\n"
                + "        proxy_set_header X-Real-IP $remote_addr;\n"
                + "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
                + "        proxy_set_header X-Forwarded-Proto https;\n"
                + "        proxy_set_header Upgrade $http_upgrade;\n"
                + "        proxy_set_header Connection \"upgrade\";\n"
                + "        proxy_read_timeout 3600s;\n"
                + "        proxy_send_timeout 3600s;\n"
                + "        proxy_buffering off;\n"
                + "        proxy_request_buffering off;\n"
                + "        proxy_pass " + upstream + ";\n"
                + "    }\n"
                + "}\n"
                + "EOF\n"
                + "$SUDO nginx -t\n"
                + "nginx_service_reload\n";
    }

    private static String nginxExistingLocationConfig(VpsSetupRequest request, String targetPath) {
        String domain = request.relayHost();
        String path = request.relayPath();
        String upstream = "http://127.0.0.1:" + request.internalRelayPort();
        String healthPath = managementPath(path, "/healthz");
        String versionPath = managementPath(path, "/version");
        String capabilitiesPath = managementPath(path, "/capabilities");
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
                + "location = " + capabilitiesPath + " { proxy_pass " + upstream + "/capabilities; }\n"
                + "location = " + testRoutesPath + " { proxy_pass " + upstream + "/test-routes; }\n\n"
                + "location ^~ " + path + " {\n"
                + "    proxy_http_version 1.1;\n"
                + "    proxy_set_header Host $host;\n"
                + "    proxy_set_header X-Real-IP $remote_addr;\n"
                + "    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
                + "    proxy_set_header X-Forwarded-Proto https;\n"
                + "    proxy_set_header Upgrade $http_upgrade;\n"
                + "    proxy_set_header Connection \"upgrade\";\n"
                + "    proxy_read_timeout 3600s;\n"
                + "    proxy_send_timeout 3600s;\n"
                + "    proxy_buffering off;\n"
                + "    proxy_request_buffering off;\n"
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
                + "nginx_service_reload\n";
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


