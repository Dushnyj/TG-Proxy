package com.dushnyj.tgproxy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full-screen zero-touch VPS installer for IP certificate, DuckDNS, and own-domain modes. */
public final class VpsSetupActivity extends AppCompatActivity {
    static final String EXTRA_PROFILE_KEY = "profile_key";
    static final String EXTRA_RELAY_ID = "relay_id";
    static final String EXTRA_UPDATE_EXISTING = "update_existing";
    private static final String KNOWN_HOSTS_FILE = "vps_ssh_known_hosts";
    private static final int PREFLIGHT_TIMEOUT_MS = 35_000;

    private enum Page { ACCESS, ENDPOINT, PLAN, INSTALL, COMPLETE, ERROR }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private ScrollView scroll;
    private TextView title;
    private TextView step;
    private ProgressBar stepProgress;
    private View footer;
    private MaterialButton previous;
    private MaterialButton next;

    private String profileKey = "";
    private String relayId = "";
    private boolean updateExisting;
    private VpsRelayConfig selectedRelay;
    private VpsOwnerRecord savedOwner;
    private boolean ownerExisted;

    private String sshHost = "";
    private int sshPort = 22;
    private String sshUser = "root";
    private String sshPassword = "";
    private boolean rememberCredentials = true;
    private String relayToken = "";
    private String adminToken = "";
    private String tokenChoiceEndpoint = "";
    private VpsEndpointPolicy.Mode endpointMode = VpsEndpointPolicy.Mode.IP;
    private String duckDnsDomain = "";
    private String duckDnsToken = "";
    private String ownDomain = "";
    private String publicIp = "";
    private VpsSetupAudit preflightAudit;
    private VpsSetupRequest setupRequest;
    private VpsRelayConfig completedRelay;

    private EditText fieldSshHost;
    private EditText fieldSshPort;
    private EditText fieldSshUser;
    private EditText fieldSshPassword;
    private CheckBox checkRemember;
    private EditText fieldDuckDomain;
    private EditText fieldDuckToken;
    private EditText fieldOwnDomain;
    private LinearLayout endpointDetails;

    private Page page = Page.ACCESS;
    private boolean running;
    private boolean mutating;
    private boolean planDeclined;
    private CountDownLatch planLatch;
    private AtomicBoolean planApproval;
    private ProgressBar installProgress;
    private TextView installStatus;
    private String lastError = "";

    static Intent intent(Context context, String profileKey, String relayId,
                         boolean updateExisting) {
        return new Intent(context, VpsSetupActivity.class)
                .putExtra(EXTRA_PROFILE_KEY, profileKey == null ? "" : profileKey)
                .putExtra(EXTRA_RELAY_ID, relayId == null ? "" : relayId)
                .putExtra(EXTRA_UPDATE_EXISTING, updateExisting);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_vps_setup);
        content = findViewById(R.id.content_setup);
        scroll = findViewById(R.id.scroll_setup);
        title = findViewById(R.id.tv_setup_title);
        step = findViewById(R.id.tv_setup_step);
        stepProgress = findViewById(R.id.progress_setup_steps);
        footer = findViewById(R.id.footer_setup);
        previous = findViewById(R.id.btn_setup_previous);
        next = findViewById(R.id.btn_setup_next);
        findViewById(R.id.btn_setup_close).setOnClickListener(view -> handleBack());

