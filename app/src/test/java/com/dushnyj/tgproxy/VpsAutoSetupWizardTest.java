package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VpsAutoSetupWizardTest {
    @Test
    public void setupReportsProgressAndSavesVerifiedRelayForProfile() throws Exception {
        FakeSshClient ssh = new FakeSshClient("systemd=yes\narch=x86_64\ncurl=yes\nport_18080=free\n");
        VpsRelayStore store = VpsRelayStore.inMemory();
        ArrayList<Integer> percents = new ArrayList<>();
        ArrayList<String> messages = new ArrayList<>();
        VpsSetupRequest request = directRequest("mobile:mccmnc:25020");

        VpsAutoSetupWizard wizard = new VpsAutoSetupWizard(
                ssh, (config, dcRules) -> VpsRelayCheckResult.ok("{}", "1.0.0"),
                store, dcRules());

        VpsRelayConfig saved = wizard.run(request, new VpsAutoSetupWizard.Listener() {
            @Override public void onProgress(VpsSetupProgress progress) {
                percents.add(progress.percent());
                messages.add(progress.message());
            }

            @Override public boolean onPlan(VpsSetupPlan plan) {
                return true;
            }
        });

        assertEquals(Arrays.asList(
                VpsSetupProgress.Stage.AUDIT,
                VpsSetupProgress.Stage.BACKUP,
                VpsSetupProgress.Stage.INSTALL), ssh.stages);
        assertEquals(Integer.valueOf(5), percents.get(0));
        assertEquals(Integer.valueOf(100), percents.get(percents.size() - 1));
        assertTrue(messages.get(0).contains("аудит"));
        assertEquals("relay.example.com", saved.host());
        VpsRelayConfig selected = store.selectedRelay("mobile:mccmnc:25020");
        assertNotNull(selected);
        assertEquals("relay.example.com", selected.host());
        assertTrue(selected.isAllowedForProfile("mobile:mccmnc:25020"));
        assertFalse(selected.isAllowedForProfile("wifi:ssid:home"));
    }

    @Test
    public void verificationFailureRunsRollbackAndDoesNotSaveRelay() throws Exception {
        FakeSshClient ssh = new FakeSshClient("systemd=yes\narch=x86_64\ncurl=yes\nport_18080=free\n");
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsSetupRequest request = directRequest("wifi:ssid:home");
        VpsAutoSetupWizard wizard = new VpsAutoSetupWizard(
                ssh,
                (config, dcRules) -> VpsRelayCheckResult.of(
                        VpsRelayCheckResult.Status.UNAVAILABLE, "timeout"),
                store,
                dcRules());

        try {
            wizard.run(request, approvingListener());
        } catch (VpsSetupException expected) {
            assertTrue(expected.getMessage().contains("timeout"));
        }

        assertTrue(ssh.stages.contains(VpsSetupProgress.Stage.ROLLBACK));
        assertEquals(null, store.selectedRelay("wifi:ssid:home"));
    }

    @Test
    public void testEnvironmentWarningDoesNotRollbackProductionReadyRelay() throws Exception {
        FakeSshClient ssh = new FakeSshClient(
                "systemd=yes\narch=x86_64\ncurl=yes\nport_18080=free\n");
        VpsRelayStore store = VpsRelayStore.inMemory();
        ArrayList<String> progressMessages = new ArrayList<>();
        VpsAutoSetupWizard wizard = new VpsAutoSetupWizard(
                ssh,
                (config, rules) -> VpsRelayCheckResult.ok("{}", "1.0.0",
                        "test DC3 main=telegram probe deadline exceeded"),
                store,
                dcRules());

        VpsRelayConfig saved = wizard.run(directRequest("wifi:ssid:home"),
                new VpsAutoSetupWizard.Listener() {
                    @Override public void onProgress(VpsSetupProgress progress) {
                        progressMessages.add(progress.message());
                    }

                    @Override public boolean onPlan(VpsSetupPlan plan) {
                        return true;
                    }
                });

        assertNotNull(saved);
        assertFalse(ssh.stages.contains(VpsSetupProgress.Stage.ROLLBACK));
        assertNotNull(store.selectedRelay("wifi:ssid:home"));
        assertTrue(progressMessages.get(progressMessages.size() - 1)
                .contains("тестовая среда"));
    }

    @Test
    public void successfulEndpointsWithWrongDeployedVersionAreRolledBack() throws Exception {
        FakeSshClient ssh = new FakeSshClient(
                "systemd=yes\narch=x86_64\ncurl=yes\nport_18080=free\n");
        VpsAutoSetupWizard wizard = new VpsAutoSetupWizard(
                ssh, (config, rules) -> VpsRelayCheckResult.ok("{}", "0.9.9"),
                VpsRelayStore.inMemory(), dcRules());

        try {
            wizard.run(directRequest("wifi:ssid:home"), approvingListener());
            throw new AssertionError("wrong deployed version was accepted");
        } catch (VpsSetupException expected) {
            assertTrue(expected.getMessage().contains("version mismatch"));
        }
        assertTrue(ssh.stages.contains(VpsSetupProgress.Stage.ROLLBACK));
    }

    @Test
    public void installCommandFailureRunsRollbackAfterCompletedBackup() throws Exception {
        ArrayList<VpsSetupProgress.Stage> stages = new ArrayList<>();
        VpsSshClient ssh = (credentials, stage, command, stdin, timeoutMs) -> {
            stages.add(stage);
            if (stage == VpsSetupProgress.Stage.AUDIT) {
                return "systemd=yes\narch=x86_64\ncurl=yes\nport_18080=free\n";
            }
            if (stage == VpsSetupProgress.Stage.INSTALL) {
                throw new VpsSetupException("relay_start_failed");
            }
            return "";
        };
        VpsAutoSetupWizard wizard = new VpsAutoSetupWizard(
                ssh, (config, rules) -> VpsRelayCheckResult.ok("{}"),
                VpsRelayStore.inMemory(), dcRules());

        try {
            wizard.run(directRequest("wifi:ssid:home"), approvingListener());
            throw new AssertionError("install failure was accepted");
        } catch (VpsSetupException expected) {
            assertTrue(expected.getMessage().contains("relay_start_failed"));
        }

        assertEquals(Arrays.asList(
                VpsSetupProgress.Stage.AUDIT,
                VpsSetupProgress.Stage.BACKUP,
                VpsSetupProgress.Stage.INSTALL,
                VpsSetupProgress.Stage.ROLLBACK), stages);
    }

    @Test
    public void rollbackFailureIsReportedInsteadOfBeingSwallowed() throws Exception {
        VpsSshClient ssh = (credentials, stage, command, stdin, timeoutMs) -> {
            if (stage == VpsSetupProgress.Stage.AUDIT) {
                return "systemd=yes\narch=x86_64\ncurl=yes\nport_18080=free\n";
            }
            if (stage == VpsSetupProgress.Stage.INSTALL) {
                throw new VpsSetupException("install_failed");
            }
            if (stage == VpsSetupProgress.Stage.ROLLBACK) {
                throw new VpsSetupException("restore_failed");
            }
            return "";
        };
        VpsAutoSetupWizard wizard = new VpsAutoSetupWizard(
                ssh, (config, rules) -> VpsRelayCheckResult.ok("{}", "1.0.0"),
                VpsRelayStore.inMemory(), dcRules());

        try {
            wizard.run(directRequest("wifi:ssid:home"), approvingListener());
            throw new AssertionError("rollback failure was hidden");
        } catch (VpsSetupException expected) {
            assertTrue(expected.getMessage().contains("install_failed"));
            assertTrue(expected.getMessage().contains("ROLLBACK_FAILED"));
            assertTrue(expected.getMessage().contains("restore_failed"));
        }
    }

    @Test
    public void existingRelaySetupAddsTokenWithoutFullReinstall() throws Exception {
        FakeSshClient ssh = new FakeSshClient(
                "systemd=yes\n"
                        + "arch=x86_64\n"
                        + "python3=yes\n"
                        + "existing_relay=yes\n"
                        + "existing_relay_config=/etc/tgproxy-relay/config.json\n"
                        + "existing_relay_public_url=https://example.com/apiws\n"
                        + "existing_relay_listen=127.0.0.1:18080\n");
        VpsRelayStore store = VpsRelayStore.inMemory();
        VpsSetupRequest request = tlsRequest("mobile:mccmnc:25001");
        VpsAutoSetupWizard wizard = new VpsAutoSetupWizard(
                ssh, (config, dcRules) -> VpsRelayCheckResult.ok("{}"), store, dcRules());

        wizard.run(request, approvingListener());

        int installIndex = ssh.stages.indexOf(VpsSetupProgress.Stage.INSTALL);
        assertTrue(installIndex >= 0);
        String installScript = ssh.stdinByStage.get(installIndex);
        assertTrue(installScript.contains("EXISTING_CONFIG='/etc/tgproxy-relay/config.json'"));
        assertTrue(installScript.contains("systemctl restart tgproxy-relay"));
        assertFalse(installScript.contains("tar -xzf"));
        assertNotNull(store.selectedRelay("mobile:mccmnc:25001"));
    }

    @Test
    public void existingRelaySetupVerifiesExistingPublicUrlWhenRelayHostWasEmpty() throws Exception {
        FakeSshClient ssh = new FakeSshClient(
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
                        + "docker_caddy_path_exists=yes\n"
                        + "docker_caddy_validate=yes\n");
        VpsRelayStore store = VpsRelayStore.inMemory();
        final VpsRelayConfig[] checked = new VpsRelayConfig[1];
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshPort(22)
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayName("Work VPS")
                .relayHost("")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("new-device-token")
                .releaseVersion("1.0.0")
                .profileKey("wifi:ssid:home")
                .build();
        VpsAutoSetupWizard wizard = new VpsAutoSetupWizard(
                ssh,
                (config, dcRules) -> {
                    checked[0] = config;
                    return VpsRelayCheckResult.ok("{}");
                },
                store,
                dcRules());

        VpsRelayConfig saved = wizard.run(request, approvingListener());

        assertNotNull(checked[0]);
        assertEquals("relay.example.com", checked[0].host());
        assertEquals(443, checked[0].port());
        assertTrue(checked[0].tls());
        assertEquals("relay.example.com", saved.host());
        assertEquals("relay.example.com", store.selectedRelay("wifi:ssid:home").host());
    }

    private static VpsAutoSetupWizard.Listener approvingListener() {
        return new VpsAutoSetupWizard.Listener() {
            @Override public void onProgress(VpsSetupProgress progress) {}
            @Override public boolean onPlan(VpsSetupPlan plan) { return true; }
        };
    }

    private static VpsSetupRequest directRequest(String profileKey) {
        return VpsSetupRequest.builder()
                .sshHost("vps.example.com")
                .sshPort(22)
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayName("Work VPS")
                .relayHost("relay.example.com")
                .relayPort(18080)
                .relayTls(false)
                .relayPath("/apiws")
                .relayToken("relay-token")
                .releaseVersion("1.0.0")
                .profileKey(profileKey)
                .build();
    }

    private static VpsSetupRequest tlsRequest(String profileKey) {
        return VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshPort(22)
                .sshUser("root")
                .sshPassword("ssh-secret")
                .relayName("Work VPS")
                .relayHost("example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("new-device-token")
                .releaseVersion("1.0.0")
                .profileKey(profileKey)
                .build();
    }

    private static Map<Integer, String> dcRules() {
        LinkedHashMap<Integer, String> rules = new LinkedHashMap<>();
        rules.put(2, "149.154.167.220");
        rules.put(4, "149.154.167.220");
        return rules;
    }

    private static final class FakeSshClient implements VpsSshClient {
        final List<VpsSetupProgress.Stage> stages = new ArrayList<>();
        final List<String> stdinByStage = new ArrayList<>();
        private final String audit;

        FakeSshClient(String audit) {
            this.audit = audit;
        }

        @Override
        public String execute(VpsSshCredentials credentials, VpsSetupProgress.Stage stage,
                              String command, String stdin, int timeoutMs) {
            stages.add(stage);
            stdinByStage.add(stdin == null ? "" : stdin);
            return stage == VpsSetupProgress.Stage.AUDIT ? audit : "";
        }
    }
}

