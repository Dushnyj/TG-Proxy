package com.dushnyj.tgproxy;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
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
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_relay_paste, null, false);
        EditText input = root.findViewById(R.id.et_relay_import_payload);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setView(root).create();
        dialog.setCanceledOnTouchOutside(false);
        root.findViewById(R.id.btn_relay_paste_cancel).setOnClickListener(view -> dialog.dismiss());
        root.findViewById(R.id.btn_relay_paste_import).setOnClickListener(view -> {
            String value = clean(input.getText().toString());
            if (value.isEmpty()) {
                input.setError(activity.getString(R.string.import_error_invalid));
                return;
            }
            dialog.dismiss();
            importRaw(value, "");
        });
        dialog.show();
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
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.import_settings,
                        (dialog, which) -> importRaw(payload, password.getText().toString()))
                .show();
    }

    private void confirm(VpsRelayConfig relay) {
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_relay_preview, null, false);
        ((TextView) root.findViewById(R.id.tv_relay_preview_name)).setText(relay.name());
        ((TextView) root.findViewById(R.id.tv_relay_preview_endpoint)).setText(endpoint(relay));
        ((TextView) root.findViewById(R.id.tv_relay_preview_security)).setText(
                relay.tls() ? R.string.vps_connection_tls : R.string.vps_connection_plain);
        ((TextView) root.findViewById(R.id.tv_relay_preview_token)).setText(
                activity.getString(R.string.vps_connections_token, relay.maskedToken()));
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setView(root).create();
        dialog.setCanceledOnTouchOutside(false);
        root.findViewById(R.id.btn_relay_preview_cancel).setOnClickListener(view -> dialog.dismiss());
        root.findViewById(R.id.btn_relay_preview_continue).setOnClickListener(view -> {
            dialog.dismiss();
            chooseScope(relay);
        });
        dialog.show();
    }

    private void chooseScope(VpsRelayConfig relay) {
        if (profileKey.isEmpty()) {
            validateAndSave(relay, "");
            return;
        }
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_relay_scope, null, false);
        LinearLayout options = root.findViewById(R.id.content_relay_scope_options);
        View current = addScopeOption(options, R.string.vps_connection_import_current,
                R.string.relay_scope_current_note);
        View all = addScopeOption(options, R.string.vps_connection_import_all,
                R.string.relay_scope_all_note);
        LinearLayout.LayoutParams allParams = (LinearLayout.LayoutParams) all.getLayoutParams();
        allParams.topMargin = dp(10);
        all.setLayoutParams(allParams);
        final boolean[] currentSelected = {true};
        setScopeSelected(current, true);
        setScopeSelected(all, false);
        current.setOnClickListener(view -> {
            currentSelected[0] = true;
            setScopeSelected(current, true);
            setScopeSelected(all, false);
        });
        all.setOnClickListener(view -> {
            currentSelected[0] = false;
            setScopeSelected(current, false);
            setScopeSelected(all, true);
        });
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setView(root).create();
        dialog.setCanceledOnTouchOutside(false);
        root.findViewById(R.id.btn_relay_scope_cancel).setOnClickListener(view -> dialog.dismiss());
        root.findViewById(R.id.btn_relay_scope_continue).setOnClickListener(view -> {
            String target = currentSelected[0] ? profileKey : "";
            dialog.dismiss();
            validateAndSave(relay, target);
        });
        dialog.show();
    }

    private View addScopeOption(LinearLayout parent, int titleRes, int noteRes) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_relay_scope, parent, false);
        ((TextView) row.findViewById(R.id.tv_relay_scope_title)).setText(titleRes);
        ((TextView) row.findViewById(R.id.tv_relay_scope_note)).setText(noteRes);
        row.setContentDescription(activity.getString(titleRes) + ". " + activity.getString(noteRes));
        parent.addView(row);
        return row;
    }

    private void setScopeSelected(View row, boolean selected) {
        row.setSelected(selected);
        ImageView icon = row.findViewById(R.id.iv_relay_scope_icon);
        if (selected) {
            icon.setImageResource(R.drawable.ic_status_check);
            icon.setImageTintList(ContextCompat.getColorStateList(activity, R.color.accent));
            icon.setBackgroundResource(R.drawable.dialog_icon_bg);
        } else {
            icon.setImageDrawable(null);
            icon.setBackgroundResource(R.drawable.status_neutral_bg);
        }
    }

    private void validateAndSave(VpsRelayConfig relay, String targetProfile) {
        RelayCheckProgressDialog progress = new RelayCheckProgressDialog(activity,
                () -> saveUnchecked(relay, targetProfile));
        progress.show();
        new Thread(() -> {
            VpsRelayCheckResult result = new VpsRelayClient().check(
                    relay.withEnabled(true), MtProtoConfig.relayDcRules(), progress::update);
            main.post(() -> {
                if (activity.isFinishing() || progress.isAbandoned()) return;
                progress.dismissForResult();
                if (result.status() != VpsRelayCheckResult.Status.OK) {
                    showCheckResult(result, () -> saveUnchecked(relay, targetProfile));
                    return;
                }
                saveVerified(relay, targetProfile, result);
            });
        }, "tg-relay-import").start();
    }

    private void saveVerified(VpsRelayConfig relay, String targetProfile,
                              VpsRelayCheckResult result) {
        VpsRelayConfig verified = relay.withEnabled(true)
                .withCapabilities(result.capabilities())
                .withInstanceId(result.instanceId());
        VpsRelayStore.Record saved = VpsRelayStore.fromContext(activity)
                .saveUsableRelay(verified, targetProfile);
        if (saved == null) {
            showParseError(activity.getString(R.string.settings_save_failed));
            return;
        }
        Toast.makeText(activity, R.string.vps_connection_import_success, Toast.LENGTH_SHORT).show();
        if (callback != null) callback.onImported(saved);
    }

    private void saveUnchecked(VpsRelayConfig relay, String targetProfile) {
        VpsRelayStore.Record saved = VpsRelayStore.fromContext(activity)
                .saveUsableRelay(relay.withEnabled(true), targetProfile);
        if (saved == null) {
            showParseError(activity.getString(R.string.settings_save_failed));
            return;
        }
        Toast.makeText(activity, R.string.relay_added_without_check, Toast.LENGTH_LONG).show();
        if (callback != null) callback.onImported(saved);
    }

    void showCheckResult(VpsRelayCheckResult result) {
        showCheckResult(result, null);
    }

    void showCheckResult(VpsRelayCheckResult result, Runnable addWithoutCheck) {
        boolean ok = result != null && result.status() == VpsRelayCheckResult.Status.OK;
        int title = ok ? R.string.relay_check_success_title
                : R.string.vps_connection_check_title_error;
        int message = ok ? R.string.relay_check_success_message : messageFor(result);
        showResult(ok, activity.getString(title), activity.getString(message),
                ok, addWithoutCheck);
    }

    private void showParseError(String message) {
        showResult(false, activity.getString(R.string.import_failed_title), message,
                false, null);
    }

    private void showResult(boolean ok, String titleText, String messageText,
                            boolean showTelegramNote, Runnable secondaryAction) {
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_relay_result, null, false);
        ImageView icon = root.findViewById(R.id.iv_relay_result_icon);
        icon.setImageResource(ok ? R.drawable.ic_status_check : R.drawable.ic_status_error);
        icon.setImageTintList(ContextCompat.getColorStateList(activity,
                ok ? R.color.green : R.color.red));
        icon.setBackgroundResource(ok ? R.drawable.status_success_bg : R.drawable.status_error_bg);
        ((TextView) root.findViewById(R.id.tv_relay_result_title)).setText(titleText);
        ((TextView) root.findViewById(R.id.tv_relay_result_message)).setText(messageText);
        LinearLayout details = root.findViewById(R.id.content_relay_result_details);
        if (showTelegramNote) {
            TextView note = new TextView(activity);
            note.setText(R.string.relay_check_telegram_note);
            note.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
            note.setTextSize(11f);
            note.setLineSpacing(0f, 1.08f);
            details.addView(note);
        } else if (secondaryAction != null) {
            TextView hint = new TextView(activity);
            hint.setText(R.string.relay_check_error_hint);
            hint.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
            hint.setTextSize(11f);
            hint.setLineSpacing(0f, 1.08f);
            details.addView(hint);
        } else {
            details.setVisibility(View.GONE);
        }
        MaterialButton secondary = root.findViewById(R.id.btn_relay_result_secondary);
        MaterialButton primary = root.findViewById(R.id.btn_relay_result_primary);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setView(root).create();
        dialog.setCanceledOnTouchOutside(false);
        if (secondaryAction != null) {
            secondary.setVisibility(View.VISIBLE);
            secondary.setOnClickListener(view -> {
                dialog.dismiss();
                secondaryAction.run();
            });
        } else {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) primary.getLayoutParams();
            params.setMarginStart(0);
            primary.setLayoutParams(params);
        }
        primary.setOnClickListener(view -> dialog.dismiss());
        dialog.show();
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

    private EditText field(String hint, boolean multiline) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(activity, R.color.text_hint));
        input.setBackgroundResource(R.drawable.edit_bg);
        int horizontal = dp(14);
        input.setPadding(horizontal, multiline ? dp(12) : 0, horizontal,
                multiline ? dp(12) : 0);
        input.setSingleLine(!multiline);
        input.setMinHeight(dp(multiline ? 112 : 54));
        if (multiline) input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        return input;
    }

    private static String endpoint(VpsRelayConfig relay) {
        String authority = relay.host().contains(":") ? "[" + relay.host() + "]" : relay.host();
        return (relay.tls() ? "https://" : "http://") + authority + ":"
                + relay.port() + relay.path();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
