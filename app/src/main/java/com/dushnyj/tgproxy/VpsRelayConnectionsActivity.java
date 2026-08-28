package com.dushnyj.tgproxy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Grouped, profile-aware control centre for every local Relay server and credential. */
public final class VpsRelayConnectionsActivity extends AppCompatActivity {
    private static final String EXTRA_PROFILE_KEY = "profile_key";
    private static final String EXTRA_OPEN_ADD = "open_add";
    private static final int REQUEST_SETUP = 7101;
    private static final int REQUEST_FILE = 7102;

    private enum Page { LIST, CONNECTION }

    private LinearLayout content;
    private ScrollView scroll;
    private TextView title;
    private TextView subtitle;
    private ImageButton add;
    private String profileKey = "";
    private String selectedRelayId = "";
    private Page page = Page.LIST;
    private RelayImportCoordinator importer;

    static Intent intent(Context context, String profileKey) {
        return intent(context, profileKey, false);
    }

    static Intent intent(Context context, String profileKey, boolean openAdd) {
        return new Intent(context, VpsRelayConnectionsActivity.class)
                .putExtra(EXTRA_PROFILE_KEY, clean(profileKey))
                .putExtra(EXTRA_OPEN_ADD, openAdd);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_vps_relay_connections);
        content = findViewById(R.id.content_relay_connections);
        scroll = findViewById(R.id.scroll_relay_connections);
        title = findViewById(R.id.tv_relay_connections_title);
        subtitle = findViewById(R.id.tv_relay_connections_subtitle);
        add = findViewById(R.id.btn_relay_connections_add);
        profileKey = clean(getIntent().getStringExtra(EXTRA_PROFILE_KEY));
        importer = new RelayImportCoordinator(this, profileKey, record -> {
            selectedRelayId = record == null ? "" : record.id();
            page = Page.LIST;
            markChanged();
            render();
        });
        findViewById(R.id.btn_relay_connections_back).setOnClickListener(view -> navigateBack());
        add.setOnClickListener(view -> showAddSheet());
        render();
        if (state == null && getIntent().getBooleanExtra(EXTRA_OPEN_ADD, false)) {
            add.post(this::showAddSheet);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (content != null) render();
    }

    @Override public void onBackPressed() {
        if (page == Page.CONNECTION) {
            page = Page.LIST;
            selectedRelayId = "";
            render();
        } else {
            super.onBackPressed();
        }
    }

    private void navigateBack() {
        if (page == Page.CONNECTION) {
            page = Page.LIST;
            selectedRelayId = "";
            render();
        } else finish();
    }

    private void render() {
        if (page == Page.CONNECTION && !selectedRelayId.isEmpty()) {
            VpsRelayStore.Record selected = VpsRelayStore.fromContext(this).relay(selectedRelayId);
            if (selected != null) {
                renderConnection(selected);
                return;
            }
            page = Page.LIST;
            selectedRelayId = "";
        }
        renderList();
    }

