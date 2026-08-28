package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsSetupScriptsTest {
    @Test
    public void auditDetectsLinuxInitSystemsAndCommonPackageManagers() {
        String script = VpsSetupScripts.audit();

        assertTrue(script.contains("printf 'kernel=%s\\n'"));
        assertTrue(script.contains("printf 'init_system=%s\\n'"));
        assertTrue(script.contains("echo openrc"));
        assertTrue(script.contains("echo runit"));
        assertTrue(script.contains("echo sysv"));
        assertTrue(script.contains("command -v microdnf"));
        assertTrue(script.contains("command -v zypper"));
        assertTrue(script.contains("command -v apk"));
        assertTrue(script.contains("command -v pacman"));
        assertTrue(script.contains("command -v xbps-install"));
        assertTrue(script.contains("command -v emerge"));
    }

    @Test
    public void auditScriptIsReadOnly() {
        String script = VpsSetupScripts.audit();

        assertTrue(script.contains("uname -m"));
        assertTrue(script.contains("systemctl"));
        assertFalse(script.contains("apt install"));
        assertFalse(script.contains("systemctl restart"));
        assertFalse(script.contains("cat >"));
    }

    @Test
    public void auditScriptChecksRequestedDomainDnsCertificatesAndWebServerConfigs() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build();

        String script = VpsSetupScripts.audit(request);

        assertTrue(script.contains("DOMAIN='relay.example.com'"));
        assertTrue(script.contains("getent ahosts \"$DOMAIN\""));
        assertTrue(script.contains("/etc/letsencrypt/live/$DOMAIN/fullchain.pem"));
        assertTrue(script.contains("nginx_domain_match_count"));
        assertTrue(script.contains("caddy_domain_match_count"));
        assertTrue(script.contains("apache_domain_match_count"));
        assertFalse(script.contains("cat >"));
        assertFalse(script.contains("systemctl restart"));
    }

    @Test
    public void auditScriptReportsExistingRelayAndSafeNginxEmbedFacts() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build();

        String script = VpsSetupScripts.audit(request);

        assertTrue(script.contains("printf 'python3=%s\\n'"));
        assertTrue(script.contains("existing_relay"));
        assertTrue(script.contains("existing_relay_config"));
        assertTrue(script.contains("existing_relay_public_url"));
        assertTrue(script.contains("existing_relay_token_ids_known"));
        assertTrue(script.contains("existing_relay_token_ids"));
        assertTrue(script.contains("addedTokens"));
        assertTrue(script.contains("revokedHashes"));
        assertTrue(script.contains("nginx_safe_embed"));
        assertTrue(script.contains("nginx_path_exists"));
        assertTrue(script.contains("docker_caddy_domain_match_count"));
        assertTrue(script.contains("docker_caddy_path_exists"));
        assertTrue(script.contains("docker_caddy_validate"));
        assertTrue(script.contains("caddy_safe_embed"));
        assertTrue(script.contains("caddy_path_exists"));
        assertTrue(script.contains("caddy_validate"));
        assertFalse(script.contains("cat >"));
        assertFalse(script.contains("systemctl restart"));
    }

    @Test
    public void auditOnlyCountsActiveWebConfigsAndIgnoresBackups() {
        String script = VpsSetupScripts.audit(VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build());

        assertTrue(script.contains("active_matches()"));
        assertTrue(script.contains("find -L \"$root\" -type f"));
        assertTrue(script.contains("! -name '*.bak'"));
        assertTrue(script.contains("! -name '*.disabled'"));
        assertTrue(script.contains(
                "NGINX_MATCHES=$(active_matches /etc/nginx/sites-enabled /etc/nginx/conf.d)"));
        assertFalse(script.contains(
                "NGINX_MATCHES=$(active_matches /etc/nginx/sites-enabled /etc/nginx/conf.d /etc/nginx/sites-available)"));
        assertFalse(script.contains(
                "APACHE_MATCHES=$(active_matches /etc/apache2/sites-enabled /etc/apache2/sites-available"));
    }

    @Test
    public void auditScriptCanUseExistingRelayPublicUrlAsRouteDomainFallback() {
        String script = VpsSetupScripts.audit(VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("203.0.113.10")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build());

        assertTrue(script.contains("public_url_host()"));
        assertTrue(script.contains("public_url_path()"));
        assertTrue(script.contains("EXISTING_PUBLIC_HOST=$(public_url_host \"$EXISTING_PUBLIC_URL\")"));
        assertTrue(script.contains("DOMAIN=\"$EXISTING_PUBLIC_HOST\""));
        assertTrue(script.contains("RELAY_PATH=$(public_url_path \"$EXISTING_PUBLIC_URL\")"));
        assertTrue(script.indexOf("EXISTING_PUBLIC_URL=$(json_value") <
                script.indexOf("DOCKER_CADDY_CONTAINER=$(docker_caddy_container_for_domain)"));
    }

    @Test
    public void auditScriptCanReplaceIpRelayHostWithExistingRelayPublicDomain() {
        String script = VpsSetupScripts.audit(VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build());

        assertTrue(script.contains("is_ipv4()"));
        assertTrue(script.contains("EXISTING_PUBLIC_HOST=$(public_url_host \"$EXISTING_PUBLIC_URL\")"));
        assertTrue(script.contains("[ -z \"$DOMAIN\" ] || is_ipv4 \"$DOMAIN\""));
        assertTrue(script.contains("DOMAIN=\"$EXISTING_PUBLIC_HOST\""));
    }

    @Test
    public void auditScriptDiscoversExistingVpsDomainsForEmptyRelayHostFlow() {
        String script = VpsSetupScripts.audit(VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build());

        assertTrue(script.contains("discover_domains()"));
        assertTrue(script.contains("/etc/letsencrypt/live"));
        assertTrue(script.contains("server_name"));
        assertTrue(script.contains("ServerName"));
        assertTrue(script.contains("docker-compose.yml"));
        assertTrue(script.contains("DOMAIN"));
        assertTrue(script.contains("Caddyfile"));
        assertTrue(script.contains("docker exec"));
        assertTrue(script.contains("printf 'discovered_domains=%s\\n'"));
        assertFalse(script.contains("cat >"));
        assertFalse(script.contains("systemctl restart"));
    }

    @Test
    public void installScriptUsesReleaseAssetAndDoesNotEmbedSshPassword() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("vps.example.com")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("relay.example.com")
                .relayPort(18080)
                .relayTls(false)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build();

        String script = VpsSetupScripts.install(request);

        assertTrue(script.contains("TG-Proxy-Relay-v1.0.0-linux-${RELAY_ARCH}.tar.gz"));
        assertTrue(script.contains("/etc/tgproxy-relay/config.json"));
        assertTrue(script.contains("\"1\": \"149.154.175.50\""));
        assertTrue(script.contains("\"2\": \"149.154.167.51\""));
        assertTrue(script.contains("\"4\": \"149.154.167.91\""));
        assertTrue(script.contains("systemctl enable --now tgproxy-relay"));
        assertTrue(script.contains("SHA256SUMS.txt"));
        assertTrue(script.contains("sha256sum -c -"));
        assertTrue(script.contains("-version"));
        assertTrue(script.contains("-check-config"));
        assertTrue(script.contains("\"idleTimeoutSec\": 0"));
        assertTrue(script.contains("\"pingIntervalSec\": 25"));
        assertTrue(script.contains("Restart=always"));
        assertTrue(script.contains("StartLimitIntervalSec=0"));
        assertTrue(script.contains("\"admin\": {\"tokens\":"));
        assertTrue(script.contains("\"statePath\": \"/var/lib/tgproxy-relay/state.json\""));
        assertTrue(script.contains("ReadWritePaths=/var/log/tgproxy-relay /var/lib/tgproxy-relay"));
        assertTrue(script.contains("install -d -m 0750 -o tgproxy-relay -g tgproxy-relay /var/lib/tgproxy-relay"));
        assertFalse(script.contains("ssh-secret"));
    }

    @Test
    public void tlsDomainInstallUsesLocalRelayAndIsolatedNginxServerBlock() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build();

        String script = VpsSetupScripts.install(request);

        assertTrue(script.contains("LISTEN='127.0.0.1:18080'"));
        assertTrue(script.contains("PUBLIC_URL='https://relay.example.com:443/apiws'"));
        assertTrue(script.contains("/etc/nginx/conf.d/tgproxy-relay-relay_example_com.conf"));
        assertTrue(script.contains("nginx -t"));
        assertTrue(script.contains("systemctl reload nginx"));
        assertTrue(script.contains("proxy_pass http://127.0.0.1:18080"));
        assertTrue(script.contains("proxy_read_timeout 3600s"));
        assertTrue(script.contains("proxy_send_timeout 3600s"));
        assertTrue(script.contains("proxy_buffering off"));
        assertFalse(script.contains("ssh-secret"));
    }

    @Test
    public void installScriptAddsTokenToExistingRelayWithoutFullReinstall() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("new-device-token")
                .releaseVersion("1.0.0")
                .build();
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "python3=yes\n"
                        + "existing_relay=yes\n"
                        + "existing_relay_config=/etc/tgproxy-relay/config.json\n"
                        + "existing_relay_public_url=https://example.com/apiws\n"
                        + "existing_relay_listen=127.0.0.1:18080\n");
        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        String script = VpsSetupScripts.install(request, plan);

        assertTrue(script.contains("EXISTING_CONFIG='/etc/tgproxy-relay/config.json'"));
        assertTrue(script.contains("json.load"));
        assertTrue(script.contains("TOKEN_HASH"));
        assertTrue(script.contains("ADMIN_HASH"));
        assertTrue(script.contains("admin_tokens = admin.setdefault('tokens', [])"));
        assertTrue(script.contains("admin['statePath'] = '/var/lib/tgproxy-relay/state.json'"));
        assertTrue(script.contains("20-owner-state.conf"));
        assertTrue(script.contains("StartLimitIntervalSec=0"));
        assertTrue(script.contains("Restart=always"));
        assertTrue(script.contains("RestartSec=2s"));
        assertTrue(script.contains("TimeoutStopSec=15s"));
        assertTrue(script.contains("LimitNOFILE=65536"));
        assertFalse(script.contains("relay_dc_map = {"));
        assertTrue(script.contains("TOKEN_HASH=sha256:$(printf '%s' \"$TOKEN\""));
        assertFalse(script.contains("-token \"$TOKEN\""));
        assertTrue(script.contains("chown root:tgproxy-relay \"$EXISTING_CONFIG\""));
        assertTrue(script.contains("chmod 0640 \"$EXISTING_CONFIG\""));
        assertTrue(script.contains("systemctl restart tgproxy-relay"));
        assertTrue(script.contains("config.previous"));
        assertTrue(script.contains("rollback_config"));
        assertTrue(script.indexOf("chmod 0640 \"$EXISTING_CONFIG\"")
                < script.indexOf("if ! relay_service_restart"));
        assertFalse(script.contains("tar -xzf"));
        assertFalse(script.contains("cat > /etc/tgproxy-relay/config.json"));
        assertFalse(script.contains("ssh-secret"));
    }

    @Test
    public void installSupportsAlternativeInitSystemsPackagesAndReleaseArchitectures() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("vps.example.com")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("relay.example.com")
                .relayPort(18080)
                .relayTls(false)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build();

        String script = VpsSetupScripts.install(request);

        assertTrue(script.contains("INIT_SYSTEM=$(detect_init)"));
        assertTrue(script.contains("rc-service tgproxy-relay"));
        assertTrue(script.contains("sv restart tgproxy-relay"));
        assertTrue(script.contains("/etc/init.d/tgproxy-relay"));
        assertTrue(script.contains("#!/sbin/openrc-run"));
        assertTrue(script.contains("/etc/sv/tgproxy-relay/run"));
        assertTrue(script.contains("@reboot root /etc/init.d/tgproxy-relay start"));
        assertTrue(script.contains("i386|i486|i586|i686|x86) RELAY_ARCH=386"));
        assertTrue(script.contains("armv7*|armv8l) RELAY_ARCH=armv7"));
        assertTrue(script.contains("riscv64) RELAY_ARCH=riscv64"));
        assertTrue(script.contains("ppc64le) RELAY_ARCH=ppc64le"));
        assertTrue(script.contains("s390x) RELAY_ARCH=s390x"));
        assertTrue(script.contains("loong64|loongarch64) RELAY_ARCH=loong64"));
        assertTrue(script.contains("apk add --no-cache"));
        assertTrue(script.contains("pacman -Sy --noconfirm"));
        assertTrue(script.contains("xbps-install -Sy"));
        assertFalse(script.contains("ssh-secret"));
    }

    @Test
    public void installScriptRepairsDockerCaddyRouteForExistingRelay() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("203.0.113.10")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("new-device-token")
                .releaseVersion("1.0.0")
                .build();
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "python3=yes\n"
                        + "docker=yes\n"
                        + "existing_relay=yes\n"
                        + "existing_relay_config=/etc/tgproxy-relay/config.json\n"
                        + "existing_relay_public_url=https://relay.example.com:443/apiws\n"
                        + "existing_relay_listen=172.18.0.1:18080\n"
                        + "domain=relay.example.com\n"
                        + "domain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\n"
                        + "domain_points_to_vps=yes\n"
                        + "docker_caddy_domain_match_count=1\n"
                        + "docker_caddy_domain_matches=/opt/example-app/infra/caddy/Caddyfile\n"
                        + "docker_caddy_container=example-app-caddy-1\n"
                        + "docker_caddy_safe_embed=yes\n"
                        + "docker_caddy_path_exists=no\n"
                        + "docker_caddy_validate=yes\n");
        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        String script = VpsSetupScripts.install(request, plan);

        assertTrue(script.contains("EXISTING_CONFIG='/etc/tgproxy-relay/config.json'"));
        assertTrue(script.contains("TOKEN_HASH"));
        assertTrue(script.contains("DOCKER_CADDY_TARGET='/opt/example-app/infra/caddy/Caddyfile'"));
        assertTrue(script.contains("DOCKER_CADDY_CONTAINER='example-app-caddy-1'"));
        assertTrue(script.contains("ufw allow in on \"$DOCKER_CADDY_BRIDGE\""));
        assertTrue(script.contains("write_file_in_place \"$CADDY_TMP\" \"$DOCKER_CADDY_TARGET\""));
        assertTrue(script.contains("docker cp \"$CADDY_TMP\" \"$DOCKER_CADDY_CONTAINER\":/tmp/tgproxy-caddy-validate"));
        assertTrue(script.contains("docker restart \"$DOCKER_CADDY_CONTAINER\""));
        assertFalse(script.contains("install -m 0644 \"$CADDY_TMP\" \"$DOCKER_CADDY_TARGET\""));
        assertTrue(script.contains("handle /apiws/version"));
        assertTrue(script.contains("handle /apiws/capabilities"));
        assertTrue(script.contains("TGPROXY-RELAY relay.example.com /apiws"));
        assertTrue(script.indexOf("TGPROXY-RELAY relay.example.com /apiws")
                > script.indexOf("systemctl restart tgproxy-relay"));
        assertFalse(script.contains("tar -xzf"));
        assertFalse(script.contains("cat > /etc/tgproxy-relay/config.json"));
        assertFalse(script.contains("ssh-secret"));
    }

    @Test
    public void installScriptUpdatesExistingRelayBinaryAndKeepsConfig() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("existing-phone-token")
                .releaseVersion("1.0.0")
                .updateExistingRelay(true)
                .build();
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "curl=yes\n"
                        + "tar=yes\n"
                        + "python3=yes\n"
                        + "existing_relay=yes\n"
                        + "existing_relay_config=/etc/tgproxy-relay/config.json\n"
                        + "existing_relay_public_url=https://example.com/apiws\n"
                        + "existing_relay_listen=127.0.0.1:18080\n");
        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        String script = VpsSetupScripts.install(request, plan);

        assertTrue(script.contains("TG-Proxy-Relay-v1.0.0-linux-${RELAY_ARCH}.tar.gz"));
        assertTrue(script.contains("tar -xzf"));
        assertTrue(script.contains("SHA256SUMS.txt"));
        assertTrue(script.contains("sha256sum -c -"));
        assertTrue(script.contains("rollback_relay"));
        assertTrue(script.contains("relay_restart_failed_rolled_back"));
        assertTrue(script.contains("websocket['pingIntervalSec'] = 25"));
        assertTrue(script.contains("test_dc_map = telegram.get('testDcMap')"));
        assertTrue(script.contains("if not str(test_dc_map.get(dc, '')).strip()"));
        assertTrue(script.contains("if as_int(websocket.get('pingIntervalSec')) <= 0"));
        assertTrue(script.contains("cfg['publicUrl'] = public_url"));
        assertTrue(script.contains("ADMIN_HASH"));
        assertTrue(script.contains("admin_tokens = admin.setdefault('tokens', [])"));
        assertTrue(script.contains("ReadWritePaths=/var/lib/tgproxy-relay"));
        assertTrue(script.contains("StartLimitIntervalSec=0"));
        assertTrue(script.contains("Restart=always"));
        assertTrue(script.contains("RestartSec=2s"));
        assertTrue(script.contains("TimeoutStopSec=15s"));
        assertTrue(script.contains("LimitNOFILE=65536"));
        assertFalse(script.contains("-token \"$TOKEN\""));
        assertTrue(script.contains("install -m 0755 \"$TMPDIR/tgproxy-relay\" /opt/tgproxy-relay/tgproxy-relay"));
        assertTrue(script.contains("EXISTING_CONFIG='/etc/tgproxy-relay/config.json'"));
        assertTrue(script.contains("json.load"));
        assertTrue(script.contains("chown root:tgproxy-relay \"$EXISTING_CONFIG\""));
        assertTrue(script.contains("chmod 0640 \"$EXISTING_CONFIG\""));
        assertTrue(script.contains("systemctl restart tgproxy-relay"));
        assertTrue(script.indexOf("chmod 0640 \"$EXISTING_CONFIG\"")
                < script.indexOf("if ! relay_service_restart"));
        assertFalse(script.contains("cat > /etc/tgproxy-relay/config.json"));
        assertFalse(script.contains("ssh-secret"));
    }

    @Test
    public void tlsDomainInstallCanEmbedLocationIntoExistingNginxBlock() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build();
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "curl=yes\n"
                        + "tar=yes\n"
                        + "nginx=yes\n"
                        + "apache=no\n"
                        + "caddy=no\n"
                        + "port_443=busy\n"
                        + "port_18080=free\n"
                        + "domain=example.com\n"
                        + "domain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\n"
                        + "domain_points_to_vps=yes\n"
                        + "nginx_domain_match_count=1\n"
                        + "nginx_domain_matches=/etc/nginx/sites-enabled/example.com.conf\n"
                        + "nginx_safe_embed=yes\n"
                        + "nginx_path_exists=no\n"
                        + "cert_exists=yes\n");
        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        String script = VpsSetupScripts.install(request, plan);

        assertTrue(script.contains("NGINX_TARGET='/etc/nginx/sites-enabled/example.com.conf'"));
        assertTrue(script.contains("TGPROXY-RELAY example.com /apiws"));
        assertTrue(script.contains("/etc/nginx/snippets/tgproxy-relay-example_com.conf"));
        assertTrue(script.contains("include /etc/nginx/snippets/tgproxy-relay-example_com.conf;"));
        assertTrue(script.contains("location = /apiws/version"));
        assertTrue(script.contains("location = /apiws/capabilities"));
        assertFalse(script.contains("/etc/nginx/conf.d/tgproxy-relay-example_com.conf"));
        assertFalse(script.contains("ssh-secret"));
    }

    @Test
    public void tlsDomainInstallCanEmbedIntoExistingDockerCaddySite() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build();
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "curl=yes\n"
                        + "tar=yes\n"
                        + "python3=yes\n"
                        + "nginx=yes\n"
                        + "apache=yes\n"
                        + "docker=yes\n"
                        + "port_443=busy\n"
                        + "port_18080=free\n"
                        + "domain=relay.example.com\n"
                        + "domain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\n"
                        + "domain_points_to_vps=yes\n"
                        + "docker_caddy_domain_match_count=1\n"
                        + "docker_caddy_domain_matches=/opt/example-app/infra/caddy/Caddyfile\n"
                        + "docker_caddy_container=example-app-caddy-1\n"
                        + "docker_caddy_safe_embed=yes\n"
                        + "docker_caddy_path_exists=no\n"
                        + "docker_caddy_validate=yes\n");
        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        String script = VpsSetupScripts.install(request, plan);

        assertTrue(script.contains("DOCKER_CADDY_TARGET='/opt/example-app/infra/caddy/Caddyfile'"));
        assertTrue(script.contains("DOCKER_CADDY_CONTAINER='example-app-caddy-1'"));
        assertTrue(script.contains("DOCKER_HOST_GATEWAY"));
        assertTrue(script.contains("LISTEN=\"${DOCKER_HOST_GATEWAY}:${INTERNAL_RELAY_PORT}\""));
        assertTrue(script.contains("DOCKER_CADDY_SUBNET"));
        assertTrue(script.contains("ufw allow in on \"$DOCKER_CADDY_BRIDGE\""));
        assertTrue(script.contains("write_file_in_place()"));
        assertTrue(script.contains("with open(dst, 'r+b') as df"));
        assertTrue(script.contains("docker cp \"$CADDY_TMP\" \"$DOCKER_CADDY_CONTAINER\":/tmp/tgproxy-caddy-validate"));
        assertTrue(script.contains("docker restart \"$DOCKER_CADDY_CONTAINER\""));
        assertTrue(script.contains("TGPROXY-RELAY relay.example.com /apiws"));
        assertTrue(script.contains("RELAY_PATH='/apiws'"));
        assertTrue(script.contains("handle /apiws/version"));
        assertTrue(script.contains("handle /apiws/capabilities"));
        assertTrue(script.contains("handle ' + relay_path + '*"));
        assertTrue(script.contains("caddy validate --config /tmp/tgproxy-caddy-validate"));
        assertTrue(script.contains("caddy reload --config /etc/caddy/Caddyfile"));
        assertFalse(script.contains("install -m 0644 \"$CADDY_TMP\" \"$DOCKER_CADDY_TARGET\""));
        assertFalse(script.contains("/etc/nginx/conf.d/tgproxy-relay-example_duckdns_org.conf"));
        assertFalse(script.contains("\nnginx_service_reload\n"));
        assertFalse(script.contains("ssh-secret"));
    }

    @Test
    public void dockerCaddyGatewayProbeDoesNotUseInstallerPositionalParameters() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build();
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "curl=yes\n"
                        + "tar=yes\n"
                        + "python3=yes\n"
                        + "docker=yes\n"
                        + "port_443=busy\n"
                        + "port_18080=free\n"
                        + "domain=relay.example.com\n"
                        + "domain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\n"
                        + "domain_points_to_vps=yes\n"
                        + "docker_caddy_domain_match_count=1\n"
                        + "docker_caddy_domain_matches=/opt/example-app/infra/caddy/Caddyfile\n"
                        + "docker_caddy_container=example-app-caddy-1\n"
                        + "docker_caddy_safe_embed=yes\n"
                        + "docker_caddy_path_exists=no\n"
                        + "docker_caddy_validate=yes\n");
        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        String script = VpsSetupScripts.install(request, plan);

        assertFalse(script.contains("$3"));
        assertTrue(script.contains("sed -n 's/^default.* via"));
    }

    @Test
    public void tlsDomainInstallCanEmbedIntoExistingHostCaddySite() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build();
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "curl=yes\n"
                        + "tar=yes\n"
                        + "python3=yes\n"
                        + "caddy=yes\n"
                        + "port_443=busy\n"
                        + "port_18080=free\n"
                        + "domain=relay.example.com\n"
                        + "domain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\n"
                        + "domain_points_to_vps=yes\n"
                        + "caddy_domain_match_count=1\n"
                        + "caddy_domain_matches=/etc/caddy/Caddyfile\n"
                        + "caddy_safe_embed=yes\n"
                        + "caddy_path_exists=no\n"
                        + "caddy_validate=yes\n");
        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        String script = VpsSetupScripts.install(request, plan);

        assertTrue(script.contains("CADDY_TARGET='/etc/caddy/Caddyfile'"));
        assertTrue(script.contains("TGPROXY-RELAY relay.example.com /apiws"));
        assertTrue(script.contains("handle /apiws/version"));
        assertTrue(script.contains("handle /apiws/capabilities"));
        assertTrue(script.contains("caddy validate --config \"$CADDY_TARGET\""));
        assertTrue(script.contains("caddy_service_reload \"$CADDY_TARGET\""));
        assertFalse(script.contains("\nnginx_service_reload\n"));
        assertFalse(script.contains("ssh-secret"));
    }

    @Test
    public void backupAndRollbackCoverCoreStateWithoutScanningUnrelatedSites() {
        String transaction = "12345678-test-transaction";
        String backup = VpsSetupScripts.backup(transaction);
        String rollback = VpsSetupScripts.rollback(transaction);

        assertTrue(backup.contains("$BACKUP_DIR/tgproxy-relay"));
        assertFalse(backup.contains("find /etc/nginx/sites-enabled /etc/nginx/sites-available"));
        assertFalse(backup.contains("find /etc/caddy /opt /srv /root /home"));
        assertTrue(backup.contains("path-map.tsv"));
        assertTrue(backup.contains("binary.absent"));
        assertTrue(backup.contains("config.absent"));
        assertTrue(backup.contains("service.absent"));
        assertTrue(backup.contains("ufw.was-active"));
        assertTrue(backup.contains("/etc/ufw/user.rules"));
        assertTrue(backup.contains("opt-dir.absent"));
        assertTrue(backup.contains("state-dir.absent"));
        assertTrue(backup.contains("$BACKUP_DIR/state-dir"));
        assertTrue(backup.contains("service-dropin.absent"));
        assertTrue(backup.contains("20-owner-state.conf"));
        assertTrue(backup.contains("user.absent"));
        assertTrue(backup.contains("group.absent"));
        assertTrue(backup.contains("txn-" + transaction));
        assertTrue(rollback.contains("txn-" + transaction));
        assertFalse(rollback.contains("ls -td /var/backups"));
        assertTrue(rollback.contains("install -m 0755 \"$LATEST/tgproxy-relay\""));
        assertTrue(rollback.contains("absent-paths.txt"));
        assertTrue(rollback.contains("mutation-paths.txt"));
        assertTrue(rollback.contains("$SUDO ufw reload"));
        assertTrue(rollback.contains("$SUDO rm -rf -- /opt/tgproxy-relay"));
        assertTrue(rollback.contains("$SUDO rm -rf -- /var/lib/tgproxy-relay"));
        assertTrue(rollback.contains("$LATEST/state-dir"));
        assertTrue(rollback.contains("service-dropin-dir.absent"));
        assertTrue(rollback.contains("20-owner-state.conf"));
        assertTrue(rollback.contains("$SUDO userdel tgproxy-relay"));
        assertTrue(rollback.contains("$SUDO groupdel tgproxy-relay"));
        assertTrue(rollback.contains("cat \"$LATEST/path-map.tsv\""));
        assertFalse(rollback.contains("for f in /etc/nginx/conf.d/tgproxy-relay-*.conf"));
        assertFalse(rollback.contains("for f in /etc/nginx/snippets/tgproxy-relay-*.conf"));
        assertTrue(rollback.contains("set -eu"));
        assertTrue(rollback.contains("systemctl disable --now tgproxy-relay"));
        assertTrue(rollback.contains("systemctl stop tgproxy-relay"));
        assertTrue(rollback.contains("/var/lib/docker/volumes/*"));
    }

    @Test
    public void transactionBackupTracksOnlyExactPlannedNginxMutations() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost("example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/private-ws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .build();
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\narch=x86_64\ncurl=yes\ntar=yes\nnginx=yes\n"
                        + "apache=no\ncaddy=no\nport_443=free\nport_18080=free\n"
                        + "domain=example.com\ndomain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\ndomain_points_to_vps=yes\n"
                        + "nginx_domain_match_count=0\ncert_exists=yes\n");
        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        String backup = VpsSetupScripts.backup(
                "12345678-exact-transaction", request, plan);

        assertTrue(backup.contains(
                "track_mutation '/etc/nginx/conf.d/tgproxy-relay-example_com.conf'"));
        assertTrue(backup.contains("$BACKUP_DIR/absent-paths.txt"));
        assertTrue(backup.contains("$BACKUP_DIR/reload-nginx"));
        assertFalse(backup.contains("track_mutation '/etc/nginx/conf.d/tgproxy-relay-*.conf'"));
    }
}
