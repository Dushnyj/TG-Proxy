package com.dushnyj.tgproxy;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Full-screen owner/token/device navigation. Secrets never leave app-local secure stores. */
public final class VpsOwnerActivity extends AppCompatActivity {
    static final String EXTRA_PROFILE_KEY = "profile_key";
    static final String EXTRA_RELAY_ID = "relay_id";

    private enum Page { OVERVIEW, TOKEN, DEVICES, DEVICE }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private ScrollView scroll;
    private ProgressBar progress;
    private TextView title;
    private TextView subtitle;
    private View refresh;

    private String profileKey = "";
    private String relayId = "";
    private VpsRelayConfig relay;
    private VpsOwnerRecord owner;
    private VpsOwnerClient.Overview overview;
    private VpsOwnerClient.Token selectedToken;
    private VpsOwnerClient.Client selectedClient;
    private Page page = Page.OVERVIEW;
    private boolean loading;
    private boolean refreshTokenAfterConnections;

    static Intent intent(Context context, String profileKey, String relayId) {
        return new Intent(context, VpsOwnerActivity.class)
                .putExtra(EXTRA_PROFILE_KEY, profileKey == null ? "" : profileKey)
                .putExtra(EXTRA_RELAY_ID, relayId == null ? "" : relayId);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_vps_owner);
        content = findViewById(R.id.content_owner);
        scroll = findViewById(R.id.scroll_owner);
        progress = findViewById(R.id.progress_owner);
        title = findViewById(R.id.tv_owner_title);
        subtitle = findViewById(R.id.tv_owner_subtitle);
        refresh = findViewById(R.id.btn_owner_refresh);
        findViewById(R.id.btn_owner_back).setOnClickListener(view -> navigateBack());
        refresh.setOnClickListener(view -> loadOverview());

