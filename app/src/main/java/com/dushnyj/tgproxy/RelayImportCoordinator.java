package com.dushnyj.tgproxy;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** One validation-first import path for pasted text, files, QR codes and external intents. */
final class RelayImportCoordinator {
    interface Callback {
        void onImported(VpsRelayStore.Record record);
    }

    private final Activity activity;
    private final String profileKey;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    RelayImportCoordinator(Activity activity, String profileKey, Callback callback) {
        this.activity = activity;
        this.profileKey = clean(profileKey);
        this.callback = callback;
    }

    void showPasteDialog() {
        EditText input = field(activity.getString(R.string.import_payload_hint), true);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.vps_connections_add_text)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.import_settings,
                        (dialog, which) -> importRaw(input.getText().toString(), ""))
                .show();
    }

    void importRaw(String raw, String password) {
        String payload = clean(raw);
        if (payload.isEmpty() || payload.length() > SettingsTransfer.MAX_IMPORT_CHARS) {
            showParseError(activity.getString(payload.length() > SettingsTransfer.MAX_IMPORT_CHARS
                    ? R.string.import_error_too_large : R.string.import_error_invalid));
            return;
        }
        try {
            SettingsTransfer.Imported imported = SettingsTransfer.isImportLink(payload)
                    ? SettingsTransfer.parseDeepLink(payload, password)
                    : SettingsTransfer.parse(payload, password);
            if (imported.kind() != SettingsTransfer.Kind.VPS_RELAY) {
                throw new SettingsTransferException("not a VPS Relay connection");
            }
            confirm(imported.data().relayConfig());
        } catch (SettingsTransferException error) {
            if (payload.startsWith("TGPROXY-ENC-v1") && clean(password).isEmpty()) {
                showPassword(payload);
            } else {
                showParseError(activity.getString(SettingsTransferErrorText.messageRes(error)));
            }
        }
    }

    private void showPassword(String payload) {
        EditText password = field(activity.getString(R.string.import_password_hint), false);
        password.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.import_password_hint)
                .setView(password)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.import_settings,
                        (dialog, which) -> importRaw(payload, password.getText().toString()))
                .show();
    }

    private void confirm(VpsRelayConfig relay) {
        String suffix = relay.path().isEmpty() ? "" : relay.path();
        String message = activity.getString(R.string.vps_connection_import_preview,
                relay.name(), relay.host(), relay.port(), suffix, relay.maskedToken());
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.import_relay_add_title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.import_settings,
                        (dialog, which) -> chooseScope(relay))
                .show();
    }

    private void chooseScope(VpsRelayConfig relay) {
        if (profileKey.isEmpty()) {
            validateAndSave(relay, "");
            return;
        }
        String[] labels = {
                activity.getString(R.string.vps_connection_import_current),
                activity.getString(R.string.vps_connection_import_all)
        };
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.vps_connection_import_scope_title)
                .setItems(labels, (dialog, which) ->
                        validateAndSave(relay, which == 0 ? profileKey : ""))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void validateAndSave(VpsRelayConfig relay, String targetProfile) {
        AlertDialog progress = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.import_relay_checking)
                .setMessage(activity.getString(R.string.vps_connection_test_note))
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(() -> {
            VpsRelayCheckResult result = new VpsRelayClient().check(
                    relay.withEnabled(true), MtProtoConfig.relayDcRules());
            main.post(() -> {
                if (activity.isFinishing()) return;
                progress.dismiss();
                if (result.status() != VpsRelayCheckResult.Status.OK) {
                    showCheckResult(result);
                    return;
                }
                VpsRelayConfig verified = relay.withEnabled(true)
                        .withCapabilities(result.capabilities())
                        .withInstanceId(result.instanceId());
                VpsRelayStore.Record saved = VpsRelayStore.fromContext(activity)
                        .saveUsableRelay(verified, targetProfile);
                if (saved == null) {
                    showParseError(activity.getString(R.string.settings_save_failed));
                    return;
                }
                Toast.makeText(activity, R.string.vps_connection_import_success,
                        Toast.LENGTH_SHORT).show();
                if (callback != null) callback.onImported(saved);
            });
        }, "tg-relay-import").start();
    }

    void showCheckResult(VpsRelayCheckResult result) {
        boolean ok = result != null && result.status() == VpsRelayCheckResult.Status.OK;
        int message = ok ? R.string.vps_connection_check_ok : messageFor(result);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(ok ? R.string.vps_connection_check_title_ok
                        : R.string.vps_connection_check_title_error)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private int messageFor(VpsRelayCheckResult result) {
        if (result == null) return R.string.vps_connection_check_unavailable;
        switch (result.status()) {
            case BAD_CONFIG: return R.string.vps_connection_check_bad_config;
            case WRONG_TOKEN: return R.string.vps_connection_check_wrong_token;
            case TLS_ERROR: return R.string.vps_connection_check_tls;
            case OUTDATED_VERSION: return R.string.vps_connection_check_outdated;
            case UNAVAILABLE:
            default: return R.string.vps_connection_check_unavailable;
        }
    }

    private void showParseError(String message) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.import_failed_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private EditText field(String hint, boolean multiline) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setTextColor(androidx.core.content.ContextCompat.getColor(
                activity, R.color.text_primary));
        input.setHintTextColor(androidx.core.content.ContextCompat.getColor(
                activity, R.color.text_hint));
        input.setBackgroundResource(R.drawable.edit_bg);
        int horizontal = Math.round(14 * activity.getResources().getDisplayMetrics().density);
        input.setPadding(horizontal, 0, horizontal, 0);
        input.setSingleLine(!multiline);
        input.setMinHeight(Math.round((multiline ? 112 : 54)
                * activity.getResources().getDisplayMetrics().density));
        if (multiline) input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        return input;
    }

    private static String firstLine(String value) {
        String clean = clean(value);
        int newline = clean.indexOf('\n');
        return newline < 0 ? clean : clean.substring(0, newline);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
