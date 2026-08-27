package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsSetupPlanTest {
    @Test
    public void alpineOpenRcArmServerIsSupportedWithoutSystemd() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "kernel=linux\n"
                        + "os=Alpine Linux 3.22\n"
                        + "init_system=openrc\n"
                        + "systemd=no\n"
                        + "arch=armv7l\n"
                        + "package_manager=apk\n"
                        + "port_18080=free\n");
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("vps.example.com")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(18080)
                .relayTls(false)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.summary(), plan.canApply());
        assertTrue(plan.summary().contains("Alpine Linux 3.22"));
        assertTrue(plan.summary().contains("init=openrc"));
        assertTrue(plan.summary().contains("службу автозапуска для openrc"));
    }

    @Test
    public void nonLinuxServerIsRejectedBeforeMutation() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "kernel=freebsd\n"
                        + "os=FreeBSD\n"
                        + "init_system=portable\n"
                        + "arch=amd64\n"
                        + "port_18080=free\n");
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("vps.example.com")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(18080)
                .relayTls(false)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertFalse(plan.canApply());
        assertTrue(plan.blockingSummary().contains("не является Linux"));
    }

    @Test
    public void refusesToAutoChangeBusyTlsSitePort() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "nginx=yes\n"
                        + "caddy=no\n"
                        + "apache=no\n"
                        + "port_443=busy\n");
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("vps.example.com")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertFalse(plan.canApply());
        assertTrue(plan.summary().contains("443"));
        assertTrue(plan.summary().contains("DNS домена"));
        assertTrue(plan.blockingSummary().contains("DNS домена"));
        assertFalse(plan.blockingSummary().startsWith("Read-only audit:"));
    }

    @Test
    public void directAlternativePortHasBackupInstallAndVerifySteps() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=aarch64\n"
                        + "nginx=no\n"
                        + "caddy=no\n"
                        + "apache=no\n"
                        + "curl=yes\n"
                        + "port_18080=free\n");
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("vps.example.com")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(18080)
                .relayTls(false)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.canApply());
        assertTrue(plan.summary().contains("backup"));
        assertTrue(plan.summary().contains("tgproxy-relay"));
        assertTrue(plan.summary().contains("/healthz"));
    }

    @Test
    public void installsMissingDownloaderFromOperatingSystemPackages() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "curl=no\n"
                        + "wget=no\n"
                        + "port_18080=free\n");
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("vps.example.com")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(18080)
                .relayTls(false)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.canApply());
        assertTrue(plan.summary().contains("curl"));
    }

    @Test
    public void refusesTlsDomainWhenDnsDoesNotPointToVps() {
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
                        + "domain=relay.example.com\n"
                        + "domain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\n"
                        + "domain_points_to_vps=no\n"
                        + "nginx_domain_match_count=0\n"
                        + "cert_exists=no\n");
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertFalse(plan.canApply());
        assertTrue(plan.summary().contains("DNS"));
        assertTrue(plan.summary().contains("203.0.113.10"));
    }

    @Test
    public void tlsDomainCanCreateDedicatedNginxBlockWhenDnsAndCertificateAreSafe() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "os=Ubuntu 24.04\n"
                        + "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "curl=yes\n"
                        + "tar=yes\n"
                        + "nginx=yes\n"
                        + "apache=no\n"
                        + "caddy=no\n"
                        + "port_443=busy\n"
                        + "port_18080=free\n"
                        + "domain=relay.example.com\n"
                        + "domain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\n"
                        + "domain_points_to_vps=yes\n"
                        + "nginx_domain_match_count=0\n"
                        + "cert_exists=yes\n");
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.canApply());
        assertTrue(plan.summary().contains("Ubuntu 24.04"));
        assertTrue(plan.summary().contains("DNS: relay.example.com -> 203.0.113.10"));
        assertTrue(plan.summary().contains("nginx и сертификат уже готовы"));
        assertTrue(plan.summary().contains("127.0.0.1:18080"));
    }

    @Test
    public void cleanVpsCanIssueAndRenewDomainCertificateAutomatically() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "os=Ubuntu 24.04\n"
                        + "systemd=yes\narch=x86_64\ncurl=yes\ntar=yes\n"
                        + "nginx=no\napache=no\ncaddy=no\n"
                        + "port_80=free\nport_443=free\nport_18080=free\n"
                        + "domain=relay.example.com\ndomain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\ndomain_points_to_vps=yes\n"
                        + "nginx_domain_match_count=0\ncert_exists=no\n"
                        + "package_manager=apt\nroot_or_passwordless_sudo=yes\n");
        VpsSetupRequest request = tlsRequest("relay.example.com");

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.summary(), plan.canApply());
        assertEquals(VpsSetupPlan.InstallMode.NGINX_MANAGED_TLS, plan.installMode());
        assertTrue(plan.summary().contains("Certbot"));
        assertTrue(plan.summary().contains("автоматическое продление"));
    }

    @Test
    public void publicIpCanUseShortLivedManagedCertificate() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "os=Ubuntu 24.04\n"
                        + "systemd=yes\narch=x86_64\ncurl=yes\ntar=yes\n"
                        + "nginx=no\napache=no\ncaddy=no\n"
                        + "port_80=free\nport_443=free\nport_18080=free\n"
                        + "domain=203.0.113.10\ndomain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\ndomain_points_to_vps=yes\n"
                        + "nginx_domain_match_count=0\ncert_exists=no\n"
                        + "package_manager=apt\nroot_or_passwordless_sudo=yes\n");
        VpsSetupRequest request = tlsRequest("203.0.113.10");

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.summary(), plan.canApply());
        assertEquals(VpsSetupPlan.InstallMode.NGINX_MANAGED_TLS, plan.installMode());
        assertTrue(plan.summary().contains("IP-сертификат"));
    }

    private static VpsSetupRequest tlsRequest(String host) {
        return VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost(host)
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();
    }

    @Test
    public void tlsDomainCanEmbedLocationIntoSimpleExistingNginxBlock() {
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
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.canApply());
        assertTrue(plan.summary().contains("встроить location /apiws"));
        assertTrue(plan.summary().contains("/etc/nginx/sites-enabled/example.com.conf"));
        assertEquals(VpsSetupPlan.InstallMode.NGINX_EXISTING_LOCATION, plan.installMode());
    }

    @Test
    public void tlsDomainCanEmbedIntoDockerCaddySiteWhenDomainLivesInContainerStack() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "os=Ubuntu 24.04\n"
                        + "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "curl=yes\n"
                        + "tar=yes\n"
                        + "python3=yes\n"
                        + "nginx=yes\n"
                        + "apache=yes\n"
                        + "caddy=no\n"
                        + "docker=yes\n"
                        + "port_443=busy\n"
                        + "port_18080=free\n"
                        + "domain=relay.example.com\n"
                        + "domain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\n"
                        + "domain_points_to_vps=yes\n"
                        + "nginx_domain_match_count=0\n"
                        + "cert_exists=no\n"
                        + "docker_caddy_domain_match_count=1\n"
                        + "docker_caddy_domain_matches=/opt/example-app/infra/caddy/Caddyfile\n"
                        + "docker_caddy_container=example-app-caddy-1\n"
                        + "docker_caddy_safe_embed=yes\n"
                        + "docker_caddy_path_exists=no\n"
                        + "docker_caddy_validate=yes\n");
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.summary(), plan.canApply());
        assertEquals(VpsSetupPlan.InstallMode.DOCKER_CADDY_EXISTING_SITE, plan.installMode());
        assertEquals("/opt/example-app/infra/caddy/Caddyfile", plan.targetPath());
        assertTrue(plan.summary().contains("Docker Caddy"));
        assertTrue(plan.summary().contains("example-app-caddy-1"));
        assertTrue(plan.summary().contains("Docker gateway"));
    }

    @Test
    public void dockerCaddyEmbedRefusesToOverwriteExistingRelayPath() {
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
                        + "docker_caddy_path_exists=yes\n"
                        + "docker_caddy_validate=yes\n");
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertFalse(plan.canApply());
        assertTrue(plan.summary().contains("уже найден в Docker Caddy"));
    }

    @Test
    public void tlsDomainCanEmbedIntoHostCaddySiteWhenValidatePasses() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "curl=yes\n"
                        + "tar=yes\n"
                        + "python3=yes\n"
                        + "nginx=no\n"
                        + "apache=no\n"
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
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.summary(), plan.canApply());
        assertEquals(VpsSetupPlan.InstallMode.CADDY_EXISTING_SITE, plan.installMode());
        assertEquals("/etc/caddy/Caddyfile", plan.targetPath());
        assertTrue(plan.summary().contains("Caddy"));
        assertTrue(plan.summary().contains("/apiws"));
    }

    @Test
    public void existingRelayCanAddNewDeviceTokenWithoutFullReinstall() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "python3=yes\n"
                        + "existing_relay=yes\n"
                        + "existing_relay_config=/etc/tgproxy-relay/config.json\n"
                        + "existing_relay_public_url=https://example.com/apiws\n"
                        + "existing_relay_listen=127.0.0.1:18080\n");
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("new-device-token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.canApply());
        assertEquals(VpsSetupPlan.InstallMode.EXISTING_RELAY_ADD_TOKEN, plan.installMode());
        assertTrue(plan.summary().contains("Relay уже установлен"));
        assertTrue(plan.summary().contains("добавить новый token"));
    }

    @Test
    public void existingRelayWithActiveNginxRouteAcceptsIpSetupFallback() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "python3=yes\n"
                        + "existing_relay=yes\n"
                        + "existing_relay_config=/etc/tgproxy-relay/config.json\n"
                        + "existing_relay_public_url=https://relay.example.com:443/apiws\n"
                        + "existing_relay_listen=127.0.0.1:18080\n"
                        + "domain=relay.example.com\n"
                        + "domain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\n"
                        + "domain_points_to_vps=yes\n"
                        + "nginx_domain_match_count=1\n"
                        + "nginx_domain_matches=/etc/nginx/conf.d/tgproxy-relay.conf\n"
                        + "nginx_safe_embed=yes\n"
                        + "nginx_path_exists=yes\n");
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("203.0.113.10")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("new-device-token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.summary(), plan.canApply());
        assertEquals(VpsSetupPlan.InstallMode.EXISTING_RELAY_ADD_TOKEN, plan.installMode());
        assertEquals("relay.example.com", plan.effectiveRequest().relayHost());
        assertTrue(plan.summary().contains("уже найден"));
    }

    @Test
    public void existingRelayRepairsMissingDockerCaddyRouteWhenEndpointMatches() {
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
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("203.0.113.10")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("new-device-token")
                .releaseVersion("1.0.0")
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.summary(), plan.canApply());
        assertEquals(VpsSetupPlan.InstallMode.EXISTING_RELAY_ADD_TOKEN, plan.installMode());
        assertEquals(VpsSetupPlan.InstallMode.DOCKER_CADDY_EXISTING_SITE, plan.routeRepairMode());
        assertEquals("/opt/example-app/infra/caddy/Caddyfile", plan.routeTargetPath());
        assertEquals("example-app-caddy-1", plan.routeTargetContainer());
        assertTrue(plan.summary().contains("восстановить public route"));
    }

    @Test
    public void existingRelayCanUpdateBinaryWhenUserConfirmedServerUpdate() {
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
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("existing-phone-token")
                .releaseVersion("1.0.0")
                .updateExistingRelay(true)
                .build();

        VpsSetupPlan plan = VpsSetupPlan.from(request, audit);

        assertTrue(plan.canApply());
        assertEquals(VpsSetupPlan.InstallMode.EXISTING_RELAY_UPDATE, plan.installMode());
        assertTrue(plan.summary().contains("обновить tgproxy-relay до 1.0.0"));
        assertTrue(plan.summary().contains("сохранить существующий config"));
    }
}