    private void renderList() {
        VpsRelayStore store = VpsRelayStore.fromContext(this);
        List<VpsRelayStore.Record> records = store.relays();
        List<VpsRelayConnectionGroups.Group> groups =
                VpsRelayConnectionGroups.build(records, store, profileKey);
        int connectionCount = 0;
        for (VpsRelayConnectionGroups.Group group : groups) {
            connectionCount += group.connections().size();
        }
        title.setText(R.string.vps_connections_title);
        subtitle.setText(getString(R.string.vps_connection_list_summary,
                groups.size(), connectionCount));
        add.setVisibility(View.VISIBLE);
        content.removeAllViews();

        LinearLayout note = card();
        note.addView(text(getString(R.string.vps_connections_how_title), 16,
                R.color.text_primary, true));
        note.addView(body(getString(R.string.vps_connections_how_note)), topMargin(7));
        content.addView(note);
        if (groups.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text(getString(R.string.vps_connections_empty_title), 17,
                    R.color.text_primary, true));
            empty.addView(body(getString(R.string.vps_connections_empty_note)), topMargin(8));
            MaterialButton create = primaryButton(R.string.vps_connections_add);
            create.setOnClickListener(view -> showAddSheet());
            empty.addView(create, topMargin(16));
            content.addView(empty, topMargin(14));
            scrollToTop();
            return;
        }
        int number = 1;
        for (VpsRelayConnectionGroups.Group group : groups) {
            addServerGroup(store, group, number++);
        }
        scrollToTop();
    }

    private void addServerGroup(VpsRelayStore store, VpsRelayConnectionGroups.Group group,
                                int number) {
        VpsRelayConfig server = group.server();
        LinearLayout serverCard = card();
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_server);
        icon.setImageTintList(ContextCompat.getColorStateList(this, R.color.accent));
        heading.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView serverTitle = text(getString(R.string.vps_connection_server_title, number),
                16, R.color.text_primary, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(dp(12));
        heading.addView(serverTitle, titleParams);
        serverCard.addView(heading);
        TextView endpointView = body(getString(R.string.vps_connection_server_endpoint,
                endpoint(server), group.connections().size()));
        endpointView.setEllipsize(TextUtils.TruncateAt.END);
        endpointView.setMaxLines(2);
        serverCard.addView(endpointView, topMargin(8));
        if (group.connections().size() > 1) {
            serverCard.addView(text(getString(R.string.vps_connections_server_identity), 11,
                    R.color.text_hint, false), topMargin(5));
        }
        content.addView(serverCard, topMargin(16));

        String primaryId = clean(store.selectedRelayId(profileKey));
        for (VpsRelayStore.Record record : group.connections()) {
            boolean primary = record.id().equals(primaryId);
            boolean enabled = store.relayEnabledForProfile(profileKey, record.id());
            View row = LayoutInflater.from(this).inflate(R.layout.item_vps_token, content, false);
            TextView name = row.findViewById(R.id.tv_token_name);
            TextView status = row.findViewById(R.id.tv_token_status);
            TextView meta = row.findViewById(R.id.tv_token_meta);
            TextView token = row.findViewById(R.id.tv_token_date);
            name.setText(record.config().name());
            setStatus(status, primary, enabled);
            meta.setText(record.config().tls() ? R.string.vps_connection_tls
                    : R.string.vps_connection_plain);
            token.setText(getString(R.string.vps_connections_token,
                    record.config().maskedToken()));
            row.setContentDescription(record.config().name() + ". " + status.getText());
            row.setOnClickListener(view -> {
                selectedRelayId = record.id();
                page = Page.CONNECTION;
                render();
            });
            content.addView(row);
        }
    }

    private void renderConnection(VpsRelayStore.Record record) {
        VpsRelayStore store = VpsRelayStore.fromContext(this);
        VpsRelayConfig relay = record.config();
        boolean primary = record.id().equals(clean(store.selectedRelayId(profileKey)));
        boolean enabled = store.relayEnabledForProfile(profileKey, record.id());
        title.setText(relay.name());
        subtitle.setText(R.string.vps_connection_detail_subtitle);
        add.setVisibility(View.GONE);
        content.removeAllViews();

        LinearLayout state = card();
        TextView status = text("", 13, R.color.text_primary, true);
        setStatus(status, primary, enabled);
        state.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        state.addView(body(endpoint(relay)), topMargin(9));
        content.addView(state);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setBackgroundResource(R.drawable.owner_card_bg);
        details.addView(detailRow(R.string.vps_connection_label_name, relay.name()));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_connection_label_endpoint,
                relay.host() + ":" + relay.port()));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_connection_label_transport,
                getString(relay.tls() ? R.string.vps_connection_tls : R.string.vps_connection_plain)));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_connection_label_path, relay.path()));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_connection_label_token, relay.maskedToken()));
        content.addView(details, topMargin(14));

        addAction(R.drawable.ic_refresh, R.string.vps_connection_test,
                R.string.vps_connection_test_note, false, () -> testConnection(record));
        addAction(R.drawable.ic_share, R.string.share_export,
                R.string.vps_connection_share_note, false,
                () -> RelayShareSheet.show(this, relay.withEnabled(true)));
        if (!primary) {
            addAction(R.drawable.ic_status_check, R.string.vps_connections_make_primary,
                    R.string.vps_connection_make_primary_note, false, () -> {
                        if (store.makePrimary(profileKey, record.id())) {
                            markChanged();
                            Toast.makeText(this, R.string.vps_connections_primary_changed,
                                    Toast.LENGTH_SHORT).show();
                            render();
                        } else showSaveError();
                    });
        }
        addAction(enabled ? R.drawable.ic_status_error : R.drawable.ic_status_check,
                enabled ? R.string.vps_connection_disable : R.string.vps_connection_enable,
                enabled ? R.string.vps_connection_disable_note : R.string.vps_connection_enable_note,
                false, () -> {
                    if (store.setRelayEnabledForProfile(profileKey, record.id(), !enabled)) {
                        markChanged();
                        render();
                    } else showSaveError();
                });

        VpsOwnerRecord owner = new VpsOwnerStore(this).forRelay(relay);
        if (owner != null && owner.canManage()) {
            addAction(R.drawable.ic_shield, R.string.vps_connection_owner,
                    R.string.vps_connection_owner_note, false,
                    () -> startActivityForResult(
                            VpsOwnerActivity.intent(this, profileKey, record.id()), REQUEST_SETUP));
            addAction(R.drawable.ic_server, R.string.vps_connection_update_server,
                    R.string.vps_connection_update_server_note, false,
                    () -> startActivityForResult(
                            VpsSetupActivity.intent(this, profileKey, record.id(), true), REQUEST_SETUP));
        }
        addAction(R.drawable.ic_settings, R.string.vps_connection_edit,
                R.string.vps_connection_edit_note, false, () -> showManualEditor(record));
        addAction(R.drawable.ic_delete, R.string.vps_connection_delete,
                R.string.vps_connection_delete_note, true, () -> confirmDelete(record));
        scrollToTop();
    }

    private void setStatus(TextView view, boolean primary, boolean enabled) {
        view.setText(primary ? R.string.vps_connections_primary
                : enabled ? R.string.vps_connections_backup : R.string.vps_connections_disabled);
        view.setTextColor(ContextCompat.getColor(this, primary ? R.color.green
                : enabled ? R.color.accent : R.color.text_secondary));
        view.setBackgroundResource(primary ? R.drawable.status_success_bg
                : enabled ? R.drawable.status_info_bg : R.drawable.status_neutral_bg);
        view.setPadding(dp(10), dp(5), dp(10), dp(5));
    }

    private void testConnection(VpsRelayStore.Record record) {
        RelayCheckProgressDialog progress = new RelayCheckProgressDialog(this, null);
        progress.show();
        new Thread(() -> {
            VpsRelayCheckResult result = new VpsRelayClient().check(
                    record.config().withEnabled(true), MtProtoConfig.relayDcRules(),
                    progress);
            runOnUiThread(() -> {
                if (isFinishing() || progress.isAbandoned()) return;
                progress.dismissForResult();
                if (result.status() == VpsRelayCheckResult.Status.OK) {
                    VpsRelayConfig verified = record.config()
                            .withCapabilities(result.capabilities())
                            .withInstanceId(result.instanceId());
                    if (VpsRelayStore.fromContext(this)
                            .updateRelayMetadata(record.id(), verified) != null) markChanged();
                }
                importer.showCheckResult(result);
                render();
            });
        }, "tg-relay-detail-test").start();
    }

    private void confirmDelete(VpsRelayStore.Record record) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_connection_delete)
                .setMessage(getString(R.string.vps_connection_delete_confirm,
                        record.config().name()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.vps_connection_delete, (dialog, which) -> {
                    if (!VpsRelayStore.fromContext(this).deleteRelay(record.id())) {
                        showSaveError();
                        return;
                    }
                    markChanged();
                    page = Page.LIST;
                    selectedRelayId = "";
                    Toast.makeText(this, R.string.vps_connection_deleted,
                            Toast.LENGTH_SHORT).show();
                    render();
                }).show();
    }

    private void showAddSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = LayoutInflater.from(this).inflate(R.layout.sheet_relay_add, null, false);
        LinearLayout actions = sheet.findViewById(R.id.content_relay_add_actions);
        addSheetAction(actions, R.drawable.ic_server, R.string.vps_connections_add_setup,
                R.string.vps_connections_add_setup_note, () -> {
                    dialog.dismiss();
                    startActivityForResult(VpsSetupActivity.intent(this, profileKey, "", false),
                            REQUEST_SETUP);
                });
        addSheetAction(actions, R.drawable.ic_link, R.string.vps_connections_add_text,
                R.string.vps_connections_add_text_note, () -> {
                    dialog.dismiss();
                    importer.showPasteDialog();
                });
        addSheetAction(actions, R.drawable.ic_file, R.string.vps_connections_add_file,
                R.string.vps_connections_add_file_note, () -> {
                    dialog.dismiss();
                    openRelayFile();
                });
        addSheetAction(actions, R.drawable.ic_qr, R.string.vps_connections_add_qr,
                R.string.vps_connections_add_qr_note, () -> {
                    dialog.dismiss();
                    new IntentIntegrator(this)
                            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                            .setCaptureActivity(PortraitCaptureActivity.class)
                            .setOrientationLocked(true)
                            .setPrompt(getString(R.string.scan_relay_qr_prompt))
                            .setBeepEnabled(false)
                            .initiateScan();
                });
        addSheetAction(actions, R.drawable.ic_settings, R.string.vps_connections_add_manual,
                R.string.vps_connections_add_manual_note, () -> {
                    dialog.dismiss();
                    showManualEditor(null);
                });
        dialog.setContentView(sheet);
        dialog.setOnShowListener(ignored -> {
            View bottom = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottom != null) BottomSheetBehavior.from(bottom)
                    .setState(BottomSheetBehavior.STATE_EXPANDED);
        });
        dialog.show();
    }

    private void addSheetAction(LinearLayout parent, int iconRes, int titleRes, int noteRes,
                                Runnable action) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_vps_action, parent, false);
        ((ImageView) row.findViewById(R.id.iv_action_icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.tv_action_title)).setText(titleRes);
        ((TextView) row.findViewById(R.id.tv_action_note)).setText(noteRes);
        row.setContentDescription(getString(titleRes) + ". " + getString(noteRes));
        row.setOnClickListener(view -> action.run());
        parent.addView(row);
    }

    private void showManualEditor(VpsRelayStore.Record existing) {
        VpsRelayConfig source = existing == null ? VpsRelayConfig.manual(
                true, "", "", 443, true, "/apiws", "", profileKey) : existing.config();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));
        EditText name = addField(form, R.string.vps_relay_name, source.name(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText host = addField(form, R.string.vps_relay_host, source.host(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = addField(form, R.string.vps_relay_port,
                source.port() > 0 ? String.valueOf(source.port()) : "443",
                InputType.TYPE_CLASS_NUMBER);
        CheckBox tls = new CheckBox(this);
        tls.setText(R.string.vps_relay_tls);
        tls.setChecked(source.tls());
        tls.setMinHeight(dp(48));
        tls.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tls.setButtonTintList(ContextCompat.getColorStateList(this, R.color.accent));
        form.addView(tls, topMargin(8));
        EditText path = addField(form, R.string.vps_relay_path, source.path(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText token = addField(form, R.string.vps_relay_token, source.token(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ScrollView wrapper = new ScrollView(this);
        wrapper.setFillViewport(true);
        wrapper.addView(form);
        AlertDialog editor = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_connection_manual_title)
                .setView(wrapper)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.vps_connection_manual_save, null)
                .create();
        editor.setOnShowListener(ignored -> editor.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int parsedPort;
                    try { parsedPort = Integer.parseInt(clean(port.getText())); }
                    catch (Exception error) { parsedPort = 0; }
                    VpsRelayConfig candidate = VpsRelayConfig.manual(true,
                            clean(name.getText()), clean(host.getText()), parsedPort,
                            tls.isChecked(), clean(path.getText()), clean(token.getText()), profileKey);
                    if (!candidate.hasValidConnection()) {
                        Toast.makeText(this, R.string.vps_connection_manual_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    editor.dismiss();
                    validateManual(existing, candidate);
                }));
        editor.show();
    }

    private EditText addField(LinearLayout form, int labelRes, String value, int inputType) {
        form.addView(text(getString(labelRes), 12, R.color.text_secondary, true),
                topMargin(form.getChildCount() == 0 ? 8 : 12));
        EditText input = new EditText(this);
        input.setText(value);
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_hint));
        input.setBackgroundResource(R.drawable.edit_bg);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setTextSize(14f);
        form.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        return input;
    }

    private void validateManual(VpsRelayStore.Record existing, VpsRelayConfig candidate) {
        RelayCheckProgressDialog progress = new RelayCheckProgressDialog(this,
                () -> saveManual(existing, candidate, null));
        progress.show();
        new Thread(() -> {
            VpsRelayCheckResult result = new VpsRelayClient().check(
                    candidate, MtProtoConfig.relayDcRules(), progress);
            runOnUiThread(() -> {
                if (isFinishing() || progress.isAbandoned()) return;
                progress.dismissForResult();
                if (result.status() != VpsRelayCheckResult.Status.OK) {
                    importer.showCheckResult(result,
                            () -> saveManual(existing, candidate, null));
                    return;
                }
                saveManual(existing, candidate, result);
            });
        }, "tg-relay-manual-check").start();
    }

    private void saveManual(VpsRelayStore.Record existing, VpsRelayConfig candidate,
                            VpsRelayCheckResult result) {
        boolean verified = result != null && result.status() == VpsRelayCheckResult.Status.OK;
        VpsRelayConfig savedConfig = verified
                ? candidate.withCapabilities(result.capabilities())
                .withInstanceId(result.instanceId())
                : candidate;
        VpsRelayStore store = VpsRelayStore.fromContext(this);
        VpsRelayStore.Record saved = existing == null
                ? store.saveUsableRelay(savedConfig, profileKey)
                : store.updateConnection(existing.id(), savedConfig);
        if (saved == null) {
            showSaveError();
            return;
        }
        markChanged();
        selectedRelayId = saved.id();
        page = Page.CONNECTION;
        Toast.makeText(this, verified
                        ? (existing == null ? R.string.vps_connection_import_success
                        : R.string.vps_connection_edit_saved)
                        : (existing == null ? R.string.relay_added_without_check
                        : R.string.relay_updated_without_check),
                verified ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
        render();
    }

    private void openRelayFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "text/plain", "application/vnd.tgproxy", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            String value = clean(scan.getContents());
            if (value.isEmpty()) {
                Toast.makeText(this, R.string.vps_connection_qr_empty, Toast.LENGTH_LONG).show();
            } else importer.importRaw(value, "");
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK) return;
        if (requestCode == REQUEST_SETUP) {
            markChanged();
            page = Page.LIST;
            render();
        } else if (requestCode == REQUEST_FILE && data != null && data.getData() != null) {
            try { importer.importRaw(readText(data.getData()), ""); }
            catch (Exception error) {
                Toast.makeText(this, R.string.vps_connection_file_error,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private String readText(Uri uri) throws Exception {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("content URI required");
        }
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalArgumentException("file unavailable");
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > SettingsTransfer.MAX_IMPORT_CHARS) {
                    throw new IllegalArgumentException("file too large");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void addAction(int iconRes, int titleRes, int noteRes, boolean danger,
                           Runnable action) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_vps_action, content, false);
        ImageView icon = row.findViewById(R.id.iv_action_icon);
        TextView heading = row.findViewById(R.id.tv_action_title);
        TextView note = row.findViewById(R.id.tv_action_note);
        icon.setImageResource(iconRes);
        heading.setText(titleRes);
        note.setText(noteRes);
        if (danger) {
            row.setBackgroundResource(R.drawable.danger_section_bg);
            heading.setTextColor(ContextCompat.getColor(this, R.color.red));
            icon.setImageTintList(ContextCompat.getColorStateList(this, R.color.red));
        }
        row.setContentDescription(getString(titleRes) + ". " + getString(noteRes));
        row.setOnClickListener(view -> action.run());
        content.addView(row);
    }

    private View detailRow(int labelRes, String value) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_vps_detail_row, content, false);
        ((TextView) row.findViewById(R.id.tv_detail_label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.tv_detail_value)).setText(dash(value));
        return row;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(ContextCompat.getColor(this, R.color.divider));
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.owner_card_bg);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        return card;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(ContextCompat.getColor(this, color));
        text.setIncludeFontPadding(false);
        if (bold) text.setTypeface(text.getTypeface(), Typeface.BOLD);
        return text;
    }

    private TextView body(String value) {
        TextView view = text(value, 13, R.color.text_secondary, false);
        view.setLineSpacing(0f, 1.1f);
        return view;
    }

    private MaterialButton primaryButton(int textRes) {
        MaterialButton button = new MaterialButton(this);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setMinHeight(dp(52));
        button.setCornerRadius(dp(16));
        button.setTextColor(ContextCompat.getColor(this, R.color.button_text));
        button.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent)));
        return button;
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(marginDp);
        return params;
    }

    private void scrollToTop() {
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void markChanged() { setResult(Activity.RESULT_OK); }

    private void showSaveError() {
        Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
    }

    private static String endpoint(VpsRelayConfig relay) {
        return (relay.tls() ? "https://" : "http://") + relay.host() + ":"
                + relay.port() + relay.path();
    }

    private static String dash(String value) {
        String result = clean(value);
        return result.isEmpty() ? "—" : result;
    }

    private static String clean(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