        profileKey = clean(getIntent().getStringExtra(EXTRA_PROFILE_KEY));
        relayId = clean(getIntent().getStringExtra(EXTRA_RELAY_ID));
        updateExisting = getIntent().getBooleanExtra(EXTRA_UPDATE_EXISTING, false);
        VpsRelayStore store = VpsRelayStore.fromContext(this);
        VpsRelayStore.Record record = relayId.isEmpty() ? null : store.relay(relayId);
        selectedRelay = record == null ? null : record.config().withProfileKey(profileKey);
        savedOwner = new VpsOwnerStore(this).forRelay(selectedRelay);
        ownerExisted = savedOwner != null && savedOwner.canManage();
        initializeValues();
        renderAccess();
    }

    @SuppressLint("MissingSuperCall")
    @Override public void onBackPressed() {
        handleBack();
    }

    private void initializeValues() {
        if (savedOwner != null) {
            sshHost = savedOwner.sshHost();
            sshPort = savedOwner.sshPort();
            sshUser = savedOwner.sshUser();
            sshPassword = savedOwner.sshPassword();
            rememberCredentials = !savedOwner.sshPassword().isEmpty();
            adminToken = savedOwner.adminToken();
        } else if (selectedRelay != null && selectedRelay.isUsable()) {
            sshHost = selectedRelay.host();
        } else {
            VpsSshCredentials draft = new VpsSshDraftStore(this).load();
            if (draft != null) {
                sshHost = draft.host();
                sshPort = draft.port();
                sshUser = draft.user();
                sshPassword = draft.password();
                rememberCredentials = true;
            }
        }
        if (selectedRelay != null && selectedRelay.isUsable()) {
            relayToken = selectedRelay.token();
            endpointMode = VpsEndpointPolicy.suggestedMode(selectedRelay);
            if (endpointMode == VpsEndpointPolicy.Mode.DUCKDNS) {
                duckDnsDomain = selectedRelay.host();
                duckDnsToken = new VpsEndpointCredentialStore(this)
                        .duckDnsToken(duckDnsDomain);
            } else if (endpointMode == VpsEndpointPolicy.Mode.OWN_DOMAIN) {
                ownDomain = selectedRelay.host();
            }
        }
        if (relayToken.isEmpty()) relayToken = generateToken("tgpc_");
        if (adminToken.isEmpty()) adminToken = generateToken("tgpa_");
    }

    private void handleBack() {
        hideKeyboard();
        if (page == Page.PLAN && planLatch != null) {
            planDeclined = true;
            planApproval.set(false);
            planLatch.countDown();
            renderEndpoint();
            return;
        }
        if (running || mutating) {
            Toast.makeText(this, R.string.vps_setup_running, Toast.LENGTH_SHORT).show();
            return;
        }
        if (page == Page.ENDPOINT || page == Page.ERROR) {
            renderAccess();
            return;
        }
        if (page == Page.COMPLETE) {
            finishWithResult();
            return;
        }
        confirmExit();
    }

    private void confirmExit() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_setup_cancel_title)
                .setMessage(R.string.vps_setup_cancel_message)
                .setPositiveButton(R.string.vps_setup_cancel, (dialog, which) -> finish())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void renderAccess() {
        page = Page.ACCESS;
        running = false;
        mutating = false;
        setHeader(updateExisting ? R.string.vps_relay_update_server : R.string.vps_setup_title,
                R.string.vps_setup_step_access, 25);
        content.removeAllViews();
        addHero(R.drawable.ic_server, R.string.vps_setup_access_title,
                R.string.vps_setup_access_note);
        if (!lastError.isEmpty()) addError(lastError);

        fieldSshHost = addField(R.string.vps_setup_ssh_host, sshHost,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        fieldSshPort = addField(R.string.vps_setup_ssh_port, String.valueOf(sshPort),
                InputType.TYPE_CLASS_NUMBER, false);
        fieldSshUser = addField(R.string.vps_setup_ssh_user, sshUser,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL, false);
        fieldSshPassword = addField(R.string.vps_setup_ssh_password, sshPassword,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);

        checkRemember = new CheckBox(this);
        checkRemember.setText(R.string.vps_setup_remember_credentials);
        checkRemember.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        checkRemember.setTextSize(13f);
        checkRemember.setChecked(rememberCredentials);
        checkRemember.setButtonTintList(ContextCompat.getColorStateList(this, R.color.accent));
        content.addView(checkRemember, topMargin(10));

        TextView security = body(getString(R.string.vps_setup_access_security));
        security.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_shield, 0, 0, 0);
        security.setCompoundDrawablePadding(dp(10));
        security.setBackgroundResource(R.drawable.owner_card_bg);
        security.setPadding(dp(14), dp(14), dp(14), dp(14));
        content.addView(security, topMargin(12));

        MaterialButton forget = outlinedButton(R.string.vps_setup_forget_ssh_key);
        forget.setIconResource(R.drawable.ic_delete);
        forget.setOnClickListener(view -> forgetHostKey());
        content.addView(forget, topMargin(12));

        configureFooter(false, R.string.vps_setup_check_server,
                null, this::startPreflight);
        scrollTop();
    }

    private void captureAccess() {
        sshHost = clean(fieldSshHost == null ? sshHost : fieldSshHost.getText().toString());
        sshPort = intValue(fieldSshPort == null ? "" : fieldSshPort.getText().toString(), 22);
        sshUser = clean(fieldSshUser == null ? sshUser : fieldSshUser.getText().toString());
        sshPassword = fieldSshPassword == null ? sshPassword : fieldSshPassword.getText().toString();
        rememberCredentials = checkRemember == null || checkRemember.isChecked();
    }

    private void startPreflight() {
        captureAccess();
        VpsSshCredentials credentials = new VpsSshCredentials(sshHost, sshPort, sshUser, sshPassword);
        if (!credentials.isValid() || sshPassword.isEmpty()) {
            lastError = getString(R.string.vps_setup_access_required);
            renderAccess();
            return;
        }
        VpsOwnerRecord ownerForServer = new VpsOwnerStore(this).forSsh(credentials);
        if (ownerForServer != null && ownerForServer.canManage()) {
            savedOwner = ownerForServer;
            ownerExisted = true;
            adminToken = ownerForServer.adminToken();
        }
        hideKeyboard();
        running = true;
        renderProgressPage(R.string.vps_setup_step_access, 25,
                R.string.vps_setup_checking_server);
        VpsSetupRequest auditRequest = baseRequest(sshHost, 18080, false, false);
        new Thread(() -> {
            try {
                String raw = createSshClient().execute(credentials,
                        VpsSetupProgress.Stage.AUDIT, "sh -s",
                        VpsSetupScripts.audit(auditRequest), PREFLIGHT_TIMEOUT_MS);
                VpsSetupAudit audit = VpsSetupAudit.parse(raw);
                if (!audit.isLinux() || !audit.hasSupportedInit() || !audit.isSupportedArch()) {
                    throw new VpsSetupException(
                            VpsSetupPlan.from(auditRequest, audit).blockingSummary());
                }
                String ip = VpsEndpointPolicy.normalizeHost(audit.publicIp());
                if (!VpsEndpointPolicy.isIpLiteral(ip)) {
                    throw new VpsSetupException(getString(R.string.vps_endpoint_invalid_ip));
                }
                VpsSshDraftStore draftStore = new VpsSshDraftStore(this);
                if (rememberCredentials) draftStore.save(credentials);
                else draftStore.clear();
                handler.post(() -> {
                    if (isFinishing()) return;
                    preflightAudit = audit;
                    publicIp = ip;
                    running = false;
                    lastError = "";
                    renderEndpoint();
                });
            } catch (Exception error) {
                handler.post(() -> {
                    if (isFinishing()) return;
                    running = false;
                    lastError = firstLine(error.getMessage());
                    renderAccess();
                });
            }
        }, "tg-vps-setup-preflight").start();
    }

    private void renderEndpoint() {
        page = Page.ENDPOINT;
        running = false;
        setHeader(R.string.vps_setup_title, R.string.vps_setup_step_endpoint, 50);
        content.removeAllViews();
        addHero(R.drawable.ic_link, R.string.vps_setup_endpoint_title,
                R.string.vps_setup_endpoint_note);
        if (preflightAudit != null) {
            addStatusCard(getString(R.string.vps_setup_server_ready),
                    getString(R.string.vps_setup_server_summary,
                            dash(preflightAudit.os()), dash(preflightAudit.architecture()),
                            dash(preflightAudit.initSystem()), publicIp));
        }
        if (!lastError.isEmpty()) addError(lastError);

        RadioGroup choices = new RadioGroup(this);
        choices.setOrientation(RadioGroup.VERTICAL);
        RadioButton ip = endpointOption(1001, R.string.vps_endpoint_ip_title,
                R.string.vps_endpoint_ip_note, true);
        RadioButton duck = endpointOption(1002, R.string.vps_endpoint_duckdns_title,
                R.string.vps_endpoint_duckdns_note, false);
        RadioButton own = endpointOption(1003, R.string.vps_endpoint_domain_title,
                R.string.vps_endpoint_domain_note, false);
        choices.addView(ip);
        choices.addView(duck, radioMargin());
        choices.addView(own, radioMargin());
        content.addView(choices, topMargin(16));
        int checked = endpointMode == VpsEndpointPolicy.Mode.DUCKDNS ? 1002
                : endpointMode == VpsEndpointPolicy.Mode.OWN_DOMAIN ? 1003 : 1001;
        choices.check(checked);

        endpointDetails = new LinearLayout(this);
        endpointDetails.setOrientation(LinearLayout.VERTICAL);
        content.addView(endpointDetails, topMargin(14));
        renderEndpointDetails();
        choices.setOnCheckedChangeListener((group, checkedId) -> {
            captureEndpointFields();
            endpointMode = checkedId == 1002 ? VpsEndpointPolicy.Mode.DUCKDNS
                    : checkedId == 1003 ? VpsEndpointPolicy.Mode.OWN_DOMAIN
                    : VpsEndpointPolicy.Mode.IP;
            renderEndpointDetails();
            scrollEndpointDetailsIntoView();
        });

        configureFooter(true, R.string.vps_endpoint_continue,
                this::renderAccess, this::prepareEndpoint);
        scrollTop();
    }

    private RadioButton endpointOption(int id, int titleRes, int noteRes, boolean recommended) {
        RadioButton button = new RadioButton(this);
        button.setId(id);
        String titleText = getString(titleRes)
                + (recommended ? " · " + getString(R.string.vps_endpoint_recommended) : "");
        String text = titleText + "\n" + getString(noteRes);
        SpannableString styled = new SpannableString(text);
        styled.setSpan(new StyleSpan(Typeface.BOLD), 0, titleText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(styled);
        button.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        button.setTextSize(14f);
        button.setLineSpacing(0f, 1.1f);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(86));
        button.setPadding(dp(14), dp(12), dp(14), dp(12));
        button.setBackgroundResource(R.drawable.wizard_option_bg);
        button.setButtonTintList(ContextCompat.getColorStateList(this, R.color.accent));
        return button;
    }

    private void renderEndpointDetails() {
        if (endpointDetails == null) return;
        endpointDetails.removeAllViews();
        fieldDuckDomain = null;
        fieldDuckToken = null;
        fieldOwnDomain = null;
        if (endpointMode == VpsEndpointPolicy.Mode.IP) {
            TextView ip = heading(getString(R.string.vps_endpoint_public_ip, publicIp));
            ip.setTextColor(ContextCompat.getColor(this, R.color.accent));
            ip.setBackgroundResource(R.drawable.owner_card_bg);
            ip.setPadding(dp(16), dp(16), dp(16), dp(16));
            endpointDetails.addView(ip);
        } else if (endpointMode == VpsEndpointPolicy.Mode.DUCKDNS) {
            fieldDuckDomain = addFieldTo(endpointDetails, R.string.vps_endpoint_duckdns_domain,
                    duckDnsDomain, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
            fieldDuckToken = addFieldTo(endpointDetails, R.string.vps_endpoint_duckdns_token,
                    duckDnsToken, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);
            TextView help = body(getString(R.string.vps_endpoint_duckdns_help));
            endpointDetails.addView(help, topMargin(10));
            MaterialButton open = outlinedButton(R.string.vps_endpoint_open_duckdns);
            open.setIconResource(R.drawable.ic_link);
            open.setOnClickListener(view -> openDuckDns());
            endpointDetails.addView(open, topMargin(12));
        } else {
            List<String> found = preflightAudit == null ? java.util.Collections.emptyList()
                    : preflightAudit.discoveredDomains();
            fieldOwnDomain = addDomainFieldTo(endpointDetails, ownDomain);
            TextView help = body(getString(R.string.vps_endpoint_own_domain_help));
            endpointDetails.addView(help, topMargin(8));
            if (!found.isEmpty()) {
                MaterialButton choose = outlinedButton(R.string.vps_endpoint_found_domains_title);
                choose.setText(getResources().getQuantityString(
                        R.plurals.vps_endpoint_found_domains_action,
                        found.size(), found.size()));
                choose.setIconResource(R.drawable.ic_link);
                choose.setOnClickListener(view -> showFoundDomains(found));
                endpointDetails.addView(choose, topMargin(12));
            }
        }
    }

    /**
     * A selected option can add several controls below the fold. Keep the new controls above the
     * fixed footer instead of making the user guess that the page must be scrolled manually.
     */
    private void scrollEndpointDetailsIntoView() {
        if (scroll == null || endpointDetails == null) return;
        scroll.post(() -> {
            if (endpointDetails == null || endpointDetails.getHeight() == 0) return;
            int viewport = Math.max(0, scroll.getHeight() - dp(16));
            int target = Math.max(0, endpointDetails.getBottom() - viewport);
            scroll.smoothScrollTo(0, target);
        });
    }

    private void captureEndpointFields() {
        if (fieldDuckDomain != null) duckDnsDomain = clean(fieldDuckDomain.getText().toString());
        if (fieldDuckToken != null) duckDnsToken = fieldDuckToken.getText().toString().trim();
        if (fieldOwnDomain != null) ownDomain = clean(fieldOwnDomain.getText().toString());
    }

    private void prepareEndpoint() {
        captureEndpointFields();
        hideKeyboard();
        String host;
        if (endpointMode == VpsEndpointPolicy.Mode.IP) {
            host = VpsEndpointPolicy.normalizeHost(publicIp);
            if (!VpsEndpointPolicy.isIpLiteral(host)) {
                lastError = getString(R.string.vps_endpoint_invalid_ip);
                renderEndpoint();
                return;
            }
        } else if (endpointMode == VpsEndpointPolicy.Mode.DUCKDNS) {
            host = VpsEndpointPolicy.normalizeHost(duckDnsDomain);
            if (!VpsEndpointPolicy.isDuckDnsDomain(host) || duckDnsToken.isEmpty()) {
                lastError = getString(R.string.vps_endpoint_invalid_duckdns);
                renderEndpoint();
                return;
            }
        } else {
            host = VpsEndpointPolicy.normalizeHost(ownDomain);
            if (!VpsEndpointPolicy.isDomain(host)) {
                lastError = getString(R.string.vps_endpoint_invalid_domain);
                renderEndpoint();
                return;
            }
        }
        final String endpointHost = host;
        if (offerReusableToken(endpointHost)) return;
        startEndpointPreparation(endpointHost);
    }

    private boolean offerReusableToken(String endpointHost) {
        String path = selectedRelay != null && selectedRelay.isUsable()
                ? selectedRelay.path() : "/apiws";
        String existingPublicUrl = preflightAudit == null
                ? "" : preflightAudit.value("existing_relay_public_url");
        String endpointKey = endpointHost.toLowerCase(Locale.US) + ":443" + path
                + "|" + existingPublicUrl;
        if (endpointKey.equals(tokenChoiceEndpoint)) return false;

        VpsRelayConfig endpoint = VpsRelayConfig.manual(true, "VPS Relay", endpointHost,
                443, true, path, "probe-token", profileKey);
        ArrayList<VpsRelayConfig> endpoints = new ArrayList<>();
        endpoints.add(endpoint);
        VpsRelayConfig existingEndpoint = existingRelayEndpoint();
        if (existingEndpoint != null && !existingEndpoint.sameEndpoint(endpoint)) {
            endpoints.add(existingEndpoint);
        }
        LinkedHashMap<String, VpsRelayConfig> choices = new LinkedHashMap<>();
        for (VpsRelayStore.Record record : VpsRelayStore.fromContext(this).relays()) {
            VpsRelayConfig config = record.config();
            if (!config.isUsable() || !matchesAnyEndpoint(config, endpoints)) continue;
            choices.put(VpsOwnerRecord.clientTokenId(config.token()),
                    config.withProfileKey(profileKey));
        }
        VpsOwnerStore ownerStore = new VpsOwnerStore(this);
        VpsOwnerRecord reusableEndpointOwner = null;
        for (VpsRelayConfig candidateEndpoint : endpoints) {
            VpsOwnerRecord endpointOwner = ownerStore.forRelay(candidateEndpoint);
            if (endpointOwner == null) continue;
            if (reusableEndpointOwner == null && endpointOwner.canManage()) {
                reusableEndpointOwner = endpointOwner;
            }
            for (VpsOwnerRecord.ManagedToken token : endpointOwner.managedTokens()) {
                if (token.secret().isEmpty()) continue;
                choices.put(token.id(), candidateEndpoint.withTokenAndName(token.secret(),
                        token.name().isEmpty() ? "VPS Relay" : token.name()));
            }
        }
        final VpsOwnerRecord endpointOwnerForChoice = reusableEndpointOwner;
        if (choices.isEmpty()) {
            reuseEndpointOwner(endpointOwnerForChoice);
            tokenChoiceEndpoint = endpointKey;
            return false;
        }
        if (selectedRelay != null && selectedRelay.isUsable()
                && selectedRelay.sameEndpoint(endpoint)
                && choices.containsKey(VpsOwnerRecord.clientTokenId(relayToken))) {
            tokenChoiceEndpoint = endpointKey;
            return false;
        }

        ArrayList<VpsRelayConfig> reusable = new ArrayList<>(choices.values());
        ArrayList<String> labels = new ArrayList<>();
        for (VpsRelayConfig config : reusable) {
            labels.add(getString(R.string.vps_setup_token_existing_item,
                    config.name(), config.maskedToken()));
        }
        labels.add(getString(R.string.vps_setup_token_create_new));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_setup_token_choice_title)
                .setMessage(R.string.vps_setup_token_choice_note)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < reusable.size()) {
                        VpsRelayConfig chosen = reusable.get(which);
                        relayToken = chosen.token();
                        if (chosen.name() != null && !chosen.name().trim().isEmpty()) {
                            selectedRelay = chosen;
                        }
                        VpsOwnerRecord owner = new VpsOwnerStore(this).forRelay(chosen);
                        if (owner != null && owner.canManage()) {
                            savedOwner = owner;
                            ownerExisted = true;
                            adminToken = owner.adminToken();
                        }
                    } else {
                        relayToken = generateToken("tgpc_");
                        reuseEndpointOwner(endpointOwnerForChoice);
                    }
                    tokenChoiceEndpoint = endpointKey;
                    startEndpointPreparation(endpointHost);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return true;
    }

    private void reuseEndpointOwner(VpsOwnerRecord owner) {
        if (owner == null || !owner.canManage()) return;
        savedOwner = owner;
        ownerExisted = true;
        adminToken = owner.adminToken();
    }

    private VpsRelayConfig existingRelayEndpoint() {
        if (preflightAudit == null) return null;
        String value = preflightAudit.value("existing_relay_public_url");
        if (value == null || value.trim().isEmpty()) return null;
        try {
            java.net.URI uri = new java.net.URI(value.trim());
            boolean tls = "https".equalsIgnoreCase(uri.getScheme());
            if (!tls && !"http".equalsIgnoreCase(uri.getScheme())) return null;
            String host = uri.getHost();
            if (host == null || host.trim().isEmpty()) return null;
            int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
            String path = uri.getPath() == null || uri.getPath().trim().isEmpty()
                    ? "/apiws" : uri.getPath().trim();
            return VpsRelayConfig.manual(true, "VPS Relay", host, port, tls, path,
                    "probe-token", profileKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean matchesAnyEndpoint(VpsRelayConfig relay,
                                              List<VpsRelayConfig> endpoints) {
        if (relay == null || endpoints == null) return false;
        for (VpsRelayConfig endpoint : endpoints) {
            if (endpoint != null && relay.sameEndpoint(endpoint)) return true;
        }
        return false;
    }

    private void startEndpointPreparation(String endpointHost) {
        running = true;
        renderProgressPage(
                endpointMode == VpsEndpointPolicy.Mode.DUCKDNS
                        ? R.string.vps_setup_step_endpoint : R.string.vps_setup_step_plan,
                endpointMode == VpsEndpointPolicy.Mode.DUCKDNS ? 50 : 75,
                endpointMode == VpsEndpointPolicy.Mode.DUCKDNS
                        ? R.string.vps_endpoint_updating_duckdns
                        : R.string.vps_endpoint_preparing_plan);
        new Thread(() -> {
            try {
                if (endpointMode == VpsEndpointPolicy.Mode.DUCKDNS) {
                    if (!new DuckDnsClient().update(endpointHost, duckDnsToken, publicIp)) {
                        throw new VpsSetupException(getString(R.string.vps_endpoint_duckdns_failed));
                    }
                    if (!new VpsEndpointCredentialStore(this)
                            .saveDuckDnsToken(endpointHost, duckDnsToken)) {
                        DiagnosticsLog.record("DuckDNS credential secure save failed");
                    }
                    if (!DnsAddressVerifier.waitUntilMatches(endpointHost, publicIp,
                            120_000L, 5_000L)) {
                        throw new VpsSetupException(getString(R.string.vps_endpoint_dns_mismatch,
                                endpointHost, publicIp));
                    }
                }
                setupRequest = baseRequest(endpointHost, 443, true, updateExisting);
                runSetup(setupRequest);
            } catch (Exception error) {
                handler.post(() -> {
                    if (isFinishing()) return;
                    running = false;
                    lastError = firstLine(error.getMessage());
                    renderEndpoint();
                });
            }
        }, "tg-vps-endpoint-prepare").start();
    }

    private VpsSetupRequest baseRequest(String relayHost, int relayPort,
                                        boolean tls, boolean update) {
        String name = selectedRelay != null && !selectedRelay.name().isEmpty()
                ? selectedRelay.name() : "VPS Relay";
        String path = selectedRelay != null && selectedRelay.isUsable()
                ? selectedRelay.path() : "/apiws";
        return VpsSetupRequest.builder()
                .sshHost(sshHost)
                .sshPort(sshPort)
                .sshUser(sshUser)
                .sshPassword(sshPassword)
                .relayName(name)
                .relayHost(relayHost)
                .relayPort(relayPort)
                .relayTls(tls)
                .relayPath(path)
                .relayToken(relayToken)
                .adminToken(adminToken)
                .releaseVersion(VpsSetupScripts.RELAY_VERSION)
                .profileKey(profileKey)
                .updateExistingRelay(update)
                .rememberSshPassword(rememberCredentials)
                .build();
    }

    private void runSetup(VpsSetupRequest request) throws Exception {
        if (request == null || !request.isValid()) throw new VpsSetupException("invalid VPS setup request");
        VpsOwnerStore ownerStore = new VpsOwnerStore(this);
        AtomicBoolean ownerPreSaveFailed = new AtomicBoolean(false);
        AtomicBoolean ownerRecordCreatedForAttempt = new AtomicBoolean(false);
        VpsAutoSetupWizard wizard = new VpsAutoSetupWizard(
                createSshClient(),
                (config, rules) -> new VpsRelayClient().check(config, rules),
                VpsRelayStore.fromContext(this), MtProtoConfig.relayDcRules());
        try {
            VpsRelayConfig result = wizard.run(request, new VpsAutoSetupWizard.Listener() {
                @Override public void onProgress(VpsSetupProgress progress) {
                    handler.post(() -> updateInstallProgress(progress));
                }

                @Override public boolean onPlan(VpsSetupPlan plan) {
                    boolean approved = awaitPlanApproval(plan);
                    if (approved && !ownerExisted) {
                        boolean exactOwnerAlreadyExisted =
                                ownerStore.forRelay(request.relayConfig()) != null;
                        if (!ownerStore.saveSetup(request, request.relayConfig())) {
                            ownerPreSaveFailed.set(true);
                            return false;
                        }
                        ownerRecordCreatedForAttempt.set(!exactOwnerAlreadyExisted);
                    }
                    return approved;
                }
            });
            boolean ownerSaved = ownerStore.saveSetup(request, result);
            if (ownerSaved) new VpsSshDraftStore(this).clear();
            if (!ownerSaved) DiagnosticsLog.record("VPS owner credentials could not be persisted");
            handler.post(() -> {
                if (isFinishing()) return;
                running = false;
                mutating = false;
                completedRelay = result;
                renderComplete(ownerSaved);
            });
        } catch (Exception error) {
            // Roll back only the alias created by this attempt. Never erase an owner record that
            // existed before setup merely because a second token or SSH account failed later.
            if (ownerRecordCreatedForAttempt.get()) ownerStore.forget(request.relayConfig());
            if (ownerPreSaveFailed.get()) {
                throw new VpsSetupException(getString(R.string.vps_owner_pre_save_failed), error);
            }
            if (planDeclined) {
                handler.post(() -> {
                    running = false;
                    mutating = false;
                    lastError = "";
                    renderEndpoint();
                });
                return;
            }
            throw error;
        }
    }

    private boolean awaitPlanApproval(VpsSetupPlan plan) {
        planDeclined = false;
        planApproval = new AtomicBoolean(false);
        planLatch = new CountDownLatch(1);
        handler.post(() -> renderPlan(plan));
        try {
            planLatch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            planLatch = null;
        }
        return planApproval.get();
    }

    private void renderPlan(VpsSetupPlan plan) {
        if (isFinishing()) {
            planLatch.countDown();
            return;
        }
        boolean canApply = plan != null && plan.canApply();
        page = Page.PLAN;
        running = false;
        setHeader(R.string.vps_setup_plan_title, R.string.vps_setup_step_plan, 75);
        content.removeAllViews();
        addHero(R.drawable.ic_shield, R.string.vps_setup_plan_title,
                R.string.vps_setup_plan_note);
        List<String> blockers = plan == null
                ? java.util.Collections.singletonList(getString(R.string.vps_setup_plan_unknown))
                : plan.userBlockers();
        List<String> warnings = plan == null
                ? java.util.Collections.emptyList() : plan.userWarnings();
        addPlanCard(
                getString(canApply ? R.string.vps_setup_plan_ready_title
                        : R.string.vps_setup_plan_blocked_title),
                java.util.Collections.singletonList(getString(canApply
                        ? (warnings.isEmpty() ? R.string.vps_setup_plan_ready_note
                                : R.string.vps_setup_plan_ready_warning_note)
                        : R.string.vps_setup_plan_blocked_note)),
                canApply ? R.drawable.status_success_bg : R.drawable.status_error_bg,
                canApply ? R.color.green : R.color.red);
        if (!blockers.isEmpty()) {
            addPlanCard(getString(R.string.vps_setup_plan_fix_title), blockers,
                    R.drawable.status_error_bg, R.color.red);
        }
        if (!warnings.isEmpty()) {
            addPlanCard(getString(R.string.vps_setup_plan_warning_title), warnings,
                    R.drawable.status_warning_bg, R.color.warning);
        }
        if (plan != null) {
            addPlanCard(getString(R.string.vps_setup_plan_actions_title), plan.userActions(),
                    R.drawable.owner_card_bg, R.color.accent);
            addTechnicalPlanDetails(plan.technicalSummary());
        }
        configureFooter(true, canApply
                ? R.string.vps_setup_apply : R.string.vps_setup_plan_blocked_action, () -> {
            planDeclined = true;
            planApproval.set(false);
            planLatch.countDown();
            renderEndpoint();
        }, () -> {
            if (!canApply) return;
            planApproval.set(true);
            mutating = true;
            running = true;
            planLatch.countDown();
            renderInstall();
        });
        next.setEnabled(canApply);
        if (!canApply) {
            next.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.input_bg)));
            next.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
        scrollTop();
    }

    private void addPlanCard(String titleText, List<String> items,
                             int backgroundRes, int accentColorRes) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(backgroundRes);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        TextView cardTitle = heading(titleText);
        cardTitle.setTextSize(16f);
        cardTitle.setTextColor(ContextCompat.getColor(this, accentColorRes));
        card.addView(cardTitle);
        if (items != null) {
            for (String item : items) {
                if (item == null || item.trim().isEmpty()) continue;
                TextView row = body("•  " + item.trim());
                row.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
                row.setLineSpacing(0f, 1.08f);
                card.addView(row, topMargin(9));
            }
        }
        content.addView(card, topMargin(14));
    }

    private void addTechnicalPlanDetails(String details) {
        MaterialButton toggle = outlinedButton(R.string.vps_setup_plan_technical_show);
        toggle.setIconResource(R.drawable.ic_chevron_right);
        content.addView(toggle, topMargin(14));

        TextView technical = body(details == null ? "" : details);
        technical.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        technical.setTextIsSelectable(true);
        technical.setTextSize(12f);
        technical.setBackgroundResource(R.drawable.owner_card_bg);
        technical.setPadding(dp(14), dp(14), dp(14), dp(14));
        technical.setVisibility(View.GONE);
        content.addView(technical, topMargin(8));
        toggle.setOnClickListener(view -> {
            boolean show = technical.getVisibility() != View.VISIBLE;
            technical.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? R.string.vps_setup_plan_technical_hide
                    : R.string.vps_setup_plan_technical_show);
        });
    }

    private void renderInstall() {
        page = Page.INSTALL;
        setHeader(R.string.vps_setup_progress_title, R.string.vps_setup_step_install, 90);
        content.removeAllViews();
        addHero(R.drawable.ic_server, R.string.vps_setup_progress_title,
                R.string.vps_setup_progress_note);
        installProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        installProgress.setMax(100);
        installProgress.setProgress(25);
        content.addView(installProgress, fixedTop(dp(8), 18));
        installStatus = heading(getString(R.string.vps_setup_checking_server));
        installStatus.setTextSize(15f);
        installStatus.setBackgroundResource(R.drawable.owner_card_bg);
        installStatus.setPadding(dp(16), dp(16), dp(16), dp(16));
        content.addView(installStatus, topMargin(14));
        footer.setVisibility(View.GONE);
        scrollTop();
    }

    private void updateInstallProgress(VpsSetupProgress value) {
        if (value == null || isFinishing()) return;
        if (page != Page.INSTALL) renderInstall();
        if (installProgress != null) installProgress.setProgress(value.percent());
        if (installStatus != null) installStatus.setText(value.message());
        stepProgress.setProgress(Math.max(75, value.percent()));
        mutating = value.stage() == VpsSetupProgress.Stage.BACKUP
                || value.stage() == VpsSetupProgress.Stage.INSTALL
                || value.stage() == VpsSetupProgress.Stage.VERIFY
                || value.stage() == VpsSetupProgress.Stage.ROLLBACK;
    }

    private void renderComplete(boolean ownerSaved) {
        page = Page.COMPLETE;
        setHeader(R.string.vps_setup_complete_title, R.string.vps_setup_step_complete, 100);
        content.removeAllViews();
        addHero(R.drawable.ic_shield, R.string.vps_setup_complete_title,
                R.string.vps_setup_complete_note);
        if (completedRelay != null) {
            LinearLayout endpoint = new LinearLayout(this);
            endpoint.setOrientation(LinearLayout.VERTICAL);
            endpoint.setBackgroundResource(R.drawable.owner_card_bg);
            endpoint.setPadding(dp(16), dp(16), dp(16), dp(16));
            TextView label = body(getString(R.string.vps_setup_complete_endpoint));
            endpoint.addView(label);
            TextView value = heading(completedRelay.host() + ":" + completedRelay.port()
                    + completedRelay.path());
            value.setTextSize(16f);
            value.setTextColor(ContextCompat.getColor(this, R.color.accent));
            endpoint.addView(value, topMargin(6));
            content.addView(endpoint, topMargin(16));
            addActionButton(R.drawable.ic_share, R.string.relay_share_title,
                    () -> RelayShareSheet.show(this, completedRelay));
        }
        if (!ownerSaved) addError(getString(R.string.vps_owner_save_failed));
        configureFooter(false, R.string.vps_setup_finish, null, this::finishWithResult);
        scrollTop();
    }

    private void finishWithResult() {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RELAY_ID, relayId));
        finish();
    }

    private void renderProgressPage(int stepRes, int percent, int messageRes) {
        page = Page.INSTALL;
        setHeader(R.string.vps_setup_title, stepRes, percent);
        content.removeAllViews();
        ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        spinnerParams.gravity = Gravity.CENTER_HORIZONTAL;
        spinnerParams.topMargin = dp(44);
        content.addView(spinner, spinnerParams);
        TextView message = heading(getString(messageRes));
        message.setGravity(Gravity.CENTER);
        message.setTextSize(16f);
        content.addView(message, topMargin(20));
        footer.setVisibility(View.GONE);
        scrollTop();
    }

    private void addHero(int iconRes, int titleRes, int noteRes) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.TOP);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        hero.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsParams.setMargins(dp(14), 0, 0, 0);
        hero.addView(labels, labelsParams);
        TextView heading = heading(getString(titleRes));
        labels.addView(heading);
        labels.addView(body(getString(noteRes)), topMargin(8));
        content.addView(hero);
    }

    private void addStatusCard(String headingText, String noteText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.status_success_bg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView heading = heading(headingText);
        heading.setTextSize(15f);
        heading.setTextColor(ContextCompat.getColor(this, R.color.green));
        card.addView(heading);
        card.addView(body(noteText), topMargin(5));
        content.addView(card, topMargin(16));
    }

    private void addError(String message) {
        TextView error = body(message);
        error.setTextColor(ContextCompat.getColor(this, R.color.red));
        error.setBackgroundResource(R.drawable.danger_section_bg);
        error.setPadding(dp(14), dp(14), dp(14), dp(14));
        content.addView(error, topMargin(14));
    }

    private EditText addField(int hintRes, String value, int inputType, boolean password) {
        return addFieldTo(content, hintRes, value, inputType, password);
    }

    private EditText addFieldTo(LinearLayout parent, int hintRes, String value,
                                int inputType, boolean password) {
        TextInputLayout wrapper = new TextInputLayout(this);
        wrapper.setHint(getString(hintRes));
        wrapper.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        wrapper.setBoxCornerRadii(dp(16), dp(16), dp(16), dp(16));
        wrapper.setBoxStrokeColor(ContextCompat.getColor(this, R.color.accent));
        wrapper.setHintTextColor(ContextCompat.getColorStateList(this, R.color.text_secondary));
        if (password) wrapper.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        TextInputEditText edit = new TextInputEditText(wrapper.getContext());
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        edit.setText(value == null ? "" : value);
        edit.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        edit.setTextSize(14f);
        edit.setMinHeight(dp(56));
        wrapper.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(wrapper, topMargin(12));
        return edit;
    }

    /**
     * The own-domain address deliberately uses a fixed label above the outline. A floating
     * TextInputLayout hint is hard to read on a number of OEM Material themes because the hint's
     * cut-out and the outline are rendered at different vertical offsets.
     */
    private EditText addDomainFieldTo(LinearLayout parent, String value) {
        TextView label = body(getString(R.string.vps_endpoint_own_domain));
        label.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        parent.addView(label);

        TextInputLayout wrapper = new TextInputLayout(this);
        wrapper.setHintEnabled(false);
        wrapper.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        wrapper.setBoxCornerRadii(dp(16), dp(16), dp(16), dp(16));
        wrapper.setBoxStrokeColor(ContextCompat.getColor(this, R.color.accent));

        TextInputEditText edit = new TextInputEditText(wrapper.getContext());
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setHint(R.string.vps_endpoint_own_domain_placeholder);
        edit.setText(value == null ? "" : value);
        edit.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        edit.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        edit.setTextSize(14f);
        edit.setMinHeight(dp(56));
        wrapper.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(wrapper, topMargin(7));
        return edit;
    }

    private void showFoundDomains(List<String> domains) {
        hideKeyboard();
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(22), dp(20), dp(22), dp(18));
        sheet.setBackgroundResource(R.drawable.owner_card_bg);

        TextView sheetTitle = heading(getString(R.string.vps_endpoint_found_domains_title));
        sheetTitle.setTextSize(19f);
        sheet.addView(sheetTitle);
        sheet.addView(body(getString(R.string.vps_endpoint_found_domains_note)), topMargin(8));

        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(false);
        listScroll.setClipToPadding(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(4));
        listScroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (String domain : domains) {
            MaterialButton item = outlinedButton(R.string.vps_endpoint_found_domains_title);
            item.setText(domain);
            item.setIconResource(R.drawable.ic_link);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setSingleLine(true);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setContentDescription(getString(R.string.vps_endpoint_use_found_domain, domain));
            item.setOnClickListener(view -> {
                ownDomain = domain;
                if (fieldOwnDomain != null) {
                    fieldOwnDomain.setText(domain);
                    fieldOwnDomain.setSelection(domain.length());
                }
                dialog.dismiss();
                handler.post(this::prepareEndpoint);
            });
            list.addView(item, topMargin(8));
        }
        int available = getResources().getDisplayMetrics().heightPixels - dp(260);
        int contentHeight = dp(Math.min(360, 12 + domains.size() * 60));
        int listHeight = Math.max(dp(120), Math.min(contentHeight, available));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listHeight);
        listParams.setMargins(0, dp(12), 0, 0);
        sheet.addView(listScroll, listParams);

        MaterialButton close = outlinedButton(R.string.vps_endpoint_found_domains_close);
        close.setOnClickListener(view -> dialog.dismiss());
        sheet.addView(close, topMargin(12));
        dialog.setContentView(sheet);
        dialog.setOnShowListener(ignored -> {
            View bottomSheet = dialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        dialog.show();
    }

    private void addActionButton(int iconRes, int textRes, Runnable action) {
        MaterialButton button = outlinedButton(textRes);
        button.setIconResource(iconRes);
        button.setOnClickListener(view -> action.run());
        content.addView(button, topMargin(14));
    }

    private MaterialButton outlinedButton(int textRes) {
        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        button.setCornerRadius(dp(16));
        return button;
    }

    private TextView heading(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        view.setTextSize(20f);
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

    private void setHeader(int titleRes, int stepRes, int progress) {
        title.setText(titleRes);
        step.setText(stepRes);
        stepProgress.setProgress(progress);
    }

    private void configureFooter(boolean showPrevious, int nextText,
                                 Runnable previousAction, Runnable nextAction) {
        footer.setVisibility(View.VISIBLE);
        previous.setVisibility(showPrevious ? View.VISIBLE : View.GONE);
        previous.setOnClickListener(view -> {
            if (previousAction != null) previousAction.run();
        });
        next.setText(nextText);
        next.setEnabled(true);
        next.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent)));
        next.setTextColor(ContextCompat.getColor(this, R.color.button_text));
        next.setOnClickListener(view -> {
            if (nextAction != null) nextAction.run();
        });
    }

    private void forgetHostKey() {
        captureAccess();
        if (sshHost.isEmpty()) {
            Toast.makeText(this, R.string.vps_setup_ssh_host_required, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_setup_forget_ssh_key)
                .setMessage(getString(R.string.vps_setup_forget_ssh_key_confirm, sshHost, sshPort))
                .setPositiveButton(R.string.vps_setup_forget_ssh_key, (dialog, which) -> {
                    try {
                        boolean removed = createSshClient().forgetHost(sshHost, sshPort);
                        Toast.makeText(this, removed ? R.string.vps_setup_ssh_key_forgotten
                                : R.string.vps_setup_ssh_key_not_found, Toast.LENGTH_LONG).show();
                    } catch (Exception error) {
                        Toast.makeText(this, R.string.vps_setup_ssh_key_forget_failed,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private JschVpsSshClient createSshClient() {
        return new JschVpsSshClient(new File(getFilesDir(), KNOWN_HOSTS_FILE),
                this::confirmFirstHostKey);
    }

    private boolean confirmFirstHostKey(String host, String algorithm, String fingerprint) {
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(false);
        handler.post(() -> {
            if (isFinishing()) {
                latch.countDown();
                return;
            }
            androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.vps_setup_ssh_key_confirm_title)
                    .setMessage(getString(R.string.vps_setup_ssh_key_confirm_message,
                            host, algorithm, fingerprint))
                    .setPositiveButton(R.string.vps_setup_ssh_key_trust, (ignored, which) -> {
                        accepted.set(true);
                        latch.countDown();
                    })
                    .setNegativeButton(android.R.string.cancel,
                            (ignored, which) -> latch.countDown())
                    .create();
            dialog.setOnCancelListener(ignored -> latch.countDown());
            dialog.show();
        });
        try {
            return latch.await(2, TimeUnit.MINUTES) && accepted.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void openDuckDns() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.duckdns.org/")));
        } catch (Exception error) {
            Toast.makeText(this, "https://www.duckdns.org/", Toast.LENGTH_LONG).show();
        }
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        focus.clearFocus();
    }

    private void scrollTop() {
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private LinearLayout.LayoutParams topMargin(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(value);
        return params;
    }

    private LinearLayout.LayoutParams fixedTop(int height, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = dp(margin);
        return params;
    }

    private RadioGroup.LayoutParams radioMargin() {
        RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int intValue(String raw, int fallback) {
        try {
            int value = Integer.parseInt(clean(raw));
            return value > 0 && value <= 65535 ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String generateToken(String prefix) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(prefix);
        for (byte value : bytes) out.append(String.format(Locale.US, "%02x", value & 0xff));
        return out.toString();
    }

    private static String firstLine(String value) {
        String text = clean(value);
        int newline = text.indexOf('\n');
        if (newline >= 0) text = text.substring(0, newline).trim();
        return text.isEmpty() ? "unknown" : text;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String dash(String value) {
        String text = clean(value);
        return text.isEmpty() ? "—" : text;
    }
}
