package com.dushnyj.tgproxy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class VpsAutoSetupWizard {
    private static final int AUDIT_TIMEOUT_MS = 30_000;
    private static final int BACKUP_TIMEOUT_MS = 30_000;
    private static final int INSTALL_TIMEOUT_MS = 180_000;
    private static final int ROLLBACK_TIMEOUT_MS = 60_000;

    interface Listener {
        void onProgress(VpsSetupProgress progress);
        boolean onPlan(VpsSetupPlan plan);
    }

    interface RelayVerifier {
        VpsRelayCheckResult check(VpsRelayConfig config, Map<Integer, String> dcRules);
    }

    private final VpsSshClient sshClient;
    private final RelayVerifier relayVerifier;
    private final VpsRelayStore relayStore;
    private final Map<Integer, String> dcRules;

    VpsAutoSetupWizard(VpsSshClient sshClient, RelayVerifier relayVerifier,
                       VpsRelayStore relayStore, Map<Integer, String> dcRules) {
        this.sshClient = sshClient;
        this.relayVerifier = relayVerifier;
        this.relayStore = relayStore;
        this.dcRules = dcRules == null ? new LinkedHashMap<>() : new LinkedHashMap<>(dcRules);
    }

    VpsRelayConfig run(VpsSetupRequest request, Listener listener) throws VpsSetupException {
        if (request == null || !request.isValid()) {
            throw new VpsSetupException("invalid VPS setup request");
        }
        boolean backupCompleted = false;
        String transactionId = UUID.randomUUID().toString();
        try {
            progress(listener, VpsSetupProgress.Stage.AUDIT, 5,
                    "Выполняется read-only аудит VPS");
            String auditText = execute(request, VpsSetupProgress.Stage.AUDIT,
                    VpsSetupScripts.audit(request), AUDIT_TIMEOUT_MS);
            VpsSetupAudit audit = VpsSetupAudit.parse(auditText);
            VpsSetupPlan plan = VpsSetupPlan.from(request, audit);
            progress(listener, VpsSetupProgress.Stage.PLAN, 25,
                    "План изменений готов");
            if (!plan.canApply()) throw new VpsSetupException(plan.summary());
            if (listener != null && !listener.onPlan(plan)) {
                throw new VpsSetupException("VPS setup cancelled");
            }

            progress(listener, VpsSetupProgress.Stage.BACKUP, 45,
                    "Создание backup перед изменениями");
            execute(request, VpsSetupProgress.Stage.BACKUP,
                    VpsSetupScripts.backup(transactionId, request, plan), BACKUP_TIMEOUT_MS);
            backupCompleted = true;

            progress(listener, VpsSetupProgress.Stage.INSTALL, 70,
                    "Установка tgproxy-relay и systemd unit");
            execute(request, VpsSetupProgress.Stage.INSTALL,
                    VpsSetupScripts.install(request, plan), INSTALL_TIMEOUT_MS);

            progress(listener, VpsSetupProgress.Stage.VERIFY, 90,
                    "Проверка /healthz, /version и /test-routes");
            VpsSetupRequest effectiveRequest = plan.effectiveRequest() == null
                    ? request
                    : plan.effectiveRequest();
            VpsRelayConfig relay = effectiveRequest.relayConfig();
            VpsRelayCheckResult check = relayVerifier.check(relay, dcRules);
            if (check.status() != VpsRelayCheckResult.Status.OK) {
                throw new VpsSetupException(check.message());
            }
            if (plan.installMode() != VpsSetupPlan.InstallMode.EXISTING_RELAY_ADD_TOKEN) {
                String expectedVersion = request.releaseVersion().isEmpty()
                        ? VpsSetupScripts.RELAY_VERSION : request.releaseVersion();
                if (!expectedVersion.equals(check.relayVersion())) {
                    throw new VpsSetupException("deployed Relay version mismatch: expected "
                            + expectedVersion + ", got "
                            + (check.relayVersion().isEmpty() ? "unknown" : check.relayVersion()));
                }
            }

            if (relayStore != null && relayStore.saveRelay(relay, relay.profileKey()) == null) {
                throw new VpsSetupException("VPS Relay verified, but settings could not be saved");
            }
            if (!check.warning().isEmpty()) {
                DiagnosticsLog.record("VPS Relay production ready; test environment advisory: "
                        + check.warning());
            }
            progress(listener, VpsSetupProgress.Stage.SAVE, 100,
                    check.warning().isEmpty()
                            ? "Relay проверен и сохранён в профиле"
                            : "Relay готов для обычного Telegram; тестовая среда частично недоступна");
            return relay;
        } catch (VpsSetupException e) {
            throw backupCompleted
                    ? rollbackAfterFailure(request, listener, transactionId, e) : e;
        } catch (Exception e) {
            VpsSetupException failure = new VpsSetupException(e.getMessage(), e);
            throw backupCompleted
                    ? rollbackAfterFailure(request, listener, transactionId, failure) : failure;
        }
    }

    private String execute(VpsSetupRequest request, VpsSetupProgress.Stage stage,
                           String script, int timeoutMs) throws Exception {
        return sshClient.execute(request.sshCredentials(), stage, "sh -s", script, timeoutMs);
    }

    private VpsSetupException rollbackAfterFailure(VpsSetupRequest request, Listener listener,
                                                   String transactionId,
                                                   VpsSetupException original) {
        try {
            progress(listener, VpsSetupProgress.Stage.ROLLBACK, 95,
                    "Ошибка проверки, выполняется rollback");
            execute(request, VpsSetupProgress.Stage.ROLLBACK,
                    VpsSetupScripts.rollback(transactionId), ROLLBACK_TIMEOUT_MS);
            return original;
        } catch (Exception rollbackError) {
            String detail = rollbackError.getMessage() == null
                    ? rollbackError.getClass().getSimpleName() : rollbackError.getMessage();
            progress(listener, VpsSetupProgress.Stage.ROLLBACK, 95,
                    "ROLLBACK_FAILED: " + detail);
            VpsSetupException combined = new VpsSetupException(
                    original.getMessage() + "; ROLLBACK_FAILED: " + detail, original);
            combined.addSuppressed(rollbackError);
            return combined;
        }
    }

    private void progress(Listener listener, VpsSetupProgress.Stage stage,
                          int percent, String message) {
        if (listener != null) listener.onProgress(VpsSetupProgress.of(stage, percent, message));
    }
}