        profileKey = clean(getIntent().getStringExtra(EXTRA_PROFILE_KEY));
        relayId = clean(getIntent().getStringExtra(EXTRA_RELAY_ID));
        VpsRelayStore store = VpsRelayStore.fromContext(this);
        VpsRelayStore.Record record = relayId.isEmpty() ? null : store.relay(relayId);
        relay = record == null ? store.selectedRelay(profileKey) : record.config().withProfileKey(profileKey);
        owner = new VpsOwnerStore(this).forRelay(relay);
        if (relay == null || !relay.hasValidEndpoint() || owner == null || !owner.canManage()) {
            renderUnavailable();
            return;
        }
        subtitle.setText(getString(R.string.vps_owner_page_subtitle, relay.host(), relay.port()));
        loadOverview();
    }

    @Override protected void onResume() {
        super.onResume();
        if (refreshTokenAfterConnections) {
            refreshTokenAfterConnections = false;
            if (page == Page.TOKEN) renderToken();
        }
    }

    @SuppressLint("MissingSuperCall")
    @Override public void onBackPressed() {
        navigateBack();
    }

    private void navigateBack() {
        if (loading) return;
        if (page == Page.DEVICE) {
            page = Page.DEVICES;
            renderDevices();
        } else if (page == Page.DEVICES) {
            page = Page.TOKEN;
            renderToken();
        } else if (page == Page.TOKEN) {
            page = Page.OVERVIEW;
            renderOverview();
        } else {
            finish();
        }
    }

    private void loadOverview() {
        if (loading || relay == null || owner == null) return;
        setLoading(true);
        page = Page.OVERVIEW;
        content.removeAllViews();
        addInfoCard(R.string.vps_owner_loading_inline, null, R.drawable.ic_server);
        new Thread(() -> {
            try {
                VpsOwnerClient.Overview loaded = new VpsOwnerClient().load(relay, owner.adminToken());
                handler.post(() -> {
                    if (isFinishing()) return;
                    overview = loaded;
                    setLoading(false);
                    renderOverview();
                });
            } catch (Exception error) {
                handler.post(() -> {
                    if (isFinishing()) return;
                    setLoading(false);
                    renderError(error);
                });
            }
        }, "tg-vps-owner-page-load").start();
    }

    private void renderUnavailable() {
        title.setText(R.string.vps_owner_manage);
        subtitle.setText("");
        refresh.setVisibility(View.GONE);
        content.removeAllViews();
        addInfoCard(R.string.vps_owner_not_available, null, R.drawable.ic_shield);
        MaterialButton close = primaryButton(android.R.string.ok);
        close.setOnClickListener(view -> finish());
        content.addView(close, topMargin(14));
    }

    private void renderError(Exception error) {
        title.setText(R.string.vps_owner_manage);
        content.removeAllViews();
        TextView heading = heading(getString(R.string.vps_owner_failed,
                firstLine(error == null ? "" : error.getMessage())));
        content.addView(heading);
        MaterialButton retry = primaryButton(R.string.vps_owner_retry);
        retry.setIconResource(R.drawable.ic_refresh);
        retry.setOnClickListener(view -> loadOverview());
        content.addView(retry, topMargin(16));
    }

    private void renderOverview() {
        page = Page.OVERVIEW;
        selectedToken = null;
        selectedClient = null;
        title.setText(R.string.vps_owner_manage);
        subtitle.setText(getString(R.string.vps_owner_page_subtitle, relay.host(), relay.port()));
        refresh.setVisibility(View.VISIBLE);
        content.removeAllViews();

        List<VpsOwnerClient.Token> tokens = overview == null
                ? new ArrayList<>() : overview.tokens();
        addSectionHeader(getString(R.string.vps_owner_tokens_title),
                getString(R.string.vps_owner_tokens_count, tokens.size()),
                getString(R.string.vps_owner_tokens_note));

        MaterialButton create = primaryButton(R.string.vps_owner_create_token);
        create.setIconResource(R.drawable.ic_add);
        create.setOnClickListener(view -> showCreateToken());
        content.addView(create, topMargin(14));

        if (tokens.isEmpty()) {
            addInfoCard(R.string.vps_owner_empty_title, R.string.vps_owner_empty_note,
                    R.drawable.ic_link);
        } else {
            for (VpsOwnerClient.Token token : tokens) addTokenCard(token);
        }

        addSectionHeader(getString(R.string.vps_owner_local_title), "",
                getString(R.string.vps_owner_local_note));
        addAction(R.drawable.ic_delete, R.string.vps_owner_forget_local,
                R.string.vps_owner_forget_note_short, true, this::confirmForgetOwner);
        scrollToTop();
    }

    private void addTokenCard(VpsOwnerClient.Token token) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_vps_token, content, false);
        TextView name = card.findViewById(R.id.tv_token_name);
        TextView status = card.findViewById(R.id.tv_token_status);
        TextView meta = card.findViewById(R.id.tv_token_meta);
        TextView date = card.findViewById(R.id.tv_token_date);
        name.setText(token.name().isEmpty() ? shortId(token.id()) : token.name());
        boolean active = token.activeDevices() > 0;
        status.setText(active ? R.string.vps_owner_token_active_short
                : R.string.vps_owner_token_unused_short);
        status.setBackgroundResource(active ? R.drawable.status_success_bg
                : R.drawable.status_warning_bg);
        status.setTextColor(ContextCompat.getColor(this, active ? R.color.green : R.color.warning));
        meta.setText(getString(R.string.vps_owner_token_meta,
                token.knownDevices(), token.activeDevices()));
        date.setText(getString(R.string.vps_owner_created_at, formatTimestamp(token.createdAt())));
        card.setContentDescription(name.getText() + ". " + meta.getText());
        card.setOnClickListener(view -> {
            selectedToken = token;
            page = Page.TOKEN;
            renderToken();
        });
        content.addView(card);
    }

    private void renderToken() {
        if (selectedToken == null) {
            renderOverview();
            return;
        }
        page = Page.TOKEN;
        String name = selectedToken.name().isEmpty() ? shortId(selectedToken.id()) : selectedToken.name();
        title.setText(name);
        subtitle.setText(R.string.vps_owner_token_page_subtitle);
        refresh.setVisibility(View.GONE);
        content.removeAllViews();

        addSectionHeader(name,
                getString(R.string.vps_owner_token_meta, selectedToken.knownDevices(),
                        selectedToken.activeDevices()),
                getString(R.string.vps_owner_created_at, formatTimestamp(selectedToken.createdAt())));

        LinearLayout details = detailContainer();
        details.addView(detailRow(R.string.vps_owner_token_id_label, shortId(selectedToken.id())));
        content.addView(details, topMargin(14));

        VpsOwnerRecord fresh = new VpsOwnerStore(this).forRelay(relay);
        VpsOwnerRecord.ManagedToken local = findLocalToken(fresh, selectedToken);
        if (local != null && !local.secret().isEmpty()) {
            VpsRelayStore store = VpsRelayStore.fromContext(this);
            VpsRelayConfig localRelay = relay.withTokenAndName(local.secret(), name);
            String localRelayId = store.relayIdFor(localRelay);
            boolean primary = !localRelayId.isEmpty()
                    && localRelayId.equals(clean(store.selectedRelayId(profileKey)))
                    && store.relay(localRelayId) != null
                    && store.relayEnabledForProfile(profileKey, localRelayId);
            addAction(R.drawable.ic_link,
                    primary ? R.string.vps_owner_token_primary_here
                            : R.string.vps_owner_token_use_here,
                    primary ? R.string.vps_owner_token_primary_here_note
                            : R.string.vps_owner_token_use_here_note,
                    false, primary
                            ? this::openRelayConnections
                            : () -> activateToken(local, name));
            addAction(R.drawable.ic_share, R.string.relay_share_title,
                    R.string.vps_owner_share_note_short, false,
                    () -> RelayShareSheet.show(this,
                            relay.withTokenAndName(local.secret(), name)));
        } else {
            addInfoCard(R.string.vps_owner_token_secret_missing_title,
                    R.string.vps_owner_token_secret_missing_note, R.drawable.ic_shield);
            addAction(R.drawable.ic_add, R.string.vps_owner_token_create_replacement,
                    R.string.vps_owner_token_create_replacement_note, false,
                    this::showCreateToken);
        }
        addAction(R.drawable.ic_devices, R.string.vps_owner_devices,
                R.string.vps_owner_devices_note_short, false, () -> {
                    page = Page.DEVICES;
                    renderDevices();
                });
        addAction(R.drawable.ic_delete, R.string.vps_owner_delete_token,
                R.string.vps_owner_delete_note_short, true, this::confirmDeleteToken);
        scrollToTop();
    }

    private void renderDevices() {
        if (selectedToken == null || overview == null) {
            renderOverview();
            return;
        }
        page = Page.DEVICES;
        title.setText(R.string.vps_owner_devices);
        List<VpsOwnerClient.Client> clients = overview.clientsFor(selectedToken.id());
        subtitle.setText(getString(R.string.vps_owner_devices_count, clients.size()));
        refresh.setVisibility(View.GONE);
        content.removeAllViews();
        addSectionHeader(getString(R.string.vps_owner_devices), "",
                getString(R.string.vps_owner_devices_note,
                        selectedToken.name().isEmpty() ? shortId(selectedToken.id())
                                : selectedToken.name()));
        if (clients.isEmpty()) {
            addInfoCard(R.string.vps_owner_no_devices, null, R.drawable.ic_devices);
        } else {
            for (VpsOwnerClient.Client client : clients) addDeviceCard(client);
        }
        scrollToTop();
    }

    private void addDeviceCard(VpsOwnerClient.Client client) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_vps_device, content, false);
        TextView name = card.findViewById(R.id.tv_device_name);
        TextView status = card.findViewById(R.id.tv_device_status);
        TextView system = card.findViewById(R.id.tv_device_system);
        TextView location = card.findViewById(R.id.tv_device_location);
        TextView seen = card.findViewById(R.id.tv_device_seen);
        name.setText(friendlyDeviceLabel(client));
        if (client.blocked()) {
            status.setText(R.string.vps_owner_device_blocked_short);
            status.setBackgroundResource(R.drawable.status_error_bg);
            status.setTextColor(ContextCompat.getColor(this, R.color.red));
        } else if (client.activeSessions() > 0) {
            status.setText(R.string.vps_owner_device_online_short);
            status.setBackgroundResource(R.drawable.status_success_bg);
            status.setTextColor(ContextCompat.getColor(this, R.color.green));
        } else {
            status.setText(R.string.vps_owner_device_allowed_short);
            status.setBackgroundResource(R.drawable.status_warning_bg);
            status.setTextColor(ContextCompat.getColor(this, R.color.warning));
        }
        system.setText(getString(R.string.vps_owner_device_system,
                dash(client.appVersion()), dash(client.android())));
        location.setText(dash(client.locationLabel()));
        seen.setText(getString(R.string.vps_owner_device_seen,
                formatTimestamp(client.lastSeen()), client.activeSessions()));
        card.setContentDescription(name.getText() + ". " + status.getText());
        card.setOnClickListener(view -> {
            selectedClient = client;
            page = Page.DEVICE;
            renderDevice();
        });
        content.addView(card);
    }

    private void renderDevice() {
        if (selectedClient == null || selectedToken == null) {
            renderDevices();
            return;
        }
        page = Page.DEVICE;
        title.setText(friendlyDeviceLabel(selectedClient));
        subtitle.setText(R.string.vps_owner_device_page_subtitle);
        refresh.setVisibility(View.GONE);
        content.removeAllViews();

        LinearLayout details = detailContainer();
        details.addView(detailRow(R.string.vps_owner_detail_device,
                friendlyDeviceLabel(selectedClient)));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_owner_detail_device_id,
                shortId(selectedClient.deviceId())));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_owner_detail_app,
                appVersion(selectedClient)));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_owner_detail_android,
                dash(selectedClient.android())));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_owner_detail_location,
                dash(selectedClient.locationLabel())));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_owner_detail_first_seen,
                formatTimestamp(selectedClient.firstSeen())));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_owner_detail_last_seen,
                formatTimestamp(selectedClient.lastSeen())));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_owner_detail_sessions,
                String.valueOf(selectedClient.activeSessions())));
        details.addView(divider());
        details.addView(detailRow(R.string.vps_owner_detail_state,
                getString(selectedClient.blocked() ? R.string.vps_owner_device_blocked_short
                        : R.string.vps_owner_device_allowed_short)));
        if (selectedClient.blocked() && !selectedClient.blockedAt().isEmpty()) {
            details.addView(divider());
            details.addView(detailRow(R.string.vps_owner_detail_blocked_at,
                    formatTimestamp(selectedClient.blockedAt())));
        }
        content.addView(details);

        addAction(R.drawable.ic_copy, R.string.vps_owner_copy_device_id,
                R.string.vps_owner_copy_device_id_note, false, this::copyDeviceId);
        if (selectedClient.activeSessions() > 0) {
            addAction(R.drawable.ic_refresh, R.string.vps_owner_disconnect_device,
                    R.string.vps_owner_disconnect_note, false,
                    () -> confirmDeviceAction(0));
        }
        addAction(R.drawable.ic_shield,
                selectedClient.blocked() ? R.string.vps_owner_unblock_device
                        : R.string.vps_owner_block_device,
                selectedClient.blocked() ? R.string.vps_owner_unblock_note
                        : R.string.vps_owner_block_note,
                !selectedClient.blocked(), () -> confirmDeviceAction(selectedClient.blocked() ? 2 : 1));
        scrollToTop();
    }

    private void showCreateToken() {
        EditText input = new EditText(this);
        input.setHint(R.string.vps_owner_token_name);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_hint));
        input.setBackgroundResource(R.drawable.edit_bg);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(20), dp(4), dp(20), 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        CheckBox useHere = new CheckBox(this);
        useHere.setText(R.string.vps_owner_create_use_here);
        useHere.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        useHere.setTextSize(13f);
        useHere.setChecked(true);
        useHere.setButtonTintList(ContextCompat.getColorStateList(this, R.color.accent));
        wrapper.addView(useHere, topMargin(8));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_owner_create_token)
                .setView(wrapper)
                .setPositiveButton(R.string.vps_owner_create_token, (dialog, which) ->
                        createToken(clean(input.getText().toString()), useHere.isChecked()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void createToken(String requestedName, boolean useHere) {
        String name = requestedName.isEmpty()
                ? getString(R.string.vps_owner_token_default) : requestedName;
        setLoading(true);
        new Thread(() -> {
            try {
                VpsOwnerClient api = new VpsOwnerClient();
                VpsTokenCreationDraftStore draftStore = new VpsTokenCreationDraftStore(this);
                VpsTokenCreationDraftStore.Draft draft = draftStore.loadOrCreate(relay, name);
                if (draft == null) throw new Exception("token request could not be saved");
                VpsOwnerClient.CreatedToken created = api.create(
                        relay, owner.adminToken(), name, draft);
                boolean saved = new VpsOwnerStore(this).saveManagedToken(relay,
                        created.token().id(), created.token().name(), created.secret());
                VpsRelayConfig shareRelay = relay.withTokenAndName(created.secret(), name);
                if (saved && useHere) {
                    saved = VpsRelayStore.fromContext(this)
                            .activateConnection(shareRelay, profileKey) != null;
                }
                boolean rolledBack = false;
                if (!saved) {
                    new VpsOwnerStore(this).removeManagedToken(relay, created.token().id());
                    try {
                        api.delete(relay, owner.adminToken(), created.token().id());
                        rolledBack = true;
                    } catch (Exception rollbackError) {
                        DiagnosticsLog.record("VPS owner token rollback failed: "
                                + firstLine(rollbackError.getMessage()));
                    }
                }
                // Keep the encrypted draft while a confirmed server token has not been saved
                // locally and could not be rolled back. Repeating Create will then replay the
                // same idempotent request and recover the same secret instead of adding another
                // token whose secret the phone no longer knows.
                if (saved || rolledBack) {
                    if (!draftStore.clear()) {
                        DiagnosticsLog.record("VPS owner token draft cleanup failed");
                    }
                }
                boolean finalSaved = saved;
                boolean finalRolledBack = rolledBack;
                handler.post(() -> {
                    if (isFinishing()) return;
                    setLoading(false);
                    if (finalSaved) {
                        if (useHere) {
                            Toast.makeText(this, R.string.vps_owner_token_activated,
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            RelayShareSheet.show(this, shareRelay);
                        }
                        loadOverview();
                    } else if (finalRolledBack) {
                        Toast.makeText(this, R.string.vps_owner_save_rolled_back,
                                Toast.LENGTH_LONG).show();
                        loadOverview();
                    } else {
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.vps_owner_save_failed_title)
                                .setMessage(R.string.vps_owner_save_failed_recovery)
                                .setPositiveButton(R.string.relay_share_title,
                                        (dialog, which) -> RelayShareSheet.show(this, shareRelay))
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                });
            } catch (Exception error) {
                handler.post(() -> {
                    setLoading(false);
                    showFailure(error);
                });
            }
        }, "tg-vps-owner-page-create").start();
    }

    private void confirmDeleteToken() {
        if (selectedToken == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_owner_delete_token)
                .setMessage(R.string.vps_owner_delete_token_warning)
                .setPositiveButton(R.string.vps_owner_delete_token,
                        (dialog, which) -> deleteToken())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteToken() {
        VpsOwnerClient.Token token = selectedToken;
        if (token == null) return;
        VpsOwnerRecord.ManagedToken local = findLocalToken(
                new VpsOwnerStore(this).forRelay(relay), token);
        String localSecret = local == null ? "" : local.secret();
        setLoading(true);
        new Thread(() -> {
            try {
                new VpsOwnerClient().delete(relay, owner.adminToken(), token.id());
                boolean removed = new VpsOwnerStore(this).removeManagedToken(relay, token.id());
                if (!localSecret.isEmpty()) {
                    removed = VpsRelayStore.fromContext(this)
                            .deleteConnection(relay, localSecret) && removed;
                }
                boolean finalRemoved = removed;
                handler.post(() -> {
                    if (isFinishing()) return;
                    setLoading(false);
                    Toast.makeText(this, finalRemoved ? R.string.vps_owner_token_deleted
                            : R.string.vps_owner_token_deleted_local_failed, Toast.LENGTH_LONG).show();
                    loadOverview();
                });
            } catch (Exception error) {
                handler.post(() -> {
                    setLoading(false);
                    showFailure(error);
                });
            }
        }, "tg-vps-owner-page-delete").start();
    }

    private void activateToken(VpsOwnerRecord.ManagedToken local, String name) {
        if (local == null || local.secret().isEmpty()) return;
        VpsRelayConfig connection = relay.withTokenAndName(local.secret(), name);
        VpsRelayStore.Record saved = VpsRelayStore.fromContext(this)
                .activateConnection(connection, profileKey);
        if (saved == null) {
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        relayId = saved.id();
        relay = saved.config().withProfileKey(profileKey);
        setResult(RESULT_OK);
        Toast.makeText(this, R.string.vps_owner_token_activated, Toast.LENGTH_SHORT).show();
        renderToken();
    }

    private void openRelayConnections() {
        refreshTokenAfterConnections = true;
        startActivity(VpsRelayConnectionsActivity.intent(this, profileKey));
    }

    private VpsOwnerRecord.ManagedToken findLocalToken(VpsOwnerRecord fresh,
                                                       VpsOwnerClient.Token token) {
        if (token == null) return null;
        if (fresh != null) {
            VpsOwnerRecord.ManagedToken direct = fresh.managedToken(token.id());
            if (direct != null && !direct.secret().isEmpty()) return direct;
            for (VpsOwnerRecord.ManagedToken candidate : fresh.managedTokens()) {
                if (VpsOwnerRecord.clientTokenId(candidate.secret()).equals(token.id())) {
                    return candidate;
                }
            }
        }
        for (VpsRelayStore.Record record : VpsRelayStore.fromContext(this).relays()) {
            VpsRelayConfig candidate = record.config();
            if (!candidate.sameEndpoint(relay) || !candidate.hasValidConnection()) continue;
            if (VpsOwnerRecord.clientTokenId(candidate.token()).equals(token.id())) {
                return new VpsOwnerRecord.ManagedToken(token.id(), candidate.name(),
                        candidate.token());
            }
        }
        return null;
    }

    private void confirmDeviceAction(int action) {
        if (selectedClient == null) return;
        int message = action == 0 ? R.string.vps_owner_disconnect_confirm
                : action == 1 ? R.string.vps_owner_block_confirm
                : R.string.vps_owner_unblock_confirm;
        int title = action == 0 ? R.string.vps_owner_disconnect_device
                : action == 1 ? R.string.vps_owner_block_device
                : R.string.vps_owner_unblock_device;
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(getString(message, friendlyDeviceLabel(selectedClient)))
                .setPositiveButton(title, (dialog, which) -> runDeviceAction(action))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runDeviceAction(int action) {
        VpsOwnerClient.Client client = selectedClient;
        VpsOwnerClient.Token token = selectedToken;
        if (client == null || token == null) return;
        setLoading(true);
        new Thread(() -> {
            try {
                VpsOwnerClient api = new VpsOwnerClient();
                if (action == 0) api.disconnectDevice(relay, owner.adminToken(), token.id(), client.deviceId());
                else if (action == 1) api.blockDevice(relay, owner.adminToken(), token.id(), client.deviceId());
                else api.unblockDevice(relay, owner.adminToken(), token.id(), client.deviceId());
                handler.post(() -> {
                    if (isFinishing()) return;
                    setLoading(false);
                    Toast.makeText(this, R.string.vps_owner_device_action_done,
                            Toast.LENGTH_SHORT).show();
                    loadOverview();
                });
            } catch (Exception error) {
                handler.post(() -> {
                    setLoading(false);
                    showFailure(error);
                });
            }
        }, "tg-vps-owner-page-device-action").start();
    }

    private void confirmForgetOwner() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_owner_forget_local)
                .setMessage(R.string.vps_owner_forget_local_warning)
                .setPositiveButton(R.string.vps_owner_forget_local, (dialog, which) -> {
                    boolean removed = new VpsOwnerStore(this).forget(relay);
                    if (removed && VpsEndpointPolicy.isDuckDnsDomain(relay.host())) {
                        new VpsEndpointCredentialStore(this).removeDuckDnsToken(relay.host());
                    }
                    Toast.makeText(this, removed ? R.string.vps_owner_forgotten
                            : R.string.vps_owner_save_failed, Toast.LENGTH_LONG).show();
                    if (removed) finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copyDeviceId() {
        if (selectedClient == null) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("TG Proxy device ID",
                    selectedClient.deviceId()));
            Toast.makeText(this, R.string.vps_owner_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void showFailure(Exception error) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_owner_manage)
                .setMessage(getString(R.string.vps_owner_failed,
                        firstLine(error == null ? "" : error.getMessage())))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void setLoading(boolean value) {
        loading = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        refresh.setEnabled(!value);
    }

    private void addSectionHeader(String headingText, String badgeText, String noteText) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView heading = heading(headingText);
        row.addView(heading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!clean(badgeText).isEmpty()) {
            TextView badge = new TextView(this);
            badge.setText(badgeText);
            badge.setTextColor(ContextCompat.getColor(this, R.color.accent));
            badge.setTextSize(11f);
            badge.setTypeface(badge.getTypeface(), Typeface.BOLD);
            badge.setBackgroundResource(R.drawable.status_warning_bg);
            row.addView(badge);
        }
        LinearLayout.LayoutParams rowParams = topMargin(content.getChildCount() == 0 ? 0 : 28);
        content.addView(row, rowParams);
        if (!clean(noteText).isEmpty()) {
            TextView note = body(noteText);
            content.addView(note, topMargin(8));
        }
    }

    private void addInfoCard(int titleRes, Integer noteRes, int iconRes) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.TOP);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundResource(R.drawable.owner_card_bg);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        card.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsParams.setMargins(dp(14), 0, 0, 0);
        card.addView(labels, labelsParams);
        TextView heading = heading(getString(titleRes));
        heading.setTextSize(15f);
        labels.addView(heading);
        if (noteRes != null) {
            TextView note = body(getString(noteRes));
            labels.addView(note, topMargin(6));
        }
        content.addView(card, topMargin(14));
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
            ImageViewCompat.setImageTintList(icon,
                    ContextCompat.getColorStateList(this, R.color.red));
        }
        row.setContentDescription(getString(titleRes) + ". " + getString(noteRes));
        row.setOnClickListener(view -> action.run());
        content.addView(row);
    }

    private LinearLayout detailContainer() {
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setBackgroundResource(R.drawable.owner_card_bg);
        return details;
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

    private TextView heading(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        view.setTextSize(19f);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView body(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        view.setTextSize(13f);
        view.setLineSpacing(0f, 1.12f);
        view.setIncludeFontPadding(false);
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
        button.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent));
        return button;
    }

    private LinearLayout.LayoutParams topMargin(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(margin);
        return params;
    }

    private void scrollToTop() {
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private String formatTimestamp(String raw) {
        String value = clean(raw);
        if (value.isEmpty()) return getString(R.string.vps_owner_date_unknown);
        try {
            Instant instant = Instant.parse(value);
            return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date.from(instant));
        } catch (Exception ignored) {
            return getString(R.string.vps_owner_date_unknown);
        }
    }

    private String friendlyDeviceLabel(VpsOwnerClient.Client client) {
        if (client == null) return "—";
        String hardware = clean(client.deviceLabel());
        return hardware.isEmpty() ? shortId(client.deviceId()) : hardware;
    }

    private static String appVersion(VpsOwnerClient.Client client) {
        String version = dash(client.appVersion());
        return client.appCode().isEmpty() ? version : version + " (" + client.appCode() + ")";
    }

    private static String shortId(String value) {
        String text = clean(value);
        if (text.length() <= 16) return text.isEmpty() ? "—" : text;
        return text.substring(0, 8) + "…" + text.substring(text.length() - 6);
    }

    private static String dash(String value) {
        String text = clean(value);
        return text.isEmpty() ? "—" : text;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstLine(String value) {
        String text = clean(value);
        int newline = text.indexOf('\n');
        if (newline >= 0) text = text.substring(0, newline).trim();
        return text.isEmpty() ? "unknown" : text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
