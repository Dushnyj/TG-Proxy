package com.dushnyj.tgproxy;

import java.util.LinkedHashMap;
import java.util.Map;

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
                    VpsSetupScripts.backup(), BACKUP_TIMEOUT_MS);

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
                rollback(request, listener);
                throw new VpsSetupException(check.message());
            }

            if (relayStore != null) relayStore.saveRelay(relay, relay.profileKey());
            progress(listener, VpsSetupProgress.Stage.SAVE, 100,
                    "Relay проверен и сохранён в профиле");
            return relay;
        } catch (VpsSetupException e) {
            throw e;
        } catch (Exception e) {
            rollback(request, listener);
            throw new VpsSetupException(e.getMessage(), e);
        }
    }

    private String execute(VpsSetupRequest request, VpsSetupProgress.Stage stage,
                           String script, int timeoutMs) throws Exception {
        return sshClient.execute(request.sshCredentials(), stage, "sh -s", script, timeoutMs);
    }

    private void rollback(VpsSetupRequest request, Listener listener) {
        try {
            progress(listener, VpsSetupProgress.Stage.ROLLBACK, 95,
                    "Ошибка проверки, выполняется rollback");
            execute(request, VpsSetupProgress.Stage.ROLLBACK,
                    VpsSetupScripts.rollback(), ROLLBACK_TIMEOUT_MS);
        } catch (Exception ignored) {
        }
    }

    private void progress(Listener listener, VpsSetupProgress.Stage stage,
                          int percent, String message) {
        if (listener != null) listener.onProgress(VpsSetupProgress.of(stage, percent, message));
    }
}
