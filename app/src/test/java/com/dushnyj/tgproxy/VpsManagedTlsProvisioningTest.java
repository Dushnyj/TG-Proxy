package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsManagedTlsProvisioningTest {
    @Test
    public void ipProvisioningInstallsShortLivedCertificateAndRenewalTimer() {
        VpsSetupRequest request = request("203.0.113.10");
        VpsSetupPlan plan = VpsSetupPlan.from(request, cleanAudit("203.0.113.10"));

        String script = VpsSetupScripts.install(request, plan);
        String backup = VpsSetupScripts.backup("12345678-managed-ip", request, plan);
        String rollback = VpsSetupScripts.rollback("12345678-managed-ip");

        assertTrue(plan.canApply());
        assertTrue(script.contains("PUBLIC_URL='https://203.0.113.10:443/apiws'"));
        assertTrue(script.contains("--preferred-profile shortlived --ip-address \"$DOMAIN\""));
        assertTrue(script.contains("certbot>=5.4"));
        assertTrue(script.contains("tgproxy-certbot-renew.timer"));
        assertTrue(script.contains("OnUnitActiveSec=12h"));
        assertTrue(script.contains("ufw allow 80/tcp"));
        assertTrue(script.contains("proxy_request_buffering off"));
        assertFalse(script.contains("PUBLIC_URL='http://"));
        assertFalse(script.contains("ssh-secret"));
        assertTrue(backup.contains("track_mutation '/etc/systemd/system/tgproxy-certbot-renew.timer'"));
        assertTrue(backup.contains("$BACKUP_DIR/managed-tls"));
        assertTrue(rollback.contains("disable --now tgproxy-certbot-renew.timer"));
    }

    @Test
    public void domainProvisioningUsesDnsIdentifierInsteadOfIpFlag() {
        VpsSetupRequest request = request("relay.example.com");
        VpsSetupPlan plan = VpsSetupPlan.from(request, cleanAudit("relay.example.com"));

        String script = VpsSetupScripts.install(request, plan);

        assertTrue(plan.canApply());
        assertTrue(script.contains("--cert-name \"$DOMAIN\" -d \"$DOMAIN\""));
        assertFalse(script.contains("--ip-address"));
    }

    private static VpsSetupRequest request(String host) {
        return VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayHost(host)
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.2.0")
                .build();
    }

    private static VpsSetupAudit cleanAudit(String host) {
        return VpsSetupAudit.parse(
                "os=Ubuntu 24.04\nsystemd=yes\narch=x86_64\n"
                        + "curl=yes\ntar=yes\nnginx=no\napache=no\ncaddy=no\n"
                        + "port_80=free\nport_443=free\nport_18080=free\n"
                        + "domain=" + host + "\ndomain_ips=203.0.113.10\n"
                        + "public_ip=203.0.113.10\ndomain_points_to_vps=yes\n"
                        + "nginx_domain_match_count=0\ncert_exists=no\n"
                        + "package_manager=apt\nroot_or_passwordless_sudo=yes\n");
    }
}
