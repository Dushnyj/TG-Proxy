package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsSetupScriptsTest {
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
        assertTrue(script.contains("systemctl enable --now tgproxy-relay"));
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
        assertTrue(script.contains("systemctl restart tgproxy-relay"));
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
        assertTrue(script.contains("install -m 0755 \"$TMPDIR/tgproxy-relay\" /opt/tgproxy-relay/tgproxy-relay"));
        assertTrue(script.contains("EXISTING_CONFIG='/etc/tgproxy-relay/config.json'"));
        assertTrue(script.contains("json.load"));
        assertTrue(script.contains("systemctl restart tgproxy-relay"));
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
        assertTrue(script.contains("TGPROXY-RELAY relay.example.com /apiws"));
        assertTrue(script.contains("RELAY_PATH='/apiws'"));
        assertTrue(script.contains("handle /apiws/version"));
        assertTrue(script.contains("handle ' + relay_path + '*"));
        assertTrue(script.contains("caddy validate --config /etc/caddy/Caddyfile"));
        assertTrue(script.contains("caddy reload --config /etc/caddy/Caddyfile"));
        assertFalse(script.contains("/etc/nginx/conf.d/tgproxy-relay-slovofon_duckdns_org.conf"));
        assertFalse(script.contains("systemctl reload nginx"));
        assertFalse(script.contains("ssh-secret"));
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
        assertTrue(script.contains("caddy validate --config \"$CADDY_TARGET\""));
        assertTrue(script.contains("caddy reload --config \"$CADDY_TARGET\""));
        assertFalse(script.contains("systemctl reload nginx"));
        assertFalse(script.contains("ssh-secret"));
    }
}

