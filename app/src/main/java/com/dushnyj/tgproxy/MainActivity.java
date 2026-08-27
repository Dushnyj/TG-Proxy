package com.dushnyj.tgproxy;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.FileProvider;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity {
    private static final String REPO_URL = "https://github.com/Dushnyj/TG-Proxy";
    private static final int REQUEST_IMPORT_FILE = 1101;
    private static final int REQUEST_INSTALL_UPDATE = 1102;
    private static final int REQUEST_VPS_SETUP = 1103;
    private static final int REQUEST_VPS_OWNER = 1104;
    private static final int REQUEST_VPS_CONNECTIONS = 1105;
    private static final int REQUEST_NETWORK_IDENTITY_PERMISSIONS = 100;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 101;
    private static final String STATE_SCREEN = "screen";
    private static final String STATE_SECTION = "settings_section";
    private static final String STATE_DIAGNOSTICS_RETURN = "diagnostics_return";
    private static final String SCREEN_MAIN = "main";
    private static final String SCREEN_SETTINGS = "settings";
    private static final String SCREEN_DIAGNOSTICS = "diagnostics";
    private static final int VPS_DOMAIN_DISCOVERY_TIMEOUT_MS = 30_000;
    private static final String VPS_SSH_KNOWN_HOSTS_FILE = "vps_ssh_known_hosts";
    private static final String KEY_VPS_RELAY_LEGACY_MIGRATED = "vps_relay_legacy_migrated.v1";
    private static final String KEY_PENDING_INSTALL_VERSION = "update_pending_version.v1";
    private static final String KEY_PENDING_INSTALL_DESIRED = "update_pending_desired_running.v1";
    private static final String KEY_NOTIFICATION_PERMISSION_REQUESTED =
            "notification_permission_requested.v1";

    private ImageButton btnStart;
    private Button btnStop, btnRegenerateSecret;
    private View btnOpenSettings, btnBackMain, btnOpenDiagnostics, btnOpenGithub, btnOpenTelegram, btnMainMenu;
    private Button btnTestCf, btnTestWorker;
    private Button btnRouteDirectOnly;
    private Button btnVpsRelayTest;
    private Button btnVpsRelayNew, btnVpsRelaySave, btnVpsRelayDelete, btnVpsRelayAutoSetup;
    private Button btnVpsOwnerManage, btnVpsRelayConnections;
    private Button btnVpsManualToggle, btnConnectionAdvancedToggle;
    private View btnCfHelp, btnWorkerHelp;
    private Button btnCheckUpdate, btnOpenRelease, btnInstallUpdate;
    private Button btnCreateProfile, btnSaveProfile, btnDeleteProfile;
    private Button btnExportSafeProfile, btnExportVpsRelay, btnExportEncryptedProfile, btnImportSettings;
    private Button btnScanQr;
    private Button btnSettingsDiagnostics;
    private Button btnBackgroundSetup;
    private Button btnDiagnosticsSaveZip, btnDiagnosticsSaveTxt, btnDiagnosticsCopyShort;
    private Button btnDiagnosticsShare, btnDiagnosticsReset;
    private TextView tvStatus, tvAddress, tvRoute, tvCfDomain, tvPort, tvTgLink, tvPing, tvTraffic, tvUptime;
    private TextView tvMainProfile, tvQuality, tvConnections;
    private TextView tvActiveProfile, tvProfileKey, tvProfilesList;
    private TextView tvRoutePreferenceExplanation;
    private TextView tvUpdateStatus, tvUpdateProgress, tvVersion;
    private TextView tvVpsSetupStatus;
    private TextView tvBackgroundStatus;
    private TextView tvDiagnosticsNetwork, tvDiagnosticsProfile, tvDiagnosticsRoute;
    private TextView tvDiagnosticsRouteChecks, tvDiagnosticsHistory, tvDiagnosticsErrors;
    private TextView tvDiagnosticsService, tvDiagnosticsReport;
    private View tvGithub;
    private EditText etCustomIp, etCustomPort, etSecret, etDcRules, etCfDomains, etWorkerDomains;
    private EditText etVpsRelayName, etVpsRelayHost, etVpsRelayPort, etVpsRelayPath, etVpsRelayToken;
    private EditText etProfileName;
    private CheckBox cbSmartSleep, cbAutostartOpen, cbAutostartBoot;
    private CheckBox cbVpsRelayEnabled, cbVpsRelayTls, cbVpsRelayBindProfile;
    private CheckBox cbCfCustomDomain, cbCfWarmup, cbCfRecheckNetwork, cbVerbose, cbCheckUpdates;
    private CheckBox cbRouteDirect, cbRouteRelay, cbRouteWorker, cbRouteCustomCf, cbRoutePublicCf;
    private ProgressBar progressUpdate;
    private ProgressBar progressVpsSetup;
    private Spinner spCfMode, spTheme, spLanguage, spProfileSelector, spRoutePreference, spVpsRelaySaved;
    private View mainScreen, settingsScreen, diagnosticsScreen, btnBackDiagnostics;
    private View navConnection, navRoutes, navRelay, navSystem, navAbout;
    private View sectionConnection, sectionRoute, sectionProfiles, sectionOptimization, sectionVpsRelay;
    private View sectionImportExport, sectionDiagnosticsLogs, sectionBehavior;
    private View sectionInterface, sectionUpdates, sectionAdvanced, sectionAbout;
    private View vpsManualContent, connectionAdvancedContent;

    private SharedPreferences prefs;
    private Handler handler;
    private Runnable statsUpdater;
    private GithubReleaseUpdater.ReleaseInfo lastRelease;
    private String updateDialogVersion = "";
    private boolean pendingInstallAfterPermission;
    private String pendingInstallVersion = "";
    private boolean spinnerInit;
    private boolean profileControlReady;
    private boolean profileSelectorReady;
    private boolean vpsRelaySelectorReady;
    private boolean vpsRelayFormBinding;
    private boolean vpsSetupRunning;
    private boolean backgroundSetupDialogShowing;
    private AlertDialog backgroundSetupDialog;
    private LinearLayout backgroundSetupStatusList;
    private boolean backgroundAutostartSettingsOpened;
    private final AtomicBoolean vpsRelayTestRunning = new AtomicBoolean(false);
    private final AtomicBoolean vpsRelayVersionCheckRunning = new AtomicBoolean(false);
    private String vpsRelayUpdateDialogKey = "";
    private boolean diagnosticsReturnToSettings;
    private String activeProfileKey = "";
    private String displayedProfileKey = "";
    private final AutoPingGate autoPingGate = new AutoPingGate(MainUiState.AUTO_PING_INTERVAL_MS);
    private int lastMeasuredPingMs = -1;
    private long lastMeasuredPingAt;
    private String lastMeasuredPingIdentity = "";
    private BootstrapPingPlanner.Plan bootstrapPingPlan = BootstrapPingPlanner.Plan.empty();
    private long bootstrapPingPlanBuiltAt;
    private MainUiState.SettingsSection currentSettingsSection = MainUiState.SettingsSection.CONNECTION;
    private final ArrayList<String> profileSelectorKeys = new ArrayList<>();
    private final ArrayList<String> vpsRelaySelectorIds = new ArrayList<>();

    private void applyStoredTheme() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        String mode = p.getString("theme_mode", "system");
        int nightMode;
        if ("light".equals(mode)) nightMode = AppCompatDelegate.MODE_NIGHT_NO;
        else if ("dark".equals(mode)) nightMode = AppCompatDelegate.MODE_NIGHT_YES;
        else nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(nightMode);
    }

    private void applyStoredLanguage() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        String mode = p.getString("language_mode", "system");
        String tags = "ru".equals(mode) || "en".equals(mode) ? mode : "";
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyStoredTheme();
        applyStoredLanguage();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        handler = new Handler(Looper.getMainLooper());
        pendingInstallVersion = prefs.getString(KEY_PENDING_INSTALL_VERSION, "");

        ensureDefaults();
        bindViews();
        loadSettings();
        setupActions();
        refreshConnectionFields();
        updateRunningState(ProxyService.getInstance() != null);
        boolean launchedForImport = handleImportIntent(getIntent());
        restoreUiState(savedInstanceState);
        if (!launchedForImport) {
            boolean networkPermissionDialogShown = requestNetworkIdentityPermissions();
            if (!networkPermissionDialogShown) {
                handler.postDelayed(this::showBackgroundSetupOnce, 900L);
            }
        }

        ProxyServiceLauncher.restoreIfDesired(this, "activity-open");

        statsUpdater = new Runnable() {
            @Override public void run() {
                updateStats();
                if (settingsScreen != null && settingsScreen.getVisibility() == View.VISIBLE) {
                    refreshProfileControls(false);
                }
                handler.postDelayed(this, MainUiState.STATS_REFRESH_INTERVAL_MS);
            }
        };

        if (cbAutostartOpen.isChecked() && ProxyService.getInstance() == null) {
            startProxy();
        }
        if (cbCheckUpdates.isChecked()) {
            checkUpdates(false);
        }
        checkActiveVpsRelayVersion(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleImportIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        String screen = SCREEN_MAIN;
        if (diagnosticsScreen != null && diagnosticsScreen.getVisibility() == View.VISIBLE) {
            screen = SCREEN_DIAGNOSTICS;
        } else if (settingsScreen != null && settingsScreen.getVisibility() == View.VISIBLE) {
            screen = SCREEN_SETTINGS;
        }
        outState.putString(STATE_SCREEN, screen);
        outState.putString(STATE_SECTION, currentSettingsSection.name());
        outState.putBoolean(STATE_DIAGNOSTICS_RETURN, diagnosticsReturnToSettings);
    }

    private void restoreUiState(Bundle state) {
        if (state == null) return;
        try {
            currentSettingsSection = MainUiState.SettingsSection.valueOf(
                    state.getString(STATE_SECTION, MainUiState.SettingsSection.CONNECTION.name()));
        } catch (Exception ignored) {
            currentSettingsSection = MainUiState.SettingsSection.CONNECTION;
        }
        diagnosticsReturnToSettings = state.getBoolean(STATE_DIAGNOSTICS_RETURN, false);
        String screen = state.getString(STATE_SCREEN, SCREEN_MAIN);
        if (SCREEN_DIAGNOSTICS.equals(screen)) {
            if (mainScreen != null) mainScreen.setVisibility(View.GONE);
            if (settingsScreen != null) settingsScreen.setVisibility(View.GONE);
            if (diagnosticsScreen != null) diagnosticsScreen.setVisibility(View.VISIBLE);
            refreshDiagnosticsScreen();
        } else if (SCREEN_SETTINGS.equals(screen)) {
            showSettingsScreen(true);
        } else {
            showSettingsScreen(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            if (scan.getContents() != null) {
                importSettingsPayload(scan.getContents(), "", true);
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT_FILE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            try {
                importSettingsPayload(readTextFromUri(data.getData()), "");
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.import_failed,
                        e.getMessage()), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_INSTALL_UPDATE) {
            verifyPendingUpdateInstall();
        } else if (requestCode == REQUEST_VPS_SETUP || requestCode == REQUEST_VPS_OWNER
                || requestCode == REQUEST_VPS_CONNECTIONS) {
            refreshVpsRelaySelector();
            refreshConnectionFields();
            updateVpsRelayFieldsEnabled();
        }
    }

    private void ensureDefaults() {
        SharedPreferences.Editor e = prefs.edit();
        if (!prefs.contains("mtproto_secret")) e.putString("mtproto_secret", MtProtoConfig.generateSecretHex());
        if (!prefs.contains("custom_ip")) e.putString("custom_ip", MtProtoConfig.DEFAULT_HOST);
        if (!prefs.contains("custom_port")) e.putInt("custom_port", MtProtoConfig.DEFAULT_PORT);
        if (!prefs.contains("dc_rules")) e.putString("dc_rules", MtProtoConfig.DEFAULT_DC_RULES);
        if (!prefs.contains("cfproxy_mode")) {
            String mode = prefs.contains("cfproxy_enabled") && !prefs.getBoolean("cfproxy_enabled", true)
                    ? MtProtoProxyEngine.CF_MODE_OFF
                    : MtProtoProxyEngine.CF_MODE_AUTO;
            e.putString("cfproxy_mode", mode);
        }
        if (!prefs.contains("cf_warmup")) e.putBoolean("cf_warmup", true);
        if (!prefs.contains("cf_recheck_network")) e.putBoolean("cf_recheck_network", true);
        e.putBoolean("smart_sleep", false);
        if (!prefs.contains("autostart_boot")) e.putBoolean("autostart_boot", true);
        if (!prefs.contains("buffer_kb")) e.putInt("buffer_kb", MtProtoConfig.DEFAULT_BUFFER_KB);
        if (!prefs.contains("pool_size")) e.putInt("pool_size", MtProtoConfig.DEFAULT_POOL_SIZE);
        if (!prefs.contains("check_updates")) e.putBoolean("check_updates", true);
        e.apply();
    }

    private void bindViews() {
        mainScreen = findViewById(R.id.main_screen);
        settingsScreen = findViewById(R.id.settings_screen);
        diagnosticsScreen = findViewById(R.id.diagnostics_screen);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        btnBackMain = findViewById(R.id.btn_back_main);
        btnBackDiagnostics = findViewById(R.id.btn_back_diagnostics);
        btnOpenDiagnostics = findViewById(R.id.btn_open_diagnostics);
        btnOpenGithub = findViewById(R.id.btn_open_github);
        btnOpenTelegram = findViewById(R.id.btn_open_telegram);
        btnMainMenu = findViewById(R.id.btn_main_menu);
        btnRegenerateSecret = findViewById(R.id.btn_regenerate_secret);
        btnTestCf = findViewById(R.id.btn_test_cf);
        btnTestWorker = findViewById(R.id.btn_test_worker);
        btnRouteDirectOnly = findViewById(R.id.btn_route_direct_only);
        btnVpsRelayTest = findViewById(R.id.btn_vps_relay_test);
        btnVpsRelayNew = findViewById(R.id.btn_vps_relay_new);
        btnVpsRelaySave = findViewById(R.id.btn_vps_relay_save);
        btnVpsRelayDelete = findViewById(R.id.btn_vps_relay_delete);
        btnVpsRelayAutoSetup = findViewById(R.id.btn_vps_relay_auto_setup);
        btnVpsOwnerManage = findViewById(R.id.btn_vps_owner_manage);
        btnVpsRelayConnections = findViewById(R.id.btn_vps_relay_connections);
        btnVpsManualToggle = findViewById(R.id.btn_vps_manual_toggle);
        btnConnectionAdvancedToggle = findViewById(R.id.btn_connection_advanced_toggle);
        btnCfHelp = findViewById(R.id.btn_cf_help);
        btnWorkerHelp = findViewById(R.id.btn_worker_help);
        btnCheckUpdate = findViewById(R.id.btn_check_update);
        btnOpenRelease = findViewById(R.id.btn_open_release);
        btnInstallUpdate = findViewById(R.id.btn_install_update);
        btnCreateProfile = findViewById(R.id.btn_create_profile);
        btnSaveProfile = findViewById(R.id.btn_save_profile);
        btnDeleteProfile = findViewById(R.id.btn_delete_profile);
        btnExportSafeProfile = findViewById(R.id.btn_export_safe_profile);
        btnExportVpsRelay = findViewById(R.id.btn_export_vps_relay);
        btnExportEncryptedProfile = findViewById(R.id.btn_export_encrypted_profile);
        btnImportSettings = findViewById(R.id.btn_import_settings);
        btnScanQr = findViewById(R.id.btn_scan_qr);
        btnSettingsDiagnostics = findViewById(R.id.btn_settings_diagnostics);
        btnBackgroundSetup = findViewById(R.id.btn_background_setup);
        btnDiagnosticsSaveZip = findViewById(R.id.btn_diagnostics_save_zip);
        btnDiagnosticsSaveTxt = findViewById(R.id.btn_diagnostics_save_txt);
        btnDiagnosticsCopyShort = findViewById(R.id.btn_diagnostics_copy_short);
        btnDiagnosticsShare = findViewById(R.id.btn_diagnostics_share);
        btnDiagnosticsReset = findViewById(R.id.btn_diagnostics_reset);

        tvStatus = findViewById(R.id.tv_status);
        tvAddress = findViewById(R.id.tv_address);
        tvRoute = findViewById(R.id.tv_route);
        tvCfDomain = findViewById(R.id.tv_cf_domain);
        tvPort = findViewById(R.id.tv_port);
        tvTgLink = findViewById(R.id.tv_tg_link);
        tvPing = findViewById(R.id.tv_ping);
        tvTraffic = findViewById(R.id.tv_traffic);
        tvUptime = findViewById(R.id.tv_uptime);
        tvMainProfile = findViewById(R.id.tv_main_profile);
        tvQuality = findViewById(R.id.tv_quality);
        tvConnections = findViewById(R.id.tv_connections);
        tvUpdateStatus = findViewById(R.id.tv_update_status);
        tvUpdateProgress = findViewById(R.id.tv_update_progress);
        tvVersion = findViewById(R.id.tv_version);
        tvVpsSetupStatus = findViewById(R.id.tv_vps_setup_status);
        tvBackgroundStatus = findViewById(R.id.tv_background_status);
        tvGithub = findViewById(R.id.tv_github);
        tvActiveProfile = findViewById(R.id.tv_active_profile);
        tvProfileKey = findViewById(R.id.tv_profile_key);
        tvProfilesList = findViewById(R.id.tv_profiles_list);
        tvRoutePreferenceExplanation = findViewById(R.id.tv_route_preference_explanation);
        tvDiagnosticsNetwork = findViewById(R.id.tv_diagnostics_network);
        tvDiagnosticsProfile = findViewById(R.id.tv_diagnostics_profile);
        tvDiagnosticsRoute = findViewById(R.id.tv_diagnostics_route);
        tvDiagnosticsRouteChecks = findViewById(R.id.tv_diagnostics_route_checks);
        tvDiagnosticsHistory = findViewById(R.id.tv_diagnostics_history);
        tvDiagnosticsErrors = findViewById(R.id.tv_diagnostics_errors);
        tvDiagnosticsService = findViewById(R.id.tv_diagnostics_service);
        tvDiagnosticsReport = findViewById(R.id.tv_diagnostics_report);
        progressUpdate = findViewById(R.id.progress_update);
        progressVpsSetup = findViewById(R.id.progress_vps_setup);

        etCustomIp = findViewById(R.id.et_custom_ip);
        etCustomPort = findViewById(R.id.et_custom_port);
        etSecret = findViewById(R.id.et_secret);
        etDcRules = findViewById(R.id.et_dc_rules);
        etCfDomains = findViewById(R.id.et_cf_domains);
        etWorkerDomains = findViewById(R.id.et_worker_domains);
        etVpsRelayName = findViewById(R.id.et_vps_relay_name);
        etVpsRelayHost = findViewById(R.id.et_vps_relay_host);
        etVpsRelayPort = findViewById(R.id.et_vps_relay_port);
        etVpsRelayPath = findViewById(R.id.et_vps_relay_path);
        etVpsRelayToken = findViewById(R.id.et_vps_relay_token);
        etProfileName = findViewById(R.id.et_profile_name);

        cbSmartSleep = findViewById(R.id.cb_smart_sleep);
        cbAutostartOpen = findViewById(R.id.cb_autostart_open);
        cbAutostartBoot = findViewById(R.id.cb_autostart_boot);
        cbVpsRelayEnabled = findViewById(R.id.cb_vps_relay_enabled);
        cbVpsRelayTls = findViewById(R.id.cb_vps_relay_tls);
        cbVpsRelayBindProfile = findViewById(R.id.cb_vps_relay_bind_profile);
        cbCfCustomDomain = findViewById(R.id.cb_cf_custom_domain);
        cbCfWarmup = findViewById(R.id.cb_cf_warmup);
        cbCfRecheckNetwork = findViewById(R.id.cb_cf_recheck_network);
        cbVerbose = findViewById(R.id.cb_verbose);
        cbCheckUpdates = findViewById(R.id.cb_check_updates);
        cbRouteDirect = findViewById(R.id.cb_route_direct);
        cbRouteRelay = findViewById(R.id.cb_route_relay);
        cbRouteWorker = findViewById(R.id.cb_route_worker);
        cbRouteCustomCf = findViewById(R.id.cb_route_custom_cf);
        cbRoutePublicCf = findViewById(R.id.cb_route_public_cf);
        spCfMode = findViewById(R.id.sp_cf_mode);
        spTheme = findViewById(R.id.sp_theme);
        spLanguage = findViewById(R.id.sp_language);
        spProfileSelector = findViewById(R.id.sp_profile_selector);
        spRoutePreference = findViewById(R.id.sp_route_preference);
        spVpsRelaySaved = findViewById(R.id.sp_vps_relay_saved);

        navConnection = findViewById(R.id.nav_connection);
        navRoutes = findViewById(R.id.nav_routes);
        navRelay = findViewById(R.id.nav_relay);
        navSystem = findViewById(R.id.nav_system);
        navAbout = findViewById(R.id.nav_about);

        sectionConnection = findViewById(R.id.section_connection);
        sectionRoute = findViewById(R.id.section_route);
        sectionProfiles = findViewById(R.id.section_profiles);
        sectionOptimization = findViewById(R.id.section_optimization);
        sectionVpsRelay = findViewById(R.id.section_vps_relay);
        sectionImportExport = findViewById(R.id.section_import_export);
        sectionDiagnosticsLogs = findViewById(R.id.section_diagnostics_logs);
        sectionBehavior = findViewById(R.id.section_behavior);
        sectionInterface = findViewById(R.id.section_interface);
        sectionUpdates = findViewById(R.id.section_updates);
        sectionAdvanced = findViewById(R.id.section_advanced);
        sectionAbout = findViewById(R.id.section_about);
        vpsManualContent = findViewById(R.id.vps_manual_content);
        connectionAdvancedContent = findViewById(R.id.connection_advanced_content);
    }

    private void loadSettings() {
        etCustomIp.setText(prefs.getString("custom_ip", MtProtoConfig.DEFAULT_HOST));
        etCustomPort.setText(String.valueOf(prefs.getInt("custom_port", MtProtoConfig.DEFAULT_PORT)));
        etSecret.setText(prefs.getString("mtproto_secret", MtProtoConfig.generateSecretHex()));
        etDcRules.setText(prefs.getString("dc_rules", MtProtoConfig.DEFAULT_DC_RULES));
        String cfDomains = prefs.getString("cfproxy_domains", "");
        etCfDomains.setText(cfDomains);
        etWorkerDomains.setText(prefs.getString("worker_domains", ""));
        cbSmartSleep.setChecked(prefs.getBoolean("smart_sleep", TgRoutePolicy.DEFAULT_SMART_SLEEP));
        cbAutostartOpen.setChecked(prefs.getBoolean("autostart_open", false));
        cbAutostartBoot.setChecked(prefs.getBoolean("autostart_boot", true));
        setupCfModeControl();
        cbCfCustomDomain.setChecked(prefs.getBoolean("cfproxy_custom_enabled", !cfDomains.trim().isEmpty()));
        updateCfCustomDomainEnabled();
        cbCfWarmup.setChecked(prefs.getBoolean("cf_warmup", true));
        cbCfRecheckNetwork.setChecked(prefs.getBoolean("cf_recheck_network", true));
        cbVpsRelayEnabled.setChecked(prefs.getBoolean("vps_relay_enabled", false));
        etVpsRelayName.setText(prefs.getString("vps_relay_name", ""));
        etVpsRelayHost.setText(prefs.getString("vps_relay_host", ""));
        etVpsRelayPort.setText(String.valueOf(prefs.getInt("vps_relay_port", 443)));
        cbVpsRelayTls.setChecked(prefs.getBoolean("vps_relay_tls", true));
        etVpsRelayPath.setText(prefs.getString("vps_relay_path", "/apiws"));
        etVpsRelayToken.setText(prefs.getString("vps_relay_token", ""));
        cbVpsRelayBindProfile.setChecked(!prefs.getString("vps_relay_profile_key", "").isEmpty());
        updateVpsRelayFieldsEnabled();
        cbVerbose.setChecked(prefs.getBoolean("verbose_logging", false));
        cbCheckUpdates.setChecked(prefs.getBoolean("check_updates", true));
        tvVersion.setText("Version " + BuildConfig.VERSION_NAME + " " + getString(R.string.app_by));
        setupProfileControls();
        setupVpsRelayControls();
        setupAppearanceControls();
    }

    private void setupActions() {
        btnStart.setOnClickListener(v -> {
            if (ProxyService.getInstance() == null) startProxy();
            else stopProxy();
        });
        btnStop.setOnClickListener(v -> stopProxy());
        btnOpenSettings.setOnClickListener(v -> openSettingsOrWarn());
        btnBackMain.setOnClickListener(v -> closeSettingsSaving());
        btnBackDiagnostics.setOnClickListener(v -> hideDiagnosticsScreen());
        btnOpenDiagnostics.setOnClickListener(v -> showDiagnostics(false));
        btnOpenGithub.setOnClickListener(v -> openLink(REPO_URL));
        btnOpenTelegram.setOnClickListener(v -> openTelegramLink());
        btnMainMenu.setOnClickListener(this::showMainMenu);
        setupSettingsNavigation();
        btnRegenerateSecret.setOnClickListener(v -> {
            etSecret.setText(MtProtoConfig.generateSecretHex());
            saveSettings();
            refreshConnectionFields();
        });
        btnTestCf.setOnClickListener(v -> testCfProxy());
        btnTestWorker.setOnClickListener(v -> testWorker());
        btnRouteDirectOnly.setOnClickListener(v -> {
            profileControlReady = false;
            applyRouteAvailabilityToControls(RouteAvailability.directOnly());
            profileControlReady = true;
            saveDisplayedRouteAvailability();
        });
        btnVpsRelayTest.setOnClickListener(v -> testVpsRelay());
        btnVpsRelayNew.setOnClickListener(v -> {
            vpsRelaySelectorReady = false;
            if (spVpsRelaySaved != null && !vpsRelaySelectorIds.isEmpty()) {
                spVpsRelaySaved.setSelection(0);
            }
            clearVpsRelayForm();
            setExpandableSection(vpsManualContent, btnVpsManualToggle, true,
                    R.string.vps_manual_show, R.string.vps_manual_hide);
            vpsRelaySelectorReady = true;
            setEnabled(btnVpsRelayDelete, false);
        });
        btnVpsRelaySave.setOnClickListener(v -> {
            if (saveCurrentVpsRelayFromForm()) {
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            }
        });
        btnVpsRelayDelete.setOnClickListener(v -> confirmDeleteSelectedVpsRelay());
        btnVpsRelayAutoSetup.setOnClickListener(v -> openVpsSetup(false));
        btnVpsOwnerManage.setOnClickListener(v -> openVpsOwnerManager());
        btnVpsRelayConnections.setOnClickListener(v -> startActivityForResult(
                VpsRelayConnectionsActivity.intent(this, vpsRelayProfileKeyForUi()),
                REQUEST_VPS_CONNECTIONS));
        btnVpsManualToggle.setOnClickListener(v -> setExpandableSection(
                vpsManualContent, btnVpsManualToggle,
                vpsManualContent.getVisibility() != View.VISIBLE,
                R.string.vps_manual_show, R.string.vps_manual_hide));
        btnConnectionAdvancedToggle.setOnClickListener(v -> setExpandableSection(
                connectionAdvancedContent, btnConnectionAdvancedToggle,
                connectionAdvancedContent.getVisibility() != View.VISIBLE,
                R.string.connection_advanced_show, R.string.connection_advanced_hide));
        btnCfHelp.setOnClickListener(v -> openLink("https://github.com/Flowseal/tg-ws-proxy/blob/main/docs/CfProxy.md"));
        btnWorkerHelp.setOnClickListener(v -> openLink("https://github.com/Flowseal/tg-ws-proxy/blob/main/docs/CfWorker.md"));
        btnCheckUpdate.setOnClickListener(v -> checkUpdates(true));
        btnOpenRelease.setOnClickListener(v -> openLink(lastRelease != null ? lastRelease.htmlUrl : REPO_URL + "/releases"));
        btnInstallUpdate.setOnClickListener(v -> installLastRelease());
        btnCreateProfile.setOnClickListener(v -> createManualProfile());
        btnSaveProfile.setOnClickListener(v -> saveDisplayedProfile());
        btnDeleteProfile.setOnClickListener(v -> confirmDeleteDisplayedProfile());
        btnExportSafeProfile.setOnClickListener(v -> exportSafeProfile());
        btnExportVpsRelay.setOnClickListener(v -> exportVpsRelay());
        btnExportEncryptedProfile.setOnClickListener(v -> showEncryptedExportDialog());
        btnImportSettings.setOnClickListener(v -> showImportSettingsDialog());
        btnScanQr.setOnClickListener(v -> scanImportQr());
        btnSettingsDiagnostics.setOnClickListener(v -> showDiagnostics(true));
        btnBackgroundSetup.setOnClickListener(v -> showBackgroundSetupDialog());
        btnDiagnosticsSaveZip.setOnClickListener(v -> saveDiagnosticsZip(buildDiagnosticsReport()));
        btnDiagnosticsSaveTxt.setOnClickListener(v -> saveDiagnosticsReport(buildDiagnosticsReport()));
        btnDiagnosticsCopyShort.setOnClickListener(v -> {
            copy(buildShortDiagnosticsReport());
            DiagnosticsLog.record("diagnostics short report copied");
            Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show();
        });
        btnDiagnosticsShare.setOnClickListener(v -> shareDiagnosticsReport(buildDiagnosticsReport()));
        btnDiagnosticsReset.setOnClickListener(v -> resetDiagnostics());
        tvGithub.setOnClickListener(v -> openLink(REPO_URL));

        setupCopy(tvAddress);
        setupCopy(tvPort);
        setupTelegramLink();

        cbSmartSleep.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("smart_sleep", checked).apply());
        cbAutostartOpen.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("autostart_open", checked).apply());
        cbAutostartBoot.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("autostart_boot", checked).apply());
        cbCfCustomDomain.setOnCheckedChangeListener((v, checked) -> {
            updateCfCustomDomainEnabled();
            if (!checked) etCfDomains.setText("");
            prefs.edit().putBoolean("cfproxy_custom_enabled", checked).apply();
        });
        cbCfWarmup.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("cf_warmup", checked).apply());
        cbCfRecheckNetwork.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("cf_recheck_network", checked).apply());
        cbVpsRelayEnabled.setOnCheckedChangeListener((v, checked) -> {
            if (vpsRelayFormBinding) return;
            updateVpsRelayFieldsEnabled();
            refreshConnectionFields();
        });
        cbVpsRelayTls.setOnCheckedChangeListener((v, checked) -> {
            if (vpsRelayFormBinding) return;
            if (checked && "80".equals(etVpsRelayPort.getText().toString().trim())) {
                etVpsRelayPort.setText("443");
            } else if (!checked && "443".equals(etVpsRelayPort.getText().toString().trim())) {
                etVpsRelayPort.setText("80");
            }
        });
        cbVpsRelayBindProfile.setOnCheckedChangeListener((v, checked) -> {
            if (vpsRelayFormBinding) return;
            refreshVpsRelaySelector();
            refreshConnectionFields();
        });
        cbVerbose.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("verbose_logging", checked).apply());
        cbCheckUpdates.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("check_updates", checked).apply());
        android.widget.CompoundButton.OnCheckedChangeListener routeAvailabilityListener =
                (button, checked) -> onRouteAvailabilityChanged((CheckBox) button);
        cbRouteDirect.setOnCheckedChangeListener(routeAvailabilityListener);
        cbRouteRelay.setOnCheckedChangeListener(routeAvailabilityListener);
        cbRouteWorker.setOnCheckedChangeListener(routeAvailabilityListener);
        cbRouteCustomCf.setOnCheckedChangeListener(routeAvailabilityListener);
        cbRoutePublicCf.setOnCheckedChangeListener(routeAvailabilityListener);
    }

    private void setupAppearanceControls() {
        ArrayAdapter<CharSequence> themeAdapter = ArrayAdapter.createFromResource(
                this, R.array.theme_options, R.layout.spinner_item);
        themeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spTheme.setAdapter(themeAdapter);
        spTheme.setSelection(themeIndex(prefs.getString("theme_mode", "system")));
        spTheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (spinnerInit) return;
                String value = position == 1 ? "light" : position == 2 ? "dark" : "system";
                if (!value.equals(prefs.getString("theme_mode", "system"))) {
                    prefs.edit().putString("theme_mode", value).apply();
                    applyStoredTheme();
                    recreate();
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        ArrayAdapter<CharSequence> languageAdapter = ArrayAdapter.createFromResource(
                this, R.array.language_options, R.layout.spinner_item);
        languageAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spLanguage.setAdapter(languageAdapter);
        spLanguage.setSelection(languageIndex(prefs.getString("language_mode", "system")));
        spLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (spinnerInit) return;
                String value = position == 1 ? "ru" : position == 2 ? "en" : "system";
                if (!value.equals(prefs.getString("language_mode", "system"))) {
                    prefs.edit().putString("language_mode", value).apply();
                    applyStoredLanguage();
                    recreate();
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupCfModeControl() {
        ArrayAdapter<CharSequence> cfModeAdapter = ArrayAdapter.createFromResource(
                this, R.array.cf_mode_options, R.layout.spinner_item);
        cfModeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spCfMode.setAdapter(cfModeAdapter);
        spCfMode.setSelection(cfModeIndex(storedCfProxyMode()));
        spCfMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = position == 1
                        ? MtProtoProxyEngine.CF_MODE_ON
                        : position == 2 ? MtProtoProxyEngine.CF_MODE_OFF : MtProtoProxyEngine.CF_MODE_AUTO;
                if (!value.equals(storedCfProxyMode())) {
                    prefs.edit()
                            .putString("cfproxy_mode", value)
                            .putBoolean("cfproxy_enabled", !MtProtoProxyEngine.CF_MODE_OFF.equals(value))
                            .apply();
                    updateCfCustomDomainEnabled();
                    refreshConnectionFields();
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupProfileControls() {
        ArrayAdapter<CharSequence> profileAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, new ArrayList<>());
        profileAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spProfileSelector.setAdapter(profileAdapter);
        spProfileSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!profileSelectorReady || position < 0 || position >= profileSelectorKeys.size()) return;
                String selectedKey = profileSelectorKeys.get(position);
                if (selectedKey.equals(displayedProfileKey)) return;
                displayedProfileKey = selectedKey;
                NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
                NetworkProfileRecord record = store.profile(selectedKey);
                if (record != null) showProfileRecord(record);
                refreshProfilesList(store, activeProfileKey);
                refreshVpsRelaySelector();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.route_preference_options, R.layout.spinner_item);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spRoutePreference.setAdapter(adapter);
        spRoutePreference.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateRoutePreferenceExplanation(selectedRoutePreference());
                if (!profileControlReady) return;
                saveDisplayedRoutePreference();
                refreshConnectionFields();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        refreshProfileControls(true);
    }

    private void setupVpsRelayControls() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spVpsRelaySaved.setAdapter(adapter);
        spVpsRelaySaved.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!vpsRelaySelectorReady || position < 0 || position >= vpsRelaySelectorIds.size()) return;
                String relayId = vpsRelaySelectorIds.get(position);
                VpsRelayStore store = VpsRelayStore.fromContext(MainActivity.this);
                String profileKey = cbVpsRelayBindProfile != null && cbVpsRelayBindProfile.isChecked()
                        ? vpsRelayProfileKeyForUi() : "";
                String previouslySelected = store.selectedRelayId(profileKey);
                if (previouslySelected == null) previouslySelected = "";
                if (relayId.isEmpty()) {
                    // Adapter refresh may briefly report its zero item before setSelection()
                    // restores the actual Relay. Never mutate a binding from that callback.
                    clearVpsRelayForm();
                    setExpandableSection(vpsManualContent, btnVpsManualToggle, false,
                            R.string.vps_manual_show, R.string.vps_manual_hide);
                    setEnabled(btnVpsRelayDelete, false);
                    return;
                }
                VpsRelayStore.Record record = store.relay(relayId);
                if (record == null) return;
                if (relayId.equals(previouslySelected)) {
                    // refreshVpsRelaySelector() already synchronized the form. A delayed callback
                    // for the same selection must be a complete no-op: otherwise it rewrites a
                    // form while the owner is typing and emits a Toast every refresh interval.
                    return;
                }
                if (!store.bindProfile(profileKey, relayId)) {
                    DiagnosticsLog.record("VPS Relay profile binding commit failed");
                    Toast.makeText(MainActivity.this, R.string.settings_save_failed,
                            Toast.LENGTH_LONG).show();
                    refreshVpsRelaySelector();
                    return;
                }
                fillVpsRelayForm(record.config().withProfileKey(profileKey));
                refreshConnectionFields();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        if (!prefs.getBoolean(KEY_VPS_RELAY_LEGACY_MIGRATED, false)) {
            VpsRelayConfig legacyRelay = currentVpsRelayConfig();
            boolean imported = VpsRelayStore.fromContext(this)
                    .importLegacyIfNeeded(legacyRelay, legacyRelay.profileKey());
            if (imported && !prefs.edit()
                    .putBoolean(KEY_VPS_RELAY_LEGACY_MIGRATED, true).commit()) {
                DiagnosticsLog.record("VPS Relay migration marker commit failed");
            }
        }
        refreshVpsRelaySelector();
        VpsRelayConfig securedRelay = activeVpsRelayConfig();
        if (securedRelay != null && securedRelay.isUsable()) {
            prefs.edit().remove("vps_relay_token").commit();
        }
        applyVpsSetupProgress(VpsSetupProgress.of(
                VpsSetupProgress.Stage.AUDIT, 0, getString(R.string.vps_setup_idle)));
    }

    private void refreshProfileControls(boolean force) {
        if (prefs == null || tvActiveProfile == null || spProfileSelector == null) return;
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        NetworkProfileRecord active = store.profileOrCreate(
                NetworkProfileIdentifier.current(this), System.currentTimeMillis());
        String nextActiveKey = active.key();
        boolean activeChanged = !nextActiveKey.equals(activeProfileKey);
        activeProfileKey = nextActiveKey;
        if (force || activeChanged || displayedProfileKey.isEmpty()
                || store.profile(displayedProfileKey) == null) {
            displayedProfileKey = activeProfileKey;
            showProfileRecord(active);
        }
        refreshProfileSelector(store, activeProfileKey, displayedProfileKey);
        refreshProfilesList(store, activeProfileKey);
        if (force || activeChanged) refreshVpsRelaySelector();
    }

    private void refreshProfileSelector(NetworkProfileStore store, String activeKey, String selectedKey) {
        profileSelectorReady = false;
        profileSelectorKeys.clear();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);

        addProfileOption(adapter, activeKey, store.profile(activeKey), true);
        for (NetworkProfileRecord record : store.profilesSnapshot().values()) {
            if (record.key().equals(activeKey)) continue;
            addProfileOption(adapter, record.key(), record, false);
        }

        spProfileSelector.setAdapter(adapter);
        int selectedIndex = Math.max(0, profileSelectorKeys.indexOf(selectedKey));
        spProfileSelector.setSelection(selectedIndex);
        profileSelectorReady = true;
    }

    private void addProfileOption(ArrayAdapter<String> adapter, String key,
                                  NetworkProfileRecord record, boolean active) {
        if (record == null || key == null || key.trim().isEmpty()) return;
        profileSelectorKeys.add(key);
        String label = record.displayName();
        if (active) label += " • " + getString(R.string.profile_active);
        adapter.add(label);
    }

    private void showProfileRecord(NetworkProfileRecord record) {
        if (record == null) return;
        profileControlReady = false;
        tvActiveProfile.setText(record.key().equals(activeProfileKey)
                ? record.displayName()
                : activeProfileLabel());
        tvProfileKey.setText(record.key());
        etProfileName.setText(record.displayName());
        spRoutePreference.setSelection(routePreferenceIndex(record.routePreference()));
        applyRouteAvailabilityToControls(record.routeAvailability());
        profileControlReady = true;
    }

    private String activeProfileLabel() {
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        NetworkProfileRecord active = store.profile(activeProfileKey);
        return active == null ? "-" : active.displayName();
    }

    private String currentMainProfileLabel(NetworkProfile profile) {
        if (prefs == null) return "-";
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        NetworkProfileRecord record = null;
        if (profile != null) {
            record = store.profileOrCreate(profile, System.currentTimeMillis());
            activeProfileKey = record.key();
        } else if (!activeProfileKey.isEmpty()) {
            record = store.profile(activeProfileKey);
        }
        if (record == null) {
            record = store.profileOrCreate(NetworkProfileIdentifier.current(this), System.currentTimeMillis());
            activeProfileKey = record.key();
        }
        return record.displayName();
    }

    private void refreshProfilesList(NetworkProfileStore store, String activeKey) {
        if (tvProfilesList == null) return;
        StringBuilder out = new StringBuilder();
        for (NetworkProfileRecord record : store.profilesSnapshot().values()) {
            if (out.length() > 0) out.append('\n');
            out.append(record.key().equals(activeKey) ? "• " : "  ")
                    .append(record.displayName())
                    .append(" — ")
                    .append(routePreferenceLabel(record.routePreference()));
        }
        tvProfilesList.setText(out.length() == 0
                ? getString(R.string.profile_list_empty)
                : out.toString());
    }

    private String routePreferenceLabel(RoutePreference preference) {
        String[] labels = getResources().getStringArray(R.array.route_preference_options);
        int index = routePreferenceIndex(preference);
        return labels[Math.max(0, Math.min(index, labels.length - 1))];
    }

    private int themeIndex(String value) {
        if ("light".equals(value)) return 1;
        if ("dark".equals(value)) return 2;
        return 0;
    }

    private int languageIndex(String value) {
        if ("ru".equals(value)) return 1;
        if ("en".equals(value)) return 2;
        return 0;
    }

    private int cfModeIndex(String mode) {
        if (MtProtoProxyEngine.CF_MODE_ON.equals(mode)) return 1;
        if (MtProtoProxyEngine.CF_MODE_OFF.equals(mode)) return 2;
        return 0;
    }

    private int routePreferenceIndex(RoutePreference preference) {
        if (preference == RoutePreference.DIRECT_FIRST) return 1;
        if (preference == RoutePreference.CLOUDFLARE_FIRST) return 2;
        if (preference == RoutePreference.RELAY_FIRST) return 3;
        return 0;
    }

    private RoutePreference selectedRoutePreference() {
        if (spRoutePreference == null) return RoutePreference.AUTO;
        int position = spRoutePreference.getSelectedItemPosition();
        if (position == 1) return RoutePreference.DIRECT_FIRST;
        if (position == 2) return RoutePreference.CLOUDFLARE_FIRST;
        if (position == 3) return RoutePreference.RELAY_FIRST;
        return RoutePreference.AUTO;
    }

    private String storedCfProxyMode() {
        String fallback = prefs.getBoolean("cfproxy_enabled", true)
                ? MtProtoProxyEngine.CF_MODE_AUTO
                : MtProtoProxyEngine.CF_MODE_OFF;
        return MtProtoProxyEngine.normalizeCfProxyMode(
                prefs.getString("cfproxy_mode", fallback));
    }

    private String selectedCfProxyMode() {
        if (spCfMode == null) return storedCfProxyMode();
        int position = spCfMode.getSelectedItemPosition();
        if (position == 1) return MtProtoProxyEngine.CF_MODE_ON;
        if (position == 2) return MtProtoProxyEngine.CF_MODE_OFF;
        return MtProtoProxyEngine.CF_MODE_AUTO;
    }

    private void updateCfCustomDomainEnabled() {
        if (etCfDomains == null || cbCfCustomDomain == null) return;
        boolean cfAvailable = !MtProtoProxyEngine.CF_MODE_OFF.equals(selectedCfProxyMode());
        etCfDomains.setEnabled(cfAvailable && cbCfCustomDomain.isChecked());
        cbCfCustomDomain.setEnabled(cfAvailable);
    }

    private CharSequence coloredTrafficSummary(String up, String down) {
        return coloredTrafficText(MainUiState.trafficSummary(up, down));
    }

    private CharSequence coloredTrafficText(String text) {
        SpannableString span = new SpannableString(text);
        int down = text.indexOf('↓');
        if (down > 0) {
            span.setSpan(new ForegroundColorSpan(getColorValue(R.color.green)),
                    0, down, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(getColorValue(R.color.red)),
                    down, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    private void openSettingsOrWarn() {
        showSettingsScreen(true);
    }

    private void updateRoutePreferenceExplanation(RoutePreference preference) {
        if (tvRoutePreferenceExplanation == null) return;
        int message;
        if (preference == RoutePreference.DIRECT_FIRST) {
            message = R.string.route_priority_direct_explanation;
        } else if (preference == RoutePreference.CLOUDFLARE_FIRST) {
            message = R.string.route_priority_cloudflare_explanation;
        } else if (preference == RoutePreference.RELAY_FIRST) {
            message = R.string.route_priority_relay_explanation;
        } else {
            message = R.string.route_priority_auto_explanation;
        }
        tvRoutePreferenceExplanation.setText(message);
    }

    private void showSettingsScreen(boolean show) {
        if (show) {
            setExpandableSection(vpsManualContent, btnVpsManualToggle, false,
                    R.string.vps_manual_show, R.string.vps_manual_hide);
            refreshProfileControls(true);
            showSettingsSection(currentSettingsSection);
            updateSettingsEditability();
            if (isProxyRunning()) {
                Toast.makeText(this, R.string.settings_live_note, Toast.LENGTH_LONG).show();
            }
        }
        if (diagnosticsScreen != null) diagnosticsScreen.setVisibility(View.GONE);
        mainScreen.setVisibility(show ? View.GONE : View.VISIBLE);
        settingsScreen.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void closeSettingsSaving() {
        if (saveSettings()) showSettingsScreen(false);
    }

    private void updateSettingsEditability() {
        boolean listenerEditable = !isProxyRunning();
        setEnabled(etCustomIp, listenerEditable);
        setEnabled(etCustomPort, listenerEditable);
    }

    private void setupSettingsNavigation() {
        navConnection.setOnClickListener(v -> showSettingsSection(MainUiState.SettingsSection.CONNECTION));
        navRoutes.setOnClickListener(v -> showSettingsSection(MainUiState.SettingsSection.ROUTES));
        navRelay.setOnClickListener(v -> showSettingsSection(MainUiState.SettingsSection.RELAY));
        navSystem.setOnClickListener(v -> showSettingsSection(MainUiState.SettingsSection.SYSTEM));
        navAbout.setOnClickListener(v -> showSettingsSection(MainUiState.SettingsSection.ABOUT));
        showSettingsSection(currentSettingsSection);
    }

    private void showSettingsSection(MainUiState.SettingsSection section) {
        currentSettingsSection = section == null ? MainUiState.SettingsSection.CONNECTION : section;

        boolean connection = currentSettingsSection == MainUiState.SettingsSection.CONNECTION;
        boolean routes = currentSettingsSection == MainUiState.SettingsSection.ROUTES;
        boolean relay = currentSettingsSection == MainUiState.SettingsSection.RELAY;
        boolean system = currentSettingsSection == MainUiState.SettingsSection.SYSTEM;
        boolean about = currentSettingsSection == MainUiState.SettingsSection.ABOUT;

        setVisible(sectionConnection, connection);
        setVisible(sectionProfiles, connection);
        setVisible(sectionRoute, routes);
        setVisible(sectionOptimization, routes);
        setVisible(sectionVpsRelay, relay);
        setVisible(sectionImportExport, relay);
        setVisible(sectionDiagnosticsLogs, system);
        setVisible(sectionBehavior, system);
        setVisible(sectionAdvanced, system);
        setVisible(sectionInterface, system);
        setVisible(sectionUpdates, system);
        setVisible(sectionAbout, about);

        setNavState(navConnection, connection);
        setNavState(navRoutes, routes);
        setNavState(navRelay, relay);
        setNavState(navSystem, system);
        setNavState(navAbout, about);
        centerSelectedSettingsTab(connection ? navConnection
                : routes ? navRoutes
                : relay ? navRelay
                : system ? navSystem : navAbout);
    }

    private void centerSelectedSettingsTab(View selected) {
        View scroll = findViewById(R.id.settings_nav_scroll);
        if (!(scroll instanceof android.widget.HorizontalScrollView) || selected == null) return;
        android.widget.HorizontalScrollView navScroll = (android.widget.HorizontalScrollView) scroll;
        navScroll.post(() -> {
            int target = selected.getLeft() + selected.getWidth() / 2 - navScroll.getWidth() / 2;
            int max = Math.max(0, navScroll.getChildAt(0).getWidth() - navScroll.getWidth());
            navScroll.smoothScrollTo(Math.max(0, Math.min(max, target)), 0);
        });
    }

    private void setVisible(View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setNavState(View view, boolean selected) {
        if (view == null) return;
        view.setSelected(selected);
        view.setAlpha(selected ? 1f : 0.62f);
    }

    private boolean isProxyRunning() {
        return ProxyService.getInstance() != null;
    }

    private void startProxy() {
        if (!saveSettings()) return;
        if (ProxyServiceLauncher.startByUser(this)) {
            handler.postDelayed(() -> updateRunningState(true), 500);
            handler.postDelayed(this::showBackgroundSetupOnce, 700);
        }
    }

    private void stopProxy() {
        if (ProxyServiceLauncher.stopByUser(this)) {
            updateRunningState(false);
        } else {
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    private boolean saveSettings() {
        return saveSettingsWithRelay(null);
    }

    private boolean saveSettingsWithRelay(VpsRelayConfig relayOverride) {
        try {
            int port = Integer.parseInt(etCustomPort.getText().toString().trim());
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            String secret = etSecret.getText().toString().trim().toLowerCase(Locale.US);
            if (secret.startsWith("dd") && secret.length() == 34) secret = secret.substring(2);
            if (!secret.matches("[0-9a-f]{32}")) throw new IllegalArgumentException();
            String dcRules = MtProtoConfig.formatDcRules(
                    MtProtoConfig.parseUserDcRules(etDcRules.getText().toString()));
            VpsRelayConfig relay = relayOverride == null
                    ? preserveSavedVpsRelayCapabilities(currentVpsRelayConfig())
                    : relayOverride;
            if (relay.isEnabled() && !relay.isUsable()) throw new IllegalArgumentException();
            SharedPreferences.Editor editor = prefs.edit()
                    .putString("custom_ip", valueOrDefault(etCustomIp, MtProtoConfig.DEFAULT_HOST))
                    .putInt("custom_port", port)
                    .putString("mtproto_secret", secret)
                    .putString("dc_rules", dcRules)
                    .putString("cfproxy_mode", selectedCfProxyMode())
                    .putBoolean("cfproxy_enabled", !MtProtoProxyEngine.CF_MODE_OFF.equals(selectedCfProxyMode()))
                    .putBoolean("cfproxy_custom_enabled", cbCfCustomDomain.isChecked())
                    .putString("cfproxy_domains", cbCfCustomDomain.isChecked()
                            ? etCfDomains.getText().toString().trim() : "")
                    .putString("worker_domains", etWorkerDomains.getText().toString().trim())
                    .putBoolean("verbose_logging", cbVerbose.isChecked())
                    .putBoolean("cf_warmup", cbCfWarmup.isChecked())
                    .putBoolean("cf_recheck_network", cbCfRecheckNetwork.isChecked());
            // Profile name and route preference are part of the same logical settings save.
            // Stage the serialized profile registry in memory, then commit it together with
            // transport/Relay settings so an I/O failure cannot leave a half-imported profile.
            NetworkProfileStore stagedProfiles = NetworkProfileStore.inMemory(
                    prefs.getString(NetworkProfileStore.KEY_PROFILES, ""));
            String stagedProfileKey = selectedProfileKey(stagedProfiles);
            if (etProfileName != null) {
                stagedProfiles.renameProfile(stagedProfileKey,
                        etProfileName.getText().toString());
            }
            if (spRoutePreference != null) {
                stagedProfiles.setRoutePreference(stagedProfileKey, selectedRoutePreference());
            }
            stagedProfiles.setRouteAvailability(stagedProfileKey, routeAvailabilityFromControls());
            editor.putString(NetworkProfileStore.KEY_PROFILES, stagedProfiles.exportProfiles());
            if (!updateStoredVpsRelaySelection(editor, relay)) {
                throw new IllegalStateException("secure Relay staging failed");
            }
            putVpsRelaySettings(editor, relay);
            if (!editor.commit()) throw new IllegalStateException("settings commit failed");
            NetworkProfileStore persistedProfiles = NetworkProfileStore.fromPreferences(prefs);
            refreshProfileSelector(persistedProfiles, activeProfileKey, stagedProfileKey);
            refreshProfilesList(persistedProfiles, activeProfileKey);
            refreshConnectionFields();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void refreshConnectionFields() {
        ProxyService svc = ProxyService.getInstance();
        if (svc != null) {
            DiagnosticsSnapshot snapshot = svc.diagnosticsSnapshot();
            refreshConnectionFields(snapshot.serviceState(), snapshot.networkProfile());
            return;
        }
        refreshConnectionFields(ServiceState.stopped(), null);
    }

    private void refreshConnectionFields(ServiceState serviceState, NetworkProfile networkProfile) {
        String ip = valueOrDefault(etCustomIp, MtProtoConfig.DEFAULT_HOST);
        int port = intOrDefault(etCustomPort, MtProtoConfig.DEFAULT_PORT);
        String secret = MtProtoConfig.normalizeSecretHex(etSecret.getText().toString());
        tvAddress.setText(ip + ":" + port);
        tvRoute.setText(routeLabel(serviceState));
        tvCfDomain.setText(serviceState != null
                && serviceState.status() == ServiceState.Status.ACTIVE
                ? cfDomainLabel(serviceState.routeState()) : "-");
        tvPort.setText(String.valueOf(port));
        tvMainProfile.setText(currentMainProfileLabel(networkProfile));
        tvTgLink.setText(MtProtoConfig.telegramProxyLink(ip, port, secret));
    }

    private String cfDomainLabel(RouteState route) {
        if (MtProtoProxyEngine.CF_MODE_OFF.equals(selectedCfProxyMode())) {
            return getString(R.string.route_off);
        }
        if (route != null && (route.type() == RouteType.PUBLIC_CLOUDFLARE
                || route.type() == RouteType.CUSTOM_CLOUDFLARE)) {
            return route.activeEndpoint().isEmpty()
                    ? getString(R.string.route_searching) : route.activeEndpoint();
        }
        return "-";
    }

    private void updateRunningState(boolean running) {
        updateRunningState(currentServiceState(running));
    }

    private void updateRunningState(ServiceState state) {
        updateRunningState(state, true);
    }

    private void updateRunningState(ServiceState state, boolean refreshConnectionState) {
        boolean serviceStarted = state.serviceStarted();
        btnStart.setEnabled(true);
        btnStart.setContentDescription(getString(serviceStarted ? R.string.stop : R.string.start));
        btnStart.setBackgroundResource(serviceStarted ? R.drawable.power_button_active : R.drawable.power_button_idle);
        btnStop.setEnabled(serviceStarted);
        btnOpenSettings.setAlpha(1f);
        updateSettingsEditability();
        if (serviceStarted) {
            if (state.status() == ServiceState.Status.ACTIVE) {
                tvStatus.setText(R.string.status_active);
                tvStatus.setTextColor(getColorValue(R.color.green));
            } else if (state.status() == ServiceState.Status.READY_FOR_TELEGRAM) {
                tvStatus.setText(R.string.status_ready_for_telegram);
                tvStatus.setTextColor(getColorValue(R.color.accent));
            } else if (state.status() == ServiceState.Status.CONNECTING_TELEGRAM) {
                tvStatus.setText(R.string.status_connecting_telegram);
                tvStatus.setTextColor(getColorValue(R.color.warning));
            } else if (state.status() == ServiceState.Status.SLEEP) {
                tvStatus.setText(R.string.status_sleep);
                tvStatus.setTextColor(getColorValue(R.color.warning));
            } else if (state.status() == ServiceState.Status.DEGRADED) {
                tvStatus.setText(R.string.status_degraded);
                tvStatus.setTextColor(getColorValue(R.color.warning));
            } else if (state.status() == ServiceState.Status.RETRYING) {
                tvStatus.setText(R.string.status_retrying);
                tvStatus.setTextColor(getColorValue(R.color.warning));
            } else if (state.status() == ServiceState.Status.DEAD) {
                tvStatus.setText(R.string.status_dead);
                tvStatus.setTextColor(getColorValue(R.color.red));
            } else {
                tvStatus.setText(R.string.status_starting);
                tvStatus.setTextColor(getColorValue(R.color.warning));
            }
        } else {
            tvStatus.setText(R.string.status_stopped);
            tvStatus.setTextColor(getColorValue(R.color.text_secondary));
            tvPing.setText(MainUiState.pingSummary(-1));
            tvPing.setTextColor(getColorValue(R.color.text_secondary));
            tvQuality.setText(R.string.route_quality_unknown);
            tvQuality.setTextColor(getColorValue(R.color.text_secondary));
            tvConnections.setText(MainUiState.connectionSummary(-1));
            tvTraffic.setText(coloredTrafficText(MainUiState.emptyTrafficSummary()));
            tvUptime.setText("-");
            autoPingGate.reset();
            lastMeasuredPingIdentity = "";
            lastMeasuredPingMs = -1;
            lastMeasuredPingAt = 0L;
            bootstrapPingPlan = BootstrapPingPlanner.Plan.empty();
            bootstrapPingPlanBuiltAt = 0L;
        }
        if (refreshConnectionState) refreshConnectionFields();
        refreshBackgroundExecutionStatus();
    }

    private ServiceState currentServiceState(boolean runningHint) {
        ProxyService svc = ProxyService.getInstance();
        if (svc != null) return svc.diagnosticsSnapshot().serviceState();
        if (runningHint) {
            return ServiceState.from(true, false, false, false,
                    RouteState.inactive("service is starting"));
        }
        return ServiceState.stopped();
    }

    private String routeLabel(RouteState state) {
        return state == null || !state.active()
                ? getString(R.string.route_searching)
                : routeLabel(state.candidate());
    }

    private String routeLabel(ServiceState state) {
        if (state == null || state.status() == ServiceState.Status.STOPPED
                || state.status() == ServiceState.Status.SLEEP
                || state.status() == ServiceState.Status.DEAD
                || state.status() == ServiceState.Status.DEGRADED) {
            return getString(R.string.route_not_selected);
        }
        if (state.status() == ServiceState.Status.READY_FOR_TELEGRAM) {
            return getString(R.string.route_waiting_for_telegram);
        }
        if (state.status() == ServiceState.Status.CONNECTING_TELEGRAM
                || state.status() == ServiceState.Status.STARTING
                || state.status() == ServiceState.Status.RETRYING) {
            return getString(R.string.route_selecting);
        }
        return routeLabel(state.routeState());
    }

    private String routeLabel(RouteCandidate route) {
        if (route == null) return getString(R.string.route_searching);
        if (route.type() == RouteType.WORKER) return getString(R.string.route_cloudflare_worker);
        if (route.type() == RouteType.PUBLIC_CLOUDFLARE
                || route.type() == RouteType.CUSTOM_CLOUDFLARE) {
            return getString(R.string.route_cloudflare_cdn);
        }
        if (route.type() == RouteType.DIRECT_WS) return getString(R.string.route_direct);
        if (route.type() == RouteType.VPS_RELAY) return route.displayName();
        return route.displayName();
    }

    private void updateStats() {
        ProxyService svc = ProxyService.getInstance();
        if (svc == null) {
            updateRunningState(false);
            return;
        }
        DiagnosticsSnapshot snapshot = svc.diagnosticsSnapshot();
        updateRunningState(snapshot.serviceState(), false);
        MtProtoProxyEngine engine = svc.getEngine();
        RouteState routeState = snapshot.serviceState().routeState();
        refreshConnectionFields(snapshot.serviceState(), snapshot.networkProfile());
        updateRouteQuality(snapshot.serviceState());
        updateDisplayedPing(snapshot.serviceState());
        tvConnections.setText(MainUiState.connectionSummary(
                engine == null ? 0 : engine.activeConnectionCount()));
        tvTraffic.setText(coloredTrafficSummary(
                TgConstants.humanBytes(engine == null ? 0L : engine.bytesUp.get()),
                TgConstants.humanBytes(engine == null ? 0L : engine.bytesDown.get())));
        tvUptime.setText(MainUiState.uptimeSummary(svc.getUptime()));
        scheduleAutoPing(routeState);
        refreshDiagnosticsScreen(snapshot);
    }

    private void updateDisplayedPing(ServiceState serviceState) {
        long nowMs = System.currentTimeMillis();
        RouteState routeState = serviceState == null ? null : serviceState.routeState();
        int ping = -1;
        if (serviceState != null && serviceState.status() == ServiceState.Status.ACTIVE
                && routeState != null && routeState.active()) {
            ping = MainUiState.displayedPing(routeState, lastMeasuredPingIdentity,
                    lastMeasuredPingMs, lastMeasuredPingAt, nowMs);
        }
        tvPing.setText(ping == MainUiState.PING_ERROR_MS
                ? getString(R.string.route_ping_no_response)
                : MainUiState.pingSummary(ping));
        tvPing.setTextColor(pingColor(ping));
    }

    private void scheduleAutoPing(RouteState routeState) {
        List<RoutePingTarget> targets;
        String routeIdentity;
        if (routeState != null && routeState.active()) {
            targets = ActiveRoutePingPlanner.targetsFor(
                    routeState, activeVpsRelayConfig(), currentDcRulesOrDefault());
            routeIdentity = MainUiState.routeIdentity(routeState);
        } else {
            ProxyService service = ProxyService.getInstance();
            ServiceState serviceState = service == null
                    ? ServiceState.stopped() : service.diagnosticsSnapshot().serviceState();
            if (!serviceState.serviceStarted() || !serviceState.localPortListening()) return;
            BootstrapPingPlanner.Plan plan = currentBootstrapPingPlan();
            if (plan.isEmpty()) return;
            targets = plan.targets();
            routeIdentity = plan.identity();
        }
        if (targets.isEmpty()) return;
        long attemptToken = autoPingGate.tryStart(routeIdentity, System.currentTimeMillis());
        if (attemptToken == 0L) return;
        new Thread(() -> {
            Integer ms = RouteProbeClient.measureFirst(targets, 5000);
            if (ms != null) {
                postPingResult(routeIdentity, attemptToken, ms);
            } else {
                handler.post(() -> finishPingFailure(routeIdentity, attemptToken));
            }
        }, "tg-auto-ping").start();
    }

    private void postPingResult(String routeIdentity, long attemptToken, int ms) {
        handler.post(() -> {
            if (!autoPingGate.finish(attemptToken)) return;
            RouteState currentRoute = currentRouteForPingIdentity(routeIdentity);
            boolean bootstrap = isCurrentBootstrapPingIdentity(routeIdentity);
            if (currentRoute == null && !bootstrap) {
                DiagnosticsLog.record("stale auto ping result discarded " + routeIdentity);
                return;
            }
            lastMeasuredPingIdentity = routeIdentity == null ? "" : routeIdentity;
            lastMeasuredPingMs = ms;
            lastMeasuredPingAt = System.currentTimeMillis();
            DiagnosticsLog.record("auto ping " + lastMeasuredPingIdentity + " " + ms + "ms");
            updateDisplayedPing(currentServiceState(false));
        });
    }

    private void finishPingFailure(String routeIdentity, long attemptToken) {
        if (!autoPingGate.finish(attemptToken)) return;
        RouteState currentRoute = currentRouteForPingIdentity(routeIdentity);
        boolean bootstrap = isCurrentBootstrapPingIdentity(routeIdentity);
        if (currentRoute == null && !bootstrap) {
            DiagnosticsLog.record("stale auto ping failure discarded " + routeIdentity);
            return;
        }
        lastMeasuredPingIdentity = routeIdentity == null ? "" : routeIdentity;
        lastMeasuredPingMs = MainUiState.PING_ERROR_MS;
        lastMeasuredPingAt = System.currentTimeMillis();
        DiagnosticsLog.record("supplementary MTProto probe failed " + routeIdentity);
        updateDisplayedPing(currentServiceState(false));
    }

    private BootstrapPingPlanner.Plan currentBootstrapPingPlan() {
        long nowMs = System.currentTimeMillis();
        if (bootstrapPingPlan.isEmpty() || nowMs - bootstrapPingPlanBuiltAt >= 5_000L) {
            bootstrapPingPlan = BootstrapPingPlanner.plan(
                    routeSettingsFromControls(), firstDcId(), currentDcRulesOrDefault());
            bootstrapPingPlanBuiltAt = nowMs;
        }
        return bootstrapPingPlan;
    }

    private boolean isCurrentBootstrapPingIdentity(String expectedIdentity) {
        if (expectedIdentity == null || !expectedIdentity.startsWith("bootstrap|")) return false;
        ProxyService service = ProxyService.getInstance();
        if (service == null) return false;
        ServiceState state = service.diagnosticsSnapshot().serviceState();
        if (!state.serviceStarted() || !state.localPortListening()
                || (state.routeState() != null && state.routeState().active())) return false;
        return expectedIdentity.equals(currentBootstrapPingPlan().identity());
    }

    private RouteState currentServiceRouteState() {
        ProxyService service = ProxyService.getInstance();
        return service == null ? RouteState.inactive("service stopped")
                : service.diagnosticsSnapshot().serviceState().routeState();
    }

    private RouteState currentRouteForPingIdentity(String expectedIdentity) {
        ProxyService svc = ProxyService.getInstance();
        if (svc == null) return null;
        ServiceState state = svc.diagnosticsSnapshot().serviceState();
        RouteState route = state.routeState();
        if (state.status() != ServiceState.Status.ACTIVE || route == null || !route.active()) {
            return null;
        }
        return MainUiState.routeIdentity(route).equals(expectedIdentity) ? route : null;
    }

    private int pingColor(int ms) {
        if (ms == MainUiState.PING_ERROR_MS) return getColorValue(R.color.warning);
        if (ms < 0) return getColorValue(R.color.text_secondary);
        return ms < 100 ? 0xFF4CAF50 : ms < 300 ? 0xFFFFAB00 : 0xFFF44336;
    }

    private void updateRouteQuality(ServiceState serviceState) {
        RouteState routeState = serviceState == null ? null : serviceState.routeState();
        if (serviceState == null || serviceState.status() != ServiceState.Status.ACTIVE
                || routeState == null || !routeState.active()) {
            tvQuality.setText(R.string.route_quality_unknown);
            tvQuality.setTextColor(getColorValue(R.color.text_secondary));
            return;
        }
        String quality = routeState.quality();
        if (quality == null || quality.trim().isEmpty()) {
            tvQuality.setText(R.string.route_quality_unknown);
            tvQuality.setTextColor(getColorValue(R.color.text_secondary));
        } else if ("stable".equalsIgnoreCase(quality) || "NONE".equalsIgnoreCase(quality)) {
            tvQuality.setText(R.string.route_quality_stable);
            tvQuality.setTextColor(getColorValue(R.color.green));
        } else {
            tvQuality.setText(quality);
            tvQuality.setTextColor(getColorValue(R.color.warning));
        }
    }

    private void testDomain(String domain) {
        if (domain.isEmpty()) {
            Toast.makeText(this, R.string.test_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.checking, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(domain, 443), 7000);
                ok = true;
            } catch (Exception ignored) {
            }
            boolean result = ok;
            handler.post(() -> Toast.makeText(this,
                    result ? R.string.test_ok : R.string.test_failed,
                    Toast.LENGTH_SHORT).show());
        }, "tg-domain-test").start();
    }

    private void testCfProxy() {
        btnTestCf.setEnabled(false);
        btnTestCf.setText("...");
        List<String> domains = splitDomains(etCfDomains.getText().toString());
        if (cbCfCustomDomain.isChecked() && domains.isEmpty()) {
            btnTestCf.setEnabled(true);
            btnTestCf.setText(R.string.test);
            Toast.makeText(this, R.string.cf_custom_domain_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (cbCfCustomDomain.isChecked()) {
            FlowsealConnectivity.testCfProxyDomains(domains, results ->
                    handler.post(() -> {
                        btnTestCf.setEnabled(true);
                        btnTestCf.setText(R.string.test);
                        showMultiConnectivityResults(R.string.cf_proxy, results, "kws");
                    }));
        } else {
            FlowsealConnectivity.testCfProxyAuto(FlowsealCfDomains.defaults(), result ->
                    handler.post(() -> {
                        btnTestCf.setEnabled(true);
                        btnTestCf.setText(R.string.test);
                        showAutoCfProxyResult(result);
                    }));
        }
    }

    private void testWorker() {
        List<String> domains = splitDomains(etWorkerDomains.getText().toString());
        if (domains.isEmpty()) {
            Toast.makeText(this, R.string.cf_worker_domain_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        btnTestWorker.setEnabled(false);
        btnTestWorker.setText("...");
        FlowsealConnectivity.testWorkerDomains(domains, results ->
                handler.post(() -> {
                    btnTestWorker.setEnabled(true);
                    btnTestWorker.setText(R.string.test);
                    showMultiConnectivityResults(R.string.cf_worker, results, "DC");
                }));
    }

    private void testVpsRelay() {
        VpsRelayConfig relay = currentVpsRelayConfig();
        if (!relay.isUsable()) {
            Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show();
            return;
        }
        if (!vpsRelayTestRunning.compareAndSet(false, true)) return;
        btnVpsRelayTest.setEnabled(false);
        btnVpsRelaySave.setEnabled(false);
        btnVpsRelayTest.setText("...");
        Map<Integer, String> parsedDcRules;
        try {
            parsedDcRules = MtProtoConfig.parseDcRules(etDcRules.getText().toString());
        } catch (Exception ignored) {
            parsedDcRules = MtProtoConfig.parseDcRules(MtProtoConfig.DEFAULT_DC_RULES);
        }
        final Map<Integer, String> dcRules = parsedDcRules;
        new Thread(() -> {
            VpsRelayCheckResult result = new VpsRelayClient().check(relay, dcRules);
            handler.post(() -> {
                vpsRelayTestRunning.set(false);
                btnVpsRelayTest.setEnabled(true);
                btnVpsRelaySave.setEnabled(true);
                btnVpsRelayTest.setText(R.string.test);
                if (result.status() == VpsRelayCheckResult.Status.OK) {
                    VpsRelayConfig validatedRelay = relay.withCapabilities(result.capabilities());
                    if (!saveVpsRelaySettings(validatedRelay)) return;
                    DiagnosticsLog.record("vps relay check ok " + validatedRelay.host());
                    showVpsRelayTestResult(result);
                    checkActiveVpsRelayVersion(true);
                } else {
                    DiagnosticsLog.record("vps relay check failed "
                            + relay.host() + " " + result.status().name() + " "
                            + relayFailureSummary(result));
                    showVpsRelayTestResult(result);
                }
                refreshConnectionFields();
            });
        }, "tg-vps-relay-test").start();
    }

    private static String relayFailureSummary(VpsRelayCheckResult result) {
        if (result == null) return "UNKNOWN";
        String message = result.message() == null ? "" : result.message().trim();
        String value = result.status().name() + (message.isEmpty() ? "" : " — " + message);
        return value.length() > 220 ? value.substring(0, 220) : value;
    }

    private void showVpsRelayTestResult(VpsRelayCheckResult result) {
        VpsRelayTestPresentation presentation = VpsRelayTestPresentation.from(result);
        int title;
        int message;
        int icon;
        switch (presentation.kind()) {
            case SUCCESS:
                title = R.string.vps_relay_test_title_ok;
                message = R.string.vps_relay_test_message_ok;
                icon = R.drawable.ic_status_check;
                break;
            case SUCCESS_WITH_TEST_DC_WARNING:
                title = R.string.vps_relay_test_title_ok;
                message = R.string.vps_relay_test_message_test_warning;
                icon = R.drawable.ic_status_check;
                break;
            case INVALID_SETTINGS:
                title = R.string.vps_relay_test_title_settings;
                message = R.string.vps_relay_test_message_settings;
                icon = R.drawable.ic_status_error;
                break;
            case WRONG_TOKEN:
                title = R.string.vps_relay_test_title_token;
                message = R.string.vps_relay_test_message_token;
                icon = R.drawable.ic_status_error;
                break;
            case TLS:
                title = R.string.vps_relay_test_title_tls;
                message = R.string.vps_relay_test_message_tls;
                icon = R.drawable.ic_status_error;
                break;
            case OUTDATED:
                title = R.string.vps_relay_test_title_version;
                message = R.string.vps_relay_test_message_version;
                icon = R.drawable.ic_status_error;
                break;
            case DNS:
                title = R.string.vps_relay_test_title_dns;
                message = R.string.vps_relay_test_message_dns;
                icon = R.drawable.ic_status_error;
                break;
            case TIMEOUT:
                title = R.string.vps_relay_test_title_timeout;
                message = R.string.vps_relay_test_message_timeout;
                icon = R.drawable.ic_status_error;
                break;
            case TELEGRAM_DC:
                title = R.string.vps_relay_test_title_telegram;
                message = R.string.vps_relay_test_message_telegram;
                icon = R.drawable.ic_status_error;
                break;
            case SERVER_UNAVAILABLE:
            default:
                title = R.string.vps_relay_test_title_server;
                message = R.string.vps_relay_test_message_server;
                icon = R.drawable.ic_status_error;
                break;
        }
        new MaterialAlertDialogBuilder(this)
                .setIcon(icon)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void checkActiveVpsRelayVersion(boolean userTriggered) {
        VpsRelayConfig relay = activeVpsRelayConfig();
        if (relay == null || !relay.isUsable()) return;
        if (!vpsRelayVersionCheckRunning.compareAndSet(false, true)) return;
        new Thread(() -> {
            VpsRelayInfo info = new VpsRelayClient().inspect(relay, VpsSetupScripts.RELAY_VERSION);
            handler.post(() -> {
                vpsRelayVersionCheckRunning.set(false);
                if (info.status() == VpsRelayCheckResult.Status.OK
                        && info.capabilities().known()
                        && !info.capabilities().equals(relay.capabilities())) {
                    if (saveVpsRelaySettings(relay.withCapabilities(info.capabilities()))) {
                        DiagnosticsLog.record("vps relay capabilities refreshed revision="
                                + info.capabilities().topologyRevision());
                        refreshConnectionFields();
                    }
                }
                if (info.updateAvailable()) {
                    DiagnosticsLog.record("vps relay update available "
                            + relay.host() + " " + info.relayVersion() + " -> " + info.targetVersion());
                    showVpsRelayServerUpdateOffer(relay, info);
                } else if (userTriggered && info.status() != VpsRelayCheckResult.Status.OK) {
                    DiagnosticsLog.record("vps relay version check failed "
                            + relay.host() + " " + info.status().name());
                }
            });
        }, "tg-vps-relay-version").start();
    }

    private void showVpsRelayServerUpdateOffer(VpsRelayConfig relay, VpsRelayInfo info) {
        String key = relay.host() + ":" + relay.port() + ":" + info.relayVersion()
                + ">" + info.targetVersion();
        if (key.equals(vpsRelayUpdateDialogKey)) return;
        vpsRelayUpdateDialogKey = key;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_relay_update_title)
                .setMessage(getString(R.string.vps_relay_update_message,
                        info.relayVersion(), info.targetVersion()))
                .setPositiveButton(R.string.vps_relay_update_server,
                        (dialog, which) -> openVpsSetup(true))
                .setNegativeButton(R.string.skip_update, null)
                .show();
    }

    private void showAutoCfProxyResult(FlowsealConnectivity.Result result) {
        int ok = result.okCount();
        int total = result.expectedCount();
        String title = getString(ok > 0 ? R.string.cf_proxy_available : R.string.cf_proxy_unavailable);
        String message;
        if (ok > 0) {
            message = getString(R.string.cf_proxy_auto_available, ok, total);
            if (!result.domain.isEmpty()) {
                message += "\n\n" + getString(R.string.cf_active_domain, result.domain);
            }
        } else {
            message = getString(R.string.cf_proxy_auto_unavailable);
        }
        if (ok < total && !result.failDetails("kws").isEmpty()) {
            message += "\n\n" + result.failDetails("kws");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showMultiConnectivityResults(int titleRes, Map<String, FlowsealConnectivity.Result> results,
                                              String prefix) {
        StringBuilder message = new StringBuilder();
        boolean anyOk = false;
        for (Map.Entry<String, FlowsealConnectivity.Result> entry : results.entrySet()) {
            FlowsealConnectivity.Result result = entry.getValue();
            int ok = result.okCount();
            int total = result.expectedCount();
            if (message.length() > 0) message.append("\n\n");
            if (ok == total) {
                anyOk = true;
                message.append(getString(R.string.cf_domain_all_ok, entry.getKey(), total));
            } else if (ok == 0) {
                message.append(getString(R.string.cf_domain_none_ok, entry.getKey()));
            } else {
                anyOk = true;
                message.append(getString(R.string.cf_domain_partial_ok,
                        entry.getKey(), result.okLabels(prefix)));
                String fail = result.failDetails(prefix);
                if (!fail.isEmpty()) message.append("\n").append(fail);
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(anyOk ? getString(R.string.cf_proxy_available) : getString(R.string.cf_proxy_unavailable))
                .setMessage(message.length() == 0 ? getString(R.string.test_failed) : message.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void checkUpdates(boolean userTriggered) {
        tvUpdateStatus.setText(R.string.checking);
        progressUpdate.setVisibility(android.view.View.GONE);
        tvUpdateProgress.setVisibility(android.view.View.GONE);
        GithubReleaseUpdater.checkLatest(new GithubReleaseUpdater.Callback() {
            @Override public void onResult(GithubReleaseUpdater.ReleaseInfo info) {
                handler.post(() -> {
                    lastRelease = info;
                    if (info != null && GithubReleaseUpdater.isNewerVersion(info.version, BuildConfig.VERSION_NAME)) {
                        tvUpdateStatus.setText(getString(R.string.update_available, info.version));
                        boolean canInstall = info.apkUrl != null && !info.apkUrl.isEmpty();
                        btnInstallUpdate.setEnabled(canInstall);
                        if (canInstall) showUpdateOffer(info);
                    } else {
                        tvUpdateStatus.setText(R.string.up_to_date);
                        btnInstallUpdate.setEnabled(false);
                    }
                });
            }

            @Override public void onError(Exception error) {
                handler.post(() -> {
                    tvUpdateStatus.setText(R.string.test_failed);
                    if (userTriggered) Toast.makeText(MainActivity.this, R.string.test_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void installLastRelease() {
        if (lastRelease == null || lastRelease.apkUrl == null || lastRelease.apkUrl.isEmpty()) return;
        if (!GithubReleaseUpdater.canInstallPackages(this)) {
            pendingInstallAfterPermission = true;
            showInstallPermissionDialog(GithubReleaseUpdater.installPermissionIntent(this));
            return;
        }
        tvUpdateStatus.setText(R.string.download_started);
        progressUpdate.setVisibility(android.view.View.VISIBLE);
        progressUpdate.setIndeterminate(true);
        tvUpdateProgress.setVisibility(android.view.View.VISIBLE);
        tvUpdateProgress.setText("-");
        GithubReleaseUpdater.downloadAndInstall(this, lastRelease, new GithubReleaseUpdater.InstallCallback() {
            @Override public void onPermissionRequired(Intent intent) {
                handler.post(() -> {
                    pendingInstallAfterPermission = true;
                    showInstallPermissionDialog(intent);
                });
            }

            @Override public void onProgress(long downloaded, long total, long bytesPerSecond) {
                handler.post(() -> updateDownloadProgress(downloaded, total, bytesPerSecond));
            }

            @Override public void onInstallerReady(Intent intent) {
                handler.post(() -> {
                    tvUpdateStatus.setText(R.string.installer_started);
                    progressUpdate.setIndeterminate(false);
                    progressUpdate.setProgress(progressUpdate.getMax());
                    pendingInstallVersion = lastRelease == null ? "" : lastRelease.version;
                    boolean desiredRunning = ProxyService.getInstance() != null
                            || ProxyRunStateStore.fromPreferences(prefs).desiredRunning();
                    if (desiredRunning) {
                        ProxyRunStateStore.fromPreferences(prefs).setDesiredRunning(true);
                    }
                    boolean persisted = prefs.edit()
                            .putString(KEY_PENDING_INSTALL_VERSION, pendingInstallVersion)
                            .putBoolean(KEY_PENDING_INSTALL_DESIRED, desiredRunning)
                            .commit();
                    if (!persisted) {
                        tvUpdateStatus.setText(R.string.settings_save_failed);
                        Toast.makeText(MainActivity.this, R.string.settings_save_failed,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        startActivityForResult(intent, REQUEST_INSTALL_UPDATE);
                    } catch (Exception e) {
                        clearPendingInstallState();
                        tvUpdateStatus.setText(R.string.download_failed);
                        Toast.makeText(MainActivity.this, R.string.download_failed, Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override public void onError(Exception error) {
                handler.post(() -> {
                    tvUpdateStatus.setText(R.string.download_failed);
                    progressUpdate.setVisibility(android.view.View.GONE);
                    Toast.makeText(MainActivity.this, R.string.download_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showUpdateOffer(GithubReleaseUpdater.ReleaseInfo info) {
        if (info.version.equals(updateDialogVersion)) return;
        updateDialogVersion = info.version;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_dialog_title)
                .setMessage(getString(R.string.update_dialog_message, info.version))
                .setPositiveButton(R.string.install_update, (dialog, which) -> installLastRelease())
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.open_release, (dialog, which) -> openLink(info.htmlUrl))
                .show();
    }

    private void showInstallPermissionDialog(Intent intent) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.install_permission_title)
                .setMessage(R.string.install_permission_message)
                .setPositiveButton(R.string.open_settings, (dialog, which) -> {
                    try { startActivity(intent); }
                    catch (Exception ignored) {}
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void verifyPendingUpdateInstall() {
        String persisted = prefs == null ? ""
                : prefs.getString(KEY_PENDING_INSTALL_VERSION, "");
        if (persisted != null && !persisted.trim().isEmpty()) pendingInstallVersion = persisted;
        String expected = pendingInstallVersion == null ? "" : pendingInstallVersion.trim();
        if (expected.isEmpty()) return;
        String installed = installedVersionName();
        if (expected.equals(installed)) {
            boolean restoreProxy = prefs.getBoolean(KEY_PENDING_INSTALL_DESIRED, false);
            clearPendingInstallState();
            if (restoreProxy) ProxyServiceLauncher.restoreIfDesired(this, "update-installed");
            tvUpdateStatus.setText(R.string.update_installed_restart);
            btnInstallUpdate.setEnabled(false);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.update_installed_title)
                    .setMessage(R.string.update_installed_message)
                    .setPositiveButton(R.string.restart_app, (dialog, which) -> restartApp())
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
        } else {
            clearPendingInstallState();
            tvUpdateStatus.setText(getString(R.string.update_install_not_applied, installed));
        }
    }

    private void clearPendingInstallState() {
        pendingInstallVersion = "";
        if (prefs != null) {
            prefs.edit()
                    .remove(KEY_PENDING_INSTALL_VERSION)
                    .remove(KEY_PENDING_INSTALL_DESIRED)
                    .commit();
        }
    }

    private String installedVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception ignored) {
            return BuildConfig.VERSION_NAME;
        }
    }

    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
        finishAffinity();
        Runtime.getRuntime().exit(0);
    }

    private void exportSafeProfile() {
        showExportPayload(
                SettingsTransfer.exportSafeProfile(currentTransferData()),
                "tgproxy-safe-profile.tgproxy");
    }

    private void exportVpsRelay() {
        VpsRelayConfig relay = selectedStoredVpsRelayConfig();
        if (relay == null || !relay.isUsable()) {
            Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show();
            return;
        }
        showRelayShareMenu(relay);
    }

    private void showRelayShareMenu(VpsRelayConfig relay) {
        RelayShareSheet.show(this, relay);
    }

    private void openVpsSetup(boolean updateExistingRelay) {
        if (vpsSetupRunning) {
            Toast.makeText(this, R.string.vps_setup_running, Toast.LENGTH_SHORT).show();
            return;
        }
        String profileKey = vpsRelayProfileKeyForUi();
        String relayId = selectedVpsRelayId();
        if (relayId.isEmpty()) {
            String boundRelayId = VpsRelayStore.fromContext(this).selectedRelayId(profileKey);
            relayId = boundRelayId == null ? "" : boundRelayId.trim();
        }
        startActivityForResult(VpsSetupActivity.intent(
                this, profileKey, relayId, updateExistingRelay), REQUEST_VPS_SETUP);
    }

    private void openVpsOwnerManager() {
        VpsRelayConfig relay = selectedStoredVpsRelayConfig();
        VpsOwnerRecord owner = new VpsOwnerStore(this).forRelay(relay);
        if (relay == null || !relay.hasValidEndpoint() || owner == null || !owner.canManage()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.vps_owner_manage)
                    .setMessage(R.string.vps_owner_not_available)
                    .setPositiveButton(R.string.vps_relay_auto_setup,
                            (dialog, which) -> openVpsSetup(true))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        String profileKey = vpsRelayProfileKeyForUi();
        String relayId = selectedVpsRelayId();
        if (relayId.isEmpty()) {
            relayId = VpsRelayStore.fromContext(this).selectedRelayId(profileKey);
        }
        startActivityForResult(VpsOwnerActivity.intent(this, profileKey, relayId),
                REQUEST_VPS_OWNER);
    }

    private void showVpsOwnerManager() {
        VpsRelayConfig relay = activeVpsRelayConfig();
        VpsOwnerRecord owner = new VpsOwnerStore(this).forRelay(relay);
        if (relay == null || !relay.isUsable() || owner == null || !owner.canManage()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.vps_owner_manage)
                    .setMessage(R.string.vps_owner_not_available)
                    .setPositiveButton(R.string.vps_relay_auto_setup,
                            (dialog, which) -> showVpsAutoSetupDialog(true))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        ProgressBar progress = new ProgressBar(this);
        progress.setPadding(dp(24), dp(18), dp(24), dp(18));
        AlertDialog loading = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_owner_loading)
                .setView(progress)
                .setCancelable(false)
                .create();
        loading.show();
        setEnabled(btnVpsOwnerManage, false);
        new Thread(() -> {
            try {
                VpsOwnerClient.Overview overview = new VpsOwnerClient()
                        .load(relay, owner.adminToken());
                handler.post(() -> {
                    loading.dismiss();
                    setEnabled(btnVpsOwnerManage, true);
                    showVpsOwnerOverview(relay, owner, overview);
                });
            } catch (Exception error) {
                handler.post(() -> {
                    loading.dismiss();
                    setEnabled(btnVpsOwnerManage, true);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.vps_owner_manage)
                            .setMessage(getString(R.string.vps_owner_failed,
                                    firstLine(error.getMessage())))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        }, "tg-vps-owner-load").start();
    }

    private void showVpsOwnerOverview(VpsRelayConfig relay, VpsOwnerRecord owner,
                                      VpsOwnerClient.Overview overview) {
        ArrayList<VpsOwnerClient.Token> tokens = new ArrayList<>(overview.tokens());
        String[] labels = new String[tokens.size() + 2];
        labels[0] = getString(R.string.vps_owner_create_token);
        for (int i = 0; i < tokens.size(); i++) {
            VpsOwnerClient.Token token = tokens.get(i);
            labels[i + 1] = getString(R.string.vps_owner_token_summary,
                    token.name().isEmpty() ? token.id() : token.name(),
                    token.id(), token.activeDevices(), token.knownDevices(),
                    token.createdAt().isEmpty() ? "—" : token.createdAt());
        }
        labels[labels.length - 1] = getString(R.string.vps_owner_forget_local);
        showNotedItemsDialog(R.string.vps_owner_manage, R.string.vps_owner_manage_note,
                labels, (dialog, which) -> {
            if (which == 0) showVpsOwnerCreateToken(relay, owner);
            else if (which - 1 < tokens.size()) showVpsOwnerTokenActions(
                    relay, owner, overview, tokens.get(which - 1));
            else confirmForgetVpsOwnerData(relay);
        });
    }

    private void showNotedItemsDialog(int titleRes, int noteRes, String[] labels,
                                       DialogInterface.OnClickListener listener) {
        showNotedItemsDialog(getText(titleRes), getText(noteRes), labels, listener);
    }

    private void showNotedItemsDialog(CharSequence title, CharSequence noteText, String[] labels,
                                      DialogInterface.OnClickListener listener) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(2), dp(8), dp(4));

        TextView note = new TextView(this);
        note.setText(noteText == null ? "" : noteText);
        note.setTextColor(getColorValue(R.color.text_secondary));
        note.setTextSize(14);
        note.setLineSpacing(0f, 1.12f);
        note.setPadding(dp(16), dp(4), dp(16), dp(12));
        content.addView(note, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this, R.layout.dialog_list_item, labels));
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setPadding(0, dp(2), 0, dp(2));
        int visibleRows = Math.max(1, Math.min(labels.length, 5));
        content.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(76 * visibleRows)));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            listener.onClick(dialog, position);
        });
        dialog.show();
    }

    private void showVpsOwnerCreateToken(VpsRelayConfig relay, VpsOwnerRecord owner) {
        EditText name = dialogField(R.string.vps_owner_token_name, "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_owner_create_token)
                .setView(name)
                .setPositiveButton(R.string.vps_owner_create_token, (dialog, which) -> {
                    String tokenName = valueOrDefault(name, getString(R.string.vps_owner_token_default));
                    new Thread(() -> {
                        try {
                            VpsOwnerClient.CreatedToken created = new VpsOwnerClient().create(
                                    relay, owner.adminToken(), tokenName);
                            boolean saved = new VpsOwnerStore(this).saveManagedToken(relay,
                                    created.token().id(), created.token().name(), created.secret());
                            VpsRelayConfig shared = relay.withTokenAndName(created.secret(),
                                    created.token().name());
                            boolean rolledBack = false;
                            if (!saved) {
                                try {
                                    new VpsOwnerClient().delete(relay, owner.adminToken(),
                                            created.token().id());
                                    rolledBack = true;
                                } catch (Exception rollbackError) {
                                    DiagnosticsLog.record("VPS owner token rollback failed: "
                                            + firstLine(rollbackError.getMessage()));
                                }
                            }
                            boolean tokenRolledBack = rolledBack;
                            handler.post(() -> {
                                if (saved) {
                                    showRelayShareMenu(shared);
                                } else if (tokenRolledBack) {
                                    Toast.makeText(this, R.string.vps_owner_save_rolled_back,
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    new MaterialAlertDialogBuilder(this)
                                            .setTitle(R.string.vps_owner_save_failed_title)
                                            .setMessage(R.string.vps_owner_save_failed_recovery)
                                            .setPositiveButton(R.string.relay_share_title,
                                                    (ignored, button) -> showRelayShareMenu(shared))
                                            .setNegativeButton(android.R.string.cancel, null)
                                            .show();
                                }
                            });
                        } catch (Exception error) {
                            handler.post(() -> Toast.makeText(this,
                                    getString(R.string.vps_owner_failed,
                                            firstLine(error.getMessage())),
                                    Toast.LENGTH_LONG).show());
                        }
                    }, "tg-vps-owner-create").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showVpsOwnerTokenActions(VpsRelayConfig relay, VpsOwnerRecord owner,
                                          VpsOwnerClient.Overview overview,
                                          VpsOwnerClient.Token token) {
        VpsOwnerRecord freshOwner = new VpsOwnerStore(this).forRelay(relay);
        VpsOwnerRecord.ManagedToken local = freshOwner == null
                ? null : freshOwner.managedToken(token.id());
        ArrayList<String> actions = new ArrayList<>();
        if (local != null && !local.secret().isEmpty()) actions.add(getString(R.string.relay_share_title));
        actions.add(getString(R.string.vps_owner_devices));
        actions.add(getString(R.string.vps_owner_delete_token));
        showNotedItemsDialog(token.name().isEmpty() ? token.id() : token.name(),
                getString(R.string.vps_owner_token_actions_note, token.id()),
                actions.toArray(new String[0]), (dialog, which) -> {
                    String action = actions.get(which);
                    if (action.equals(getString(R.string.relay_share_title)) && local != null) {
                        showRelayShareMenu(relay.withTokenAndName(local.secret(), token.name()));
                    } else if (action.equals(getString(R.string.vps_owner_devices))) {
                        showVpsOwnerDevices(relay, owner, token,
                                overview.clientsFor(token.id()));
                    } else {
                        confirmDeleteVpsOwnerToken(relay, owner, token);
                    }
                });
    }

    private void showVpsOwnerDevices(VpsRelayConfig relay, VpsOwnerRecord owner,
                                     VpsOwnerClient.Token token,
                                     List<VpsOwnerClient.Client> clients) {
        if (clients == null || clients.isEmpty()) {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.vps_owner_devices)
                    .setMessage(R.string.vps_owner_no_devices)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        String[] labels = new String[clients.size()];
        for (int i = 0; i < clients.size(); i++) {
            VpsOwnerClient.Client client = clients.get(i);
            String state = getString(client.blocked()
                    ? R.string.vps_owner_device_blocked : R.string.vps_owner_device_active);
            labels[i] = getString(R.string.vps_owner_device_summary, client.deviceLabel(),
                    client.appVersion(), client.android(), client.locationLabel(),
                    client.activeSessions(), client.lastSeen()) + " • " + state;
        }
        showNotedItemsDialog(getText(R.string.vps_owner_devices),
                getString(R.string.vps_owner_devices_note,
                        token.name().isEmpty() ? token.id() : token.name()),
                labels, (dialog, which) -> showVpsOwnerDeviceActions(
                        relay, owner, token, clients.get(which)));
    }

    private void showVpsOwnerDeviceActions(VpsRelayConfig relay, VpsOwnerRecord owner,
                                           VpsOwnerClient.Token token,
                                           VpsOwnerClient.Client client) {
        String state = getString(client.blocked()
                ? R.string.vps_owner_device_blocked : R.string.vps_owner_device_active);
        String details = getString(R.string.vps_owner_device_detail,
                client.deviceLabel(), client.deviceId(), client.appVersion(), client.appCode(),
                client.android(), client.locationLabel(), client.firstSeen(), client.lastSeen(),
                client.activeSessions(), state);
        if (client.blocked() && !client.blockedAt().isEmpty()) {
            details += "\n" + getString(R.string.vps_owner_device_blocked_at,
                    client.blockedAt());
        }
        ArrayList<String> actions = new ArrayList<>();
        if (client.activeSessions() > 0) {
            actions.add(getString(R.string.vps_owner_disconnect_device));
        }
        actions.add(getString(client.blocked()
                ? R.string.vps_owner_unblock_device : R.string.vps_owner_block_device));
        showNotedItemsDialog(client.deviceLabel(), details, actions.toArray(new String[0]),
                (dialog, which) -> {
                    String action = actions.get(which);
                    if (action.equals(getString(R.string.vps_owner_disconnect_device))) {
                        runVpsOwnerDeviceAction(relay, owner, token, client, 0);
                    } else {
                        runVpsOwnerDeviceAction(relay, owner, token, client,
                                client.blocked() ? 2 : 1);
                    }
                });
    }

    private void runVpsOwnerDeviceAction(VpsRelayConfig relay, VpsOwnerRecord owner,
                                         VpsOwnerClient.Token token,
                                         VpsOwnerClient.Client client, int action) {
        new Thread(() -> {
            try {
                VpsOwnerClient api = new VpsOwnerClient();
                if (action == 0) {
                    api.disconnectDevice(relay, owner.adminToken(), token.id(), client.deviceId());
                } else if (action == 1) {
                    api.blockDevice(relay, owner.adminToken(), token.id(), client.deviceId());
                } else {
                    api.unblockDevice(relay, owner.adminToken(), token.id(), client.deviceId());
                }
                handler.post(() -> {
                    Toast.makeText(this, R.string.vps_owner_device_action_done,
                            Toast.LENGTH_SHORT).show();
                    showVpsOwnerManager();
                });
            } catch (Exception error) {
                handler.post(() -> Toast.makeText(this,
                        getString(R.string.vps_owner_failed, firstLine(error.getMessage())),
                        Toast.LENGTH_LONG).show());
            }
        }, "tg-vps-owner-device-action").start();
    }

    private void confirmDeleteVpsOwnerToken(VpsRelayConfig relay, VpsOwnerRecord owner,
                                            VpsOwnerClient.Token token) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_owner_delete_token)
                .setMessage(R.string.vps_owner_delete_token_warning)
                .setPositiveButton(R.string.vps_owner_delete_token, (dialog, which) ->
                        new Thread(() -> {
                            try {
                                new VpsOwnerClient().delete(relay, owner.adminToken(), token.id());
                                boolean removedLocally = new VpsOwnerStore(this)
                                        .removeManagedToken(relay, token.id());
                                if (!removedLocally) {
                                    DiagnosticsLog.record("VPS owner token removed remotely but "
                                            + "local secret cleanup failed");
                                }
                                handler.post(() -> {
                                    Toast.makeText(this, removedLocally
                                                    ? R.string.vps_owner_token_deleted
                                                    : R.string.vps_owner_token_deleted_local_failed,
                                            Toast.LENGTH_LONG).show();
                                    showVpsOwnerManager();
                                });
                            } catch (Exception error) {
                                handler.post(() -> Toast.makeText(this,
                                        getString(R.string.vps_owner_failed,
                                                firstLine(error.getMessage())),
                                        Toast.LENGTH_LONG).show());
                            }
                        }, "tg-vps-owner-delete").start())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmForgetVpsOwnerData(VpsRelayConfig relay) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_owner_forget_local)
                .setMessage(R.string.vps_owner_forget_local_warning)
                .setPositiveButton(R.string.vps_owner_forget_local, (dialog, which) -> {
                    boolean removed = new VpsOwnerStore(this).forget(relay);
                    updateVpsRelayFieldsEnabled();
                    Toast.makeText(this, removed ? R.string.vps_owner_forgotten
                                    : R.string.vps_owner_save_failed,
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEncryptedExportDialog() {
        showEncryptedExportDialog(null);
    }

    private void showEncryptedExportDialog(VpsRelayConfig relayOnly) {
        EditText password = new EditText(this);
        password.setHint(R.string.import_password_hint);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setTextColor(getColorValue(R.color.text_primary));
        password.setHintTextColor(getColorValue(R.color.text_hint));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export_password_title)
                .setView(password)
                .setPositiveButton(R.string.export_encrypted_profile, (dialog, which) -> {
                    try {
                        String pass = password.getText().toString();
                        if (pass.trim().isEmpty()) throw new SettingsTransferException(
                                getString(R.string.export_password_required));
                        String payload = relayOnly == null
                                ? SettingsTransfer.exportEncrypted(currentTransferData(), pass)
                                : SettingsTransfer.exportEncryptedVpsRelay(relayOnly, pass);
                        showExportPayload(payload, relayOnly == null
                                ? "tgproxy-full-profile.tgproxy"
                                : "tgproxy-vps-relay-encrypted.tgproxy");
                    } catch (Exception e) {
                        Toast.makeText(this, getString(R.string.import_failed,
                                e.getMessage()), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showImportSettingsDialog() {
        showImportSettingsDialog("", false);
    }

    private void scanImportQr() {
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt(getString(R.string.scan_import_qr_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    private void showImportSettingsDialog(String initialPayload) {
        showImportSettingsDialog(initialPayload, false);
    }

    private void showImportSettingsDialog(String initialPayload,
                                          boolean confirmExternalAfterDecrypt) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(4), dp(18), 0);
        EditText payload = dialogField(R.string.import_payload_hint, initialPayload,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        payload.setSingleLine(false);
        payload.setMinLines(5);
        EditText password = dialogField(R.string.import_password_hint, "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(payload);
        form.addView(password);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_settings)
                .setView(form)
                .setPositiveButton(R.string.import_settings, (dialog, which) ->
                        importSettingsPayload(payload.getText().toString(),
                                password.getText().toString(), confirmExternalAfterDecrypt))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.open_import_file, (dialog, which) -> openImportFilePicker())
                .show();
    }

    private void showExportPayload(String payload, String fileName) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(4), dp(18), 0);

        TextView payloadView = new TextView(this);
        payloadView.setText(payload);
        payloadView.setTextColor(getColorValue(R.color.text_secondary));
        payloadView.setTextSize(12f);
        payloadView.setTextIsSelectable(true);
        payloadView.setPadding(dp(10), dp(10), dp(10), dp(10));
        ScrollView payloadScroll = new ScrollView(this);
        payloadScroll.addView(payloadView);
        layout.addView(payloadScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        Button copyButton = exportActionButton(R.string.copy_done);
        Button saveButton = exportActionButton(R.string.save_export);
        firstRow.addView(copyButton, weightedButtonParams());
        firstRow.addView(saveButton, weightedButtonParams());
        layout.addView(firstRow);

        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        Button shareButton = exportActionButton(R.string.share_export);
        Button qrButton = exportActionButton(R.string.show_qr);
        secondRow.addView(shareButton, weightedButtonParams());
        secondRow.addView(qrButton, weightedButtonParams());
        layout.addView(secondRow);

        copyButton.setOnClickListener(v -> {
            copy(payload);
            Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show();
        });
        saveButton.setOnClickListener(v -> saveTransferPayload(payload, fileName));
        shareButton.setOnClickListener(v -> shareTransferPayload(payload));
        qrButton.setOnClickListener(v -> showTransferQr(payload));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export_ready)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private Button exportActionButton(int textRes) {
        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setCornerRadius(dp(12));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setStrokeWidth(dp(1));
        button.setStrokeColor(ColorStateList.valueOf(getColorValue(R.color.input_border)));
        button.setTextColor(getColorValue(R.color.accent));
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void shareTransferPayload(String payload) {
        String text = payload;
        try {
            text = shareableImportText(SettingsTransfer.toDeepLink(payload));
        } catch (Exception ignored) {
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "TG Proxy settings");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, getString(R.string.share_export)));
    }

    private void shareRelayLink(String link) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.relay_share_title));
        intent.putExtra(Intent.EXTRA_TEXT, shareableImportText(link));
        intent.putExtra(Intent.EXTRA_HTML_TEXT, "<a href=\"" + link + "\">"
                + getString(R.string.relay_share_title) + "</a>");
        startActivity(Intent.createChooser(intent, getString(R.string.share_export)));
    }

    private void showTransferQr(String payload) {
        String link;
        try {
            link = SettingsTransfer.toDeepLink(payload);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        showTransferQr(payload, link);
    }

    private void showTransferQr(String payload, String link) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(8), dp(18), 0);
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setPadding(0, 0, 0, dp(8));
        try {
            image.setImageBitmap(QrCodeBitmap.create(link, dp(260)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        TextView text = new TextView(this);
        SpannableString clickableLink = new SpannableString(link);
        clickableLink.setSpan(new URLSpan(link), 0, link.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setText(clickableLink);
        text.setTextColor(getColorValue(R.color.text_secondary));
        text.setLinkTextColor(getColorValue(R.color.accent));
        text.setTextSize(12f);
        text.setLinksClickable(true);
        text.setMovementMethod(LinkMovementMethod.getInstance());
        text.setOnLongClickListener(v -> {
            copy(link);
            Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show();
            return true;
        });
        layout.addView(image);
        layout.addView(text);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export_qr_title)
                .setView(layout)
                .setPositiveButton(R.string.share_qr,
                        (dialog, which) -> shareTransferQrImage(payload, link))
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void shareTransferQrImage(String payload) {
        try {
            String link = SettingsTransfer.toDeepLink(payload);
            shareTransferQrImage(payload, link);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void shareTransferQrImage(String payload, String link) {
        try {
            File dir = new File(getCacheDir(), "exports");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
            File file = new File(dir, "tgproxy-import-qr.png");
            Bitmap bitmap = QrCodeBitmap.create(link, dp(512));
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_TEXT, shareableImportText(link));
            intent.putExtra(Intent.EXTRA_HTML_TEXT, "<a href=\"" + link + "\">TG Proxy import</a>");
            intent.putExtra(Intent.EXTRA_TITLE, "TG Proxy import QR");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share_qr)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private String shareableImportText(String link) {
        return "TG Proxy import:\n" + (link == null ? "" : link);
    }

    private void saveTransferPayload(String payload, String fileName) {
        try {
            File baseDir = exportDirectory();
            File file = new File(baseDir, fileName);
            try (OutputStreamWriter writer =
                         new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
                writer.write(payload == null ? "" : payload);
                writer.write('\n');
            }
            Toast.makeText(this, getString(R.string.export_saved,
                    file.getAbsolutePath()), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void openImportFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_IMPORT_FILE);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private String readTextFromUri(Uri uri) throws Exception {
        StringBuilder out = new StringBuilder();
        InputStream stream = getContentResolver().openInputStream(uri);
        if (stream == null) throw new IOException("could not open import file");
        try (InputStream input = stream;
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (out.length() + read > SettingsTransfer.MAX_IMPORT_CHARS) {
                    throw new SettingsTransferException("transfer payload is too large");
                }
                out.append(buffer, 0, read);
            }
        }
        return out.toString();
    }

    private void importSettingsPayload(String payload, String password) {
        importSettingsPayload(payload, password, false);
    }

    private void importSettingsPayload(String payload, String password,
                                       boolean confirmExternalAfterDecrypt) {
        try {
            SettingsTransfer.Imported imported = SettingsTransfer.isImportLink(payload)
                    ? SettingsTransfer.parseDeepLink(payload.trim(), password)
                    : SettingsTransfer.parse(payload, password);
            if (confirmExternalAfterDecrypt) {
                confirmExternalImport(imported);
            } else {
                applyImportedSettings(imported);
            }
        } catch (SettingsTransferException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("password") && (password == null || password.trim().isEmpty())) {
                showImportSettingsDialog(payload, confirmExternalAfterDecrypt);
            } else {
                Toast.makeText(this, getString(R.string.import_failed,
                        e.getMessage()), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private boolean handleImportIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return false;
        String raw = intent.getData().toString();
        if (!SettingsTransfer.isImportLink(raw)) return false;
        // Consume the external URI exactly once. Otherwise an Activity recreation can display a
        // second import confirmation for the same token-bearing link.
        intent.setData(null);
        try {
            SettingsTransfer.Imported imported = SettingsTransfer.parseDeepLink(raw, "");
            confirmExternalImport(imported);
        } catch (SettingsTransferException e) {
            showImportSettingsDialog(raw, true);
        }
        return true;
    }

    private void confirmExternalImport(SettingsTransfer.Imported imported) {
        if (imported == null || isFinishing()) return;
        SettingsTransfer.Data data = imported.data();
        VpsRelayConfig relay = data.relayConfig();
        String relaySummary = relay != null && relay.isUsable()
                ? relay.name() + " — " + relay.host() + ":" + relay.port() + relay.path()
                : getString(R.string.import_preview_no_relay);
        String profile = data.profileName().isEmpty()
                ? getString(R.string.import_preview_current_profile) : data.profileName();
        String changes;
        if (imported.kind() == SettingsTransfer.Kind.VPS_RELAY) {
            changes = getString(R.string.import_preview_relay_changes,
                    relay != null && relay.isUsable()
                            ? getString(R.string.import_preview_token_present)
                            : getString(R.string.import_preview_token_absent));
        } else {
            changes = getString(R.string.import_preview_network_changes,
                    data.customIp(), data.customPort(), countImportEntries(data.dcRules()),
                    data.cfMode(), countImportEntries(data.cfDomains()),
                    countImportEntries(data.workerDomains()),
                    imported.kind() == SettingsTransfer.Kind.FULL_PROFILE
                            ? getString(R.string.import_preview_secret_replaced)
                            : getString(R.string.import_preview_secret_unchanged));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(imported.kind() == SettingsTransfer.Kind.VPS_RELAY
                        ? R.string.import_relay_add_title : R.string.import_preview_title)
                .setMessage(getString(R.string.import_preview_message,
                        imported.kind().name(), profile,
                         data.routePreference().name(), relaySummary, changes))
                .setPositiveButton(R.string.import_settings,
                        (dialog, which) -> applyImportedSettings(imported))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyImportedSettings(SettingsTransfer.Imported imported) {
        if (imported == null) return;
        VpsRelayConfig relay = imported.data().relayConfig();
        if (relay != null && relay.isUsable()) {
            showRelayImportTargetDialog(imported);
            return;
        }
        applyImportedSettingsNow(imported, "");
    }

    private void showRelayImportTargetDialog(SettingsTransfer.Imported imported) {
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        NetworkProfileRecord current = store.profileOrCreate(
                NetworkProfileIdentifier.current(this), System.currentTimeMillis());
        List<VpsRelayImportTarget.Option> options =
                VpsRelayImportTarget.options(current, store.profilesSnapshot());
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            labels[i] = relayImportTargetLabel(options.get(i));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_relay_target_title)
                .setItems(labels, (dialog, which) -> {
                    if (which < 0 || which >= options.size()) return;
                    applyImportedSettingsNow(imported, options.get(which).profileKey());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String relayImportTargetLabel(VpsRelayImportTarget.Option option) {
        if (option == null || option.kind() == VpsRelayImportTarget.Kind.ALL_NETWORKS) {
            return getString(R.string.import_relay_all_networks);
        }
        String name = option.displayName().isEmpty() ? option.profileKey() : option.displayName();
        if (option.kind() == VpsRelayImportTarget.Kind.CURRENT_NETWORK) {
            return getString(R.string.import_relay_current_network, name);
        }
        return getString(R.string.import_relay_saved_network, name);
    }

    private void applyImportedSettingsNow(SettingsTransfer.Imported imported, String relayProfileKey) {
        if (imported == null) return;
        SettingsTransfer.Data data = imported.data();
        VpsRelayConfig relay = data.relayConfig();
        if (relay != null && relay.isUsable()) {
            String targetProfileKey = relayProfileKey == null ? "" : relayProfileKey.trim();
            VpsRelayConfig boundRelay = relay.withProfileKey(targetProfileKey);
            validateImportedRelayThenApply(imported, boundRelay);
            return;
        }
        commitImportedSettings(imported, relayProfileKey);
    }

    private void validateImportedRelayThenApply(SettingsTransfer.Imported imported,
                                                VpsRelayConfig boundRelay) {
        LinearLayout progressLayout = new LinearLayout(this);
        progressLayout.setOrientation(LinearLayout.HORIZONTAL);
        progressLayout.setPadding(dp(24), dp(18), dp(24), dp(12));
        ProgressBar progress = new ProgressBar(this);
        TextView message = new TextView(this);
        message.setText(R.string.import_relay_checking);
        message.setTextColor(getColorValue(R.color.text_primary));
        message.setPadding(dp(16), dp(8), 0, 0);
        progressLayout.addView(progress, new LinearLayout.LayoutParams(dp(36), dp(36)));
        progressLayout.addView(message, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_relay_target_title)
                .setView(progressLayout)
                .setCancelable(false)
                .create();
        progressDialog.show();

        Map<Integer, String> dcRules;
        try {
            String rawRules = imported.kind() == SettingsTransfer.Kind.VPS_RELAY
                    ? etDcRules.getText().toString() : imported.data().dcRules();
            dcRules = MtProtoConfig.parseDcRules(rawRules);
        } catch (Exception ignored) {
            dcRules = MtProtoConfig.relayDcRules();
        }
        final Map<Integer, String> checkedRules = dcRules;
        new Thread(() -> {
            VpsRelayCheckResult result = new VpsRelayClient().check(boundRelay, checkedRules);
            handler.post(() -> {
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                if (result.status() == VpsRelayCheckResult.Status.OK) {
                    commitImportedSettings(imported, boundRelay.profileKey(), result.capabilities());
                    return;
                }
                DiagnosticsLog.record("vps relay import rejected "
                        + boundRelay.host() + " " + result.status().name());
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.import_failed_title)
                        .setMessage(getString(R.string.import_relay_rejected,
                                result.status().name(), result.message()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "tg-vps-relay-import-check").start();
    }

    private void commitImportedSettings(SettingsTransfer.Imported imported,
                                        String relayProfileKey) {
        commitImportedSettings(imported, relayProfileKey, VpsRelayCapabilities.unknown());
    }

    private void commitImportedSettings(SettingsTransfer.Imported imported,
                                        String relayProfileKey,
                                        VpsRelayCapabilities capabilities) {
        if (imported == null) return;
        SettingsTransfer.Data data = imported.data();
        if (imported.kind() != SettingsTransfer.Kind.VPS_RELAY) {
            etCustomIp.setText(data.customIp());
            etCustomPort.setText(String.valueOf(data.customPort()));
            if (imported.kind() == SettingsTransfer.Kind.FULL_PROFILE
                    && !data.mtProtoSecret().isEmpty()) {
                etSecret.setText(data.mtProtoSecret());
            }
            if (!data.dcRules().isEmpty()) etDcRules.setText(data.dcRules());
            spCfMode.setSelection(cfModeIndex(data.cfMode()));
            etCfDomains.setText(data.cfDomains());
            cbCfCustomDomain.setChecked(!data.cfDomains().trim().isEmpty());
            etWorkerDomains.setText(data.workerDomains());
            updateCfCustomDomainEnabled();
            spRoutePreference.setSelection(routePreferenceIndex(data.routePreference()));
            applyRouteAvailabilityToControls(data.routeAvailability());
            if (data.profileName() != null && !data.profileName().trim().isEmpty()) {
                etProfileName.setText(data.profileName());
            }
        }
        VpsRelayConfig relay = data.relayConfig();
        VpsRelayConfig relayToCommit = null;
        if (relay != null && relay.isUsable()) {
            relay = relay.withCapabilities(capabilities);
            String targetProfileKey = relayProfileKey == null ? "" : relayProfileKey.trim();
            relayToCommit = relay.withProfileKey(targetProfileKey);
            fillVpsRelayForm(relayToCommit);
        }
        boolean committed = true;
        if (imported.kind() != SettingsTransfer.Kind.VPS_RELAY) {
            committed = saveSettingsWithRelay(relayToCommit);
        } else if (relayToCommit != null) {
            committed = saveVpsRelaySettings(relayToCommit);
        }
        if (!committed) {
            loadSettings();
            refreshConnectionFields();
            return;
        }
        refreshVpsRelaySelector();
        VpsRelayConfig securedRelay = activeVpsRelayConfig();
        if (securedRelay != null && securedRelay.isUsable()) {
            prefs.edit().remove("vps_relay_token").commit();
        }
        refreshConnectionFields();
        Toast.makeText(this, R.string.import_applied, Toast.LENGTH_LONG).show();
    }

    private SettingsTransfer.Data currentTransferData() {
        NetworkProfileRecord record = currentProfileRecord();
        return SettingsTransfer.Data.builder()
                .profileName(record == null ? "" : record.displayName())
                .routePreference(selectedRoutePreference())
                .routeAvailability(routeAvailabilityFromControls())
                .customIp(valueOrDefault(etCustomIp, MtProtoConfig.DEFAULT_HOST))
                .customPort(intOrDefault(etCustomPort, MtProtoConfig.DEFAULT_PORT))
                .mtProtoSecret(MtProtoConfig.normalizeSecretHex(etSecret.getText().toString()))
                .dcRules(etDcRules.getText().toString())
                .cfMode(selectedCfProxyMode())
                .cfDomains(cbCfCustomDomain != null && cbCfCustomDomain.isChecked()
                        ? etCfDomains.getText().toString() : "")
                .workerDomains(etWorkerDomains.getText().toString())
                .relayConfig(activeVpsRelayConfig())
                .build();
    }

    private File exportDirectory() {
        File baseDir = getExternalFilesDir("exports");
        if (baseDir == null) baseDir = new File(getFilesDir(), "exports");
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("Could not create export directory");
        }
        return baseDir;
    }

    private void updateDownloadProgress(long downloaded, long total, long bytesPerSecond) {
        progressUpdate.setVisibility(android.view.View.VISIBLE);
        tvUpdateProgress.setVisibility(android.view.View.VISIBLE);
        if (total > 0) {
            progressUpdate.setIndeterminate(false);
            progressUpdate.setMax(1000);
            progressUpdate.setProgress((int) Math.min(1000, downloaded * 1000 / total));
            tvUpdateProgress.setText(getString(R.string.download_progress_known,
                    TgConstants.humanBytes(downloaded),
                    TgConstants.humanBytes(total),
                    TgConstants.humanBytes(bytesPerSecond)));
        } else {
            progressUpdate.setIndeterminate(true);
            tvUpdateProgress.setText(getString(R.string.download_progress_unknown,
                    TgConstants.humanBytes(downloaded),
                    TgConstants.humanBytes(bytesPerSecond)));
        }
    }

    private void setupCopy(TextView tv) {
        if (tv == null) return;
        tv.setOnClickListener(v -> {
            String text = tv.getText().toString();
            if (text.equals("-")) return;
            copy(text);
            Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupTelegramLink() {
        tvTgLink.setOnClickListener(v -> openTelegramLink());
        tvTgLink.setOnLongClickListener(v -> {
            copy(tvTgLink.getText().toString());
            Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void openTelegramLink() {
        String link = tvTgLink.getText().toString();
        if (link.equals("-")) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
        } catch (Exception e) {
            copy(link);
            Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show();
        }
    }

    private void showDiagnostics(boolean returnToSettings) {
        diagnosticsReturnToSettings = returnToSettings
                && settingsScreen != null
                && settingsScreen.getVisibility() == View.VISIBLE;
        if (mainScreen != null) mainScreen.setVisibility(View.GONE);
        if (settingsScreen != null) settingsScreen.setVisibility(View.GONE);
        if (diagnosticsScreen != null) diagnosticsScreen.setVisibility(View.VISIBLE);
        refreshDiagnosticsScreen();
    }

    private void hideDiagnosticsScreen() {
        if (diagnosticsScreen != null) diagnosticsScreen.setVisibility(View.GONE);
        boolean showSettings = diagnosticsReturnToSettings
                && MainUiState.canOpenSettings(isProxyRunning());
        if (settingsScreen != null) {
            settingsScreen.setVisibility(showSettings ? View.VISIBLE : View.GONE);
        }
        if (mainScreen != null) {
            mainScreen.setVisibility(showSettings ? View.GONE : View.VISIBLE);
        }
        if (showSettings) {
            refreshProfileControls(false);
            showSettingsSection(currentSettingsSection);
        }
        diagnosticsReturnToSettings = false;
    }

    private void refreshDiagnosticsScreen() {
        refreshDiagnosticsScreen(currentDiagnosticsSnapshot());
    }

    private void resetDiagnostics() {
        DiagnosticsLog.clear();
        ProxyService svc = ProxyService.getInstance();
        if (svc != null) svc.resetDiagnosticsState();
        autoPingGate.reset();
        lastMeasuredPingMs = -1;
        lastMeasuredPingAt = 0L;
        lastMeasuredPingIdentity = "";
        bootstrapPingPlan = BootstrapPingPlanner.Plan.empty();
        bootstrapPingPlanBuiltAt = 0L;
        refreshDiagnosticsScreen();
        Toast.makeText(this, R.string.diagnostics_reset_done, Toast.LENGTH_SHORT).show();
    }

    private void refreshDiagnosticsScreen(DiagnosticsSnapshot snapshot) {
        if (diagnosticsScreen == null || diagnosticsScreen.getVisibility() != View.VISIBLE) return;
        long nowMs = System.currentTimeMillis();
        List<DiagnosticsRouteMatrix.Row> routeMatrix = diagnosticsRouteMatrix(snapshot, nowMs);
        setDiagnosticsText(tvDiagnosticsNetwork, formatNetworkBlock(snapshot));
        setDiagnosticsText(tvDiagnosticsProfile, formatProfileBlock(snapshot, nowMs));
        setDiagnosticsText(tvDiagnosticsRoute, formatRouteBlock(snapshot));
        setDiagnosticsText(tvDiagnosticsRouteChecks, DiagnosticsRouteMatrix.toReportText(routeMatrix));
        setDiagnosticsText(tvDiagnosticsHistory, formatSwitchHistory(DiagnosticsLog.snapshot()));
        setDiagnosticsText(tvDiagnosticsErrors, formatErrorsBlock(snapshot));
        setDiagnosticsText(tvDiagnosticsService, formatServiceBlock(snapshot));
        setDiagnosticsText(tvDiagnosticsReport, buildDiagnosticsReport(snapshot, routeMatrix, nowMs));
    }

    private void setDiagnosticsText(TextView view, String text) {
        if (view != null) view.setText(text == null || text.trim().isEmpty() ? "-" : text);
    }

    private String buildDiagnosticsReport() {
        long nowMs = System.currentTimeMillis();
        DiagnosticsSnapshot snapshot = currentDiagnosticsSnapshot();
        return buildDiagnosticsReport(snapshot, diagnosticsRouteMatrix(snapshot, nowMs), nowMs);
    }

    private String buildDiagnosticsReport(DiagnosticsSnapshot snapshot,
                                          List<DiagnosticsRouteMatrix.Row> routeMatrix,
                                          long generatedAtMs) {
        DiagnosticsReport.AppInfo appInfo = new DiagnosticsReport.AppInfo(
                getPackageName(), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT);
        return DiagnosticsReport.build(appInfo, snapshot, diagnosticsSettings(), routeMatrix,
                DiagnosticsLog.snapshot(), generatedAtMs);
    }

    private String buildShortDiagnosticsReport() {
        DiagnosticsSnapshot snapshot = currentDiagnosticsSnapshot();
        RouteState route = snapshot.serviceState().routeState();
        StringBuilder out = new StringBuilder();
        diagnosticLine(out, "TG Proxy", BuildConfig.VERSION_NAME);
        diagnosticLine(out, "Service", snapshot.serviceState().status().name());
        diagnosticLine(out, "Network", snapshot.networkProfile().defaultDisplayName());
        diagnosticLine(out, "Profile", currentProfileRecord().displayName());
        diagnosticLine(out, "Route", route == null || !route.active()
                ? valueOrDash(route == null ? "" : route.reason()) : route.displayName());
        diagnosticLine(out, "Endpoint", route == null ? "-" : route.activeEndpoint());
        if (route != null && !route.activeSni().isEmpty()) {
            diagnosticLine(out, "SNI", route.activeSni());
        }
        diagnosticLine(out, "Ping", route != null && route.pingMs() >= 0
                ? route.pingMs() + " ms" : MainUiState.pingSummary(lastMeasuredPingMs));
        diagnosticLine(out, "Last error", lastRouteErrorLabel(snapshot.routeStats()));
        return out.toString().trim();
    }

    private List<DiagnosticsRouteMatrix.Row> diagnosticsRouteMatrix(DiagnosticsSnapshot snapshot,
                                                                    long nowMs) {
        return DiagnosticsRouteMatrix.build(routeSettingsFromControls(),
                snapshot == null ? Collections.emptyMap() : snapshot.routeStats(), nowMs);
    }

    private DiagnosticsSnapshot currentDiagnosticsSnapshot() {
        ProxyService svc = ProxyService.getInstance();
        if (svc != null) return svc.diagnosticsSnapshot();
        NetworkProfileRecord record = currentProfileRecord();
        Map<String, RouteStats> routeStats = record == null
                ? Collections.emptyMap()
                : NetworkProfileStore.fromPreferences(prefs).routeStats(record.profile());
        return new DiagnosticsSnapshot(
                ServiceState.stopped(),
                record == null ? NetworkProfile.defaultProfile() : record.profile(),
                routeStats,
                0L,
                0L,
                0L,
                0L,
                0L,
                DiagnosticsSnapshot.totalRouteFailures(routeStats),
                0L);
    }

    private String formatNetworkBlock(DiagnosticsSnapshot snapshot) {
        NetworkProfile profile = snapshot.networkProfile();
        StringBuilder out = new StringBuilder();
        diagnosticLine(out, "Kind", profile.kind().name());
        diagnosticLine(out, "Name", profile.defaultDisplayName());
        diagnosticLine(out, "Key", profile.key());
        diagnosticLine(out, "CF profile", profile.cfProfileId());
        return out.toString().trim();
    }

    private String formatProfileBlock(DiagnosticsSnapshot snapshot, long nowMs) {
        NetworkProfileRecord record = currentProfileRecord();
        StringBuilder out = new StringBuilder();
        diagnosticLine(out, "Name", record.displayName());
        diagnosticLine(out, "Why selected", "current network match");
        diagnosticLine(out, "Key", record.key());
        diagnosticLine(out, "Last seen", formatLocalTimestamp(record.lastSeenMs()));
        diagnosticLine(out, "Seen count", String.valueOf(record.seenCount()));
        diagnosticLine(out, "Route preference", routePreferenceLabel(record.routePreference()));
        diagnosticLine(out, "Manual override", record.routePreference() == RoutePreference.AUTO ? "no" : "yes");
        diagnosticLine(out, "Last stable route", lastStableRoute(snapshot.routeStats()));
        diagnosticLine(out, "Cooldown routes", cooldownRoutes(snapshot.routeStats(), nowMs));
        return out.toString().trim();
    }

    private String formatRouteBlock(DiagnosticsSnapshot snapshot) {
        RouteState route = snapshot.serviceState().routeState();
        StringBuilder out = new StringBuilder();
        if (route == null || !route.active()) {
            diagnosticLine(out, "Active", "no");
            diagnosticLine(out, "Reason", route == null ? "no route state" : route.reason());
            return out.toString().trim();
        }
        RouteCandidate candidate = route.candidate();
        diagnosticLine(out, "Active", "yes");
        diagnosticLine(out, "Name", route.displayName());
        diagnosticLine(out, "Key", route.key());
        diagnosticLine(out, "Type", route.type() == null ? "-" : route.type().name());
        diagnosticLine(out, "Endpoint", route.activeEndpoint());
        if (!route.activeSni().isEmpty()) diagnosticLine(out, "SNI", route.activeSni());
        if (candidate != null) {
            diagnosticLine(out, "DC", String.valueOf(candidate.dc()));
            diagnosticLine(out, "Media", candidate.media() ? "yes" : "no");
            diagnosticLine(out, "Candidate endpoint", candidate.endpoint());
        }
        diagnosticLine(out, "Ping", route.pingMs() >= 0 ? route.pingMs() + " ms" : "-");
        diagnosticLine(out, "Quality", route.quality());
        return out.toString().trim();
    }

    private String formatSwitchHistory(List<String> logs) {
        if (logs == null || logs.isEmpty()) return "- no switching events";
        ArrayList<String> filtered = new ArrayList<>();
        for (String log : logs) {
            String value = valueOrDash(log);
            String lower = value.toLowerCase(Locale.US);
            if (lower.contains("route ") || lower.contains("profile")
                    || lower.contains("network")) {
                filtered.add(value);
            }
        }
        if (filtered.isEmpty()) return "- no switching events";
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, filtered.size() - 8);
        for (int i = start; i < filtered.size(); i++) {
            out.append("- ").append(filtered.get(i)).append('\n');
        }
        return out.toString().trim();
    }

    private String formatErrorsBlock(DiagnosticsSnapshot snapshot) {
        StringBuilder out = new StringBuilder();
        diagnosticLine(out, "Engine errors", String.valueOf(snapshot.engineErrors()));
        diagnosticLine(out, "Route failures", String.valueOf(snapshot.routeFailures()));
        diagnosticLine(out, "Last route error", lastRouteErrorLabel(snapshot.routeStats()));
        RouteState route = snapshot.serviceState().routeState();
        if (route != null && !route.active()) {
            diagnosticLine(out, "Route reason", route.reason());
        }
        if (snapshot.engineErrors() == 0L
                && snapshot.routeFailures() == 0L
                && "-".equals(lastRouteErrorLabel(snapshot.routeStats()))) {
            diagnosticLine(out, "Summary", "no active errors");
        }
        return out.toString().trim();
    }

    private String formatServiceBlock(DiagnosticsSnapshot snapshot) {
        ServiceState state = snapshot.serviceState();
        StringBuilder out = new StringBuilder();
        diagnosticLine(out, "Status", state.status().name());
        diagnosticLine(out, "Service started", state.serviceStarted() ? "yes" : "no");
        diagnosticLine(out, "Engine running", state.engineRunning() ? "yes" : "no");
        diagnosticLine(out, "Local port listening", state.localPortListening() ? "yes" : "no");
        diagnosticLine(out, "Paused", state.paused() ? "yes" : "no");
        diagnosticLine(out, "Uptime", MainUiState.uptimeSummary(snapshot.uptimeMs()));
        diagnosticLine(out, "Active connections", String.valueOf(snapshot.activeConnections()));
        diagnosticLine(out, "Total connections", String.valueOf(snapshot.totalConnections()));
        diagnosticLine(out, "Traffic up", TgConstants.humanBytes(snapshot.bytesUp()));
        diagnosticLine(out, "Traffic down", TgConstants.humanBytes(snapshot.bytesDown()));
        return out.toString().trim();
    }

    private String lastStableRoute(Map<String, RouteStats> routeStats) {
        String key = "";
        long lastSuccess = 0L;
        if (routeStats != null) {
            for (Map.Entry<String, RouteStats> entry : routeStats.entrySet()) {
                RouteStats stats = entry.getValue();
                if (stats == null || stats.successCount() <= 0) continue;
                long successMs = stats.lastSuccessMs();
                if (successMs >= lastSuccess) {
                    lastSuccess = successMs;
                    key = entry.getKey();
                }
            }
        }
        return key.isEmpty() ? "-" : key + " at " + formatLocalTimestamp(lastSuccess);
    }

    private String cooldownRoutes(Map<String, RouteStats> routeStats, long nowMs) {
        if (routeStats == null || routeStats.isEmpty()) return "-";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, RouteStats> entry : routeStats.entrySet()) {
            RouteStats stats = entry.getValue();
            if (stats == null || !stats.copy().isCoolingDown(nowMs)) continue;
            if (out.length() > 0) out.append(", ");
            out.append(entry.getKey());
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    private String lastRouteErrorLabel(Map<String, RouteStats> routeStats) {
        RouteError lastError = RouteError.NONE;
        long lastUpdateMs = 0L;
        if (routeStats != null) {
            for (RouteStats stats : routeStats.values()) {
                if (stats == null || stats.lastError() == RouteError.NONE) continue;
                if (stats.lastUpdateMs() >= lastUpdateMs) {
                    lastUpdateMs = stats.lastUpdateMs();
                    lastError = stats.lastError();
                }
            }
        }
        return lastError == RouteError.NONE ? "-" : DiagnosticsRouteMatrix.errorLabel(lastError);
    }

    private String formatLocalTimestamp(long timeMs) {
        if (timeMs <= 0L) return "-";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timeMs));
    }

    private static void diagnosticLine(StringBuilder out, String label, String value) {
        out.append(label).append(": ").append(valueOrDash(value)).append('\n');
    }

    private static String valueOrDash(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? "-" : normalized;
    }

    private DiagnosticsReport.AppSettings diagnosticsSettings() {
        NetworkProfileRecord record = currentProfileRecord();
        boolean customCf = cbCfCustomDomain != null && cbCfCustomDomain.isChecked();
        List<String> cfDomains = customCf
                ? splitDomains(etCfDomains.getText().toString())
                : FlowsealCfDomains.defaults();
        VpsRelayConfig relay = currentVpsRelayConfig();
        return DiagnosticsReport.AppSettings.builder()
                .localEndpoint(valueOrDefault(etCustomIp, MtProtoConfig.DEFAULT_HOST),
                        intOrDefault(etCustomPort, MtProtoConfig.DEFAULT_PORT))
                .secretConfigured(hasConfiguredSecret(etSecret))
                .dcRules(etDcRules.getText().toString())
                .cfMode(selectedCfProxyMode())
                .cfCustomDomains(customCf)
                .cfDomains(cfDomains)
                .workerDomains(splitDomains(etWorkerDomains.getText().toString()))
                .vpsRelay(relay.isEnabled(), relay.name(), relay.host(), relay.port(),
                        relay.tls(), relay.path(), relay.maskedToken(), relay.profileKey())
                .profileName(record == null ? safeText(tvMainProfile) : record.displayName())
                .routePreference(record == null ? "" : routePreferenceLabel(record.routePreference()))
                .cfWarmup(cbCfWarmup != null && cbCfWarmup.isChecked())
                .recheckOnNetworkChange(cbCfRecheckNetwork != null && cbCfRecheckNetwork.isChecked())
                .smartSleep(cbSmartSleep != null && cbSmartSleep.isChecked())
                .autostartOpen(cbAutostartOpen != null && cbAutostartOpen.isChecked())
                .autostartBoot(cbAutostartBoot != null && cbAutostartBoot.isChecked())
                .theme(themeLabel())
                .language(languageLabel())
                .checkUpdates(cbCheckUpdates != null && cbCheckUpdates.isChecked())
                .verboseLogging(cbVerbose != null && cbVerbose.isChecked())
                .build();
    }

    private void saveDiagnosticsReport(String report) {
        try {
            File baseDir = diagnosticsDirectory();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File file = new File(baseDir, "tgproxy-diagnostics-" + stamp + ".txt");
            try (OutputStreamWriter writer =
                         new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
                writer.write(report);
                writer.write('\n');
            }
            DiagnosticsLog.record("diagnostics report saved " + file.getAbsolutePath());
            Toast.makeText(this, getString(R.string.diagnostics_report_saved,
                    file.getAbsolutePath()), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            DiagnosticsLog.record("diagnostics report save failed " + e.getClass().getSimpleName());
            Toast.makeText(this, R.string.diagnostics_report_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void saveDiagnosticsZip(String report) {
        try {
            File baseDir = diagnosticsDirectory();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File file = new File(baseDir, "tgproxy-diagnostics-" + stamp + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
                writeZipEntry(zip, "report.txt", report);
                writeZipEntry(zip, "logs.txt", joinLines(DiagnosticsLog.snapshot()));
            }
            DiagnosticsLog.record("diagnostics zip saved " + file.getAbsolutePath());
            Toast.makeText(this, getString(R.string.diagnostics_report_saved,
                    file.getAbsolutePath()), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            DiagnosticsLog.record("diagnostics zip save failed " + e.getClass().getSimpleName());
            Toast.makeText(this, R.string.diagnostics_report_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void shareDiagnosticsReport(String report) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics));
        intent.putExtra(Intent.EXTRA_TEXT, report);
        DiagnosticsLog.record("diagnostics report shared");
        startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_share)));
    }

    private File diagnosticsDirectory() {
        File baseDir = getExternalFilesDir("diagnostics");
        if (baseDir == null) baseDir = new File(getFilesDir(), "diagnostics");
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("Could not create diagnostics directory");
        }
        return baseDir;
    }

    private static void writeZipEntry(ZipOutputStream zip, String name, String content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((content == null ? "" : content).getBytes("UTF-8"));
        zip.closeEntry();
    }

    private static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line == null) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private String safeText(TextView view) {
        if (view == null || view.getText() == null) return "-";
        String value = view.getText().toString().trim();
        return value.isEmpty() ? "-" : value;
    }

    private String themeLabel() {
        String[] labels = getResources().getStringArray(R.array.theme_options);
        int index = themeIndex(prefs.getString("theme_mode", "system"));
        return labels[Math.max(0, Math.min(index, labels.length - 1))];
    }

    private String languageLabel() {
        String[] labels = getResources().getStringArray(R.array.language_options);
        int index = languageIndex(prefs.getString("language_mode", "system"));
        return labels[Math.max(0, Math.min(index, labels.length - 1))];
    }

    private static boolean hasConfiguredSecret(EditText editText) {
        if (editText == null || editText.getText() == null) return false;
        String secret = editText.getText().toString().trim().toLowerCase(Locale.US);
        if (secret.startsWith("dd") && secret.length() == 34) secret = secret.substring(2);
        return secret.matches("[0-9a-f]{32}");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showMainMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (isProxyRunning()) {
            menu.getMenu().add(0, 1, 0, R.string.stop);
        } else {
            menu.getMenu().add(0, 2, 0, R.string.settings);
        }
        menu.getMenu().add(0, 3, 1, R.string.github_repo);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                stopProxy();
                return true;
            }
            if (id == 2) {
                openSettingsOrWarn();
                return true;
            }
            if (id == 3) {
                openLink(REPO_URL);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void copy(String text) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("tgproxy", text));
    }

    private void openLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception ignored) {}
    }

    private int getColorValue(int colorRes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return getColor(colorRes);
        return getResources().getColor(colorRes);
    }

    private int firstDcId() {
        try {
            return MtProtoConfig.parseDcRules(etDcRules.getText().toString()).keySet().iterator().next();
        } catch (Exception ignored) {
            return 2;
        }
    }

    private String currentCfNetworkProfile() {
        return NetworkProfileIdentifier.current(this).cfProfileId();
    }

    private VpsRelayConfig currentVpsRelayConfig() {
        String profileKey = cbVpsRelayBindProfile != null && cbVpsRelayBindProfile.isChecked()
                ? vpsRelayProfileKeyForUi() : "";
        int fallbackPort = cbVpsRelayTls != null && cbVpsRelayTls.isChecked() ? 443 : 80;
        return VpsRelayConfig.manual(
                cbVpsRelayEnabled != null && cbVpsRelayEnabled.isChecked(),
                valueOrDefault(etVpsRelayName, "VPS Relay"),
                valueOrDefault(etVpsRelayHost, ""),
                intOrDefault(etVpsRelayPort, fallbackPort),
                cbVpsRelayTls == null || cbVpsRelayTls.isChecked(),
                valueOrDefault(etVpsRelayPath, "/apiws"),
                valueOrDefault(etVpsRelayToken, ""),
                profileKey);
    }

    private VpsRelayConfig activeVpsRelayConfig() {
        String profileKey = currentProfileRecord().key();
        VpsRelayConfig selected = VpsRelayStore.fromContext(this).selectedRelay(profileKey);
        return selected == null ? VpsRelayConfig.disabled() : selected;
    }

    private VpsRelayConfig selectedStoredVpsRelayConfig() {
        VpsRelayStore store = VpsRelayStore.fromContext(this);
        String relayId = selectedVpsRelayId();
        VpsRelayStore.Record record = relayId.isEmpty() ? null : store.relay(relayId);
        if (record != null) return record.config().withProfileKey(vpsRelayProfileKeyForUi());
        VpsRelayConfig selected = store.selectedRelay(vpsRelayProfileKeyForUi());
        return selected == null ? activeVpsRelayConfig() : selected;
    }

    private String vpsRelayProfileKeyForUi() {
        if (displayedProfileKey != null && !displayedProfileKey.trim().isEmpty()) {
            return displayedProfileKey;
        }
        return currentProfileRecord().key();
    }

    private void refreshVpsRelaySelector() {
        if (spVpsRelaySaved == null || prefs == null) return;
        VpsRelayStore store = VpsRelayStore.fromContext(this);
        List<VpsRelayStore.Record> relays = store.relays();
        vpsRelaySelectorReady = false;
        vpsRelaySelectorIds.clear();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        vpsRelaySelectorIds.add("");
        adapter.add(getString(R.string.vps_relay_none));
        for (VpsRelayStore.Record record : relays) {
            vpsRelaySelectorIds.add(record.id());
            adapter.add(record.displayName());
        }
        spVpsRelaySaved.setAdapter(adapter);
        boolean profileBound = cbVpsRelayBindProfile != null && cbVpsRelayBindProfile.isChecked();
        String selected = store.selectedRelayId(profileBound ? vpsRelayProfileKeyForUi() : "");
        int index = selected == null ? -1 : vpsRelaySelectorIds.indexOf(selected);
        spVpsRelaySaved.setSelection(Math.max(0, index));
        vpsRelaySelectorReady = true;
        if (index >= 0) {
            VpsRelayStore.Record record = store.relay(selected);
            if (record != null) {
                fillVpsRelayForm(record.config().withProfileKey(
                        profileBound ? vpsRelayProfileKeyForUi() : ""));
            }
        }
        setEnabled(btnVpsRelayDelete, index > 0);
    }

    private void fillVpsRelayForm(VpsRelayConfig relay) {
        if (relay == null) return;
        vpsRelayFormBinding = true;
        try {
            cbVpsRelayEnabled.setChecked(relay.isEnabled());
            etVpsRelayName.setText(relay.name());
            etVpsRelayHost.setText(relay.host());
            etVpsRelayPort.setText(String.valueOf(relay.port() > 0 ? relay.port() : 443));
            cbVpsRelayTls.setChecked(relay.tls());
            etVpsRelayPath.setText(relay.path());
            etVpsRelayToken.setText(relay.token());
            cbVpsRelayBindProfile.setChecked(!relay.profileKey().isEmpty());
        } finally {
            vpsRelayFormBinding = false;
        }
        updateVpsRelayFieldsEnabled();
    }

    private void clearVpsRelayForm() {
        vpsRelayFormBinding = true;
        try {
            cbVpsRelayEnabled.setChecked(false);
            etVpsRelayName.setText("");
            etVpsRelayHost.setText("");
            etVpsRelayPort.setText("443");
            cbVpsRelayTls.setChecked(true);
            etVpsRelayPath.setText("/apiws");
            etVpsRelayToken.setText("");
            cbVpsRelayBindProfile.setChecked(true);
        } finally {
            vpsRelayFormBinding = false;
        }
        updateVpsRelayFieldsEnabled();
        applyVpsSetupProgress(VpsSetupProgress.of(
                VpsSetupProgress.Stage.AUDIT, 0, getString(R.string.vps_setup_idle)));
    }

    private boolean saveCurrentVpsRelayFromForm() {
        VpsRelayConfig relay = preserveSavedVpsRelayCapabilities(currentVpsRelayConfig());
        if (relay.isEnabled() && !relay.isUsable()) {
            Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show();
            return false;
        }
        if (!saveVpsRelaySettings(relay, selectedVpsRelayId())) return false;
        refreshVpsRelaySelector();
        refreshConnectionFields();
        return true;
    }

    private VpsRelayConfig preserveSavedVpsRelayCapabilities(VpsRelayConfig relay) {
        if (relay == null || !relay.isUsable() || prefs == null) return relay;
        VpsRelayConfig saved = VpsRelayStore.fromContext(this)
                .selectedRelay(relay.profileKey());
        if (saved == null || !saved.sameRelayConnection(relay)) return relay;
        return relay.withCapabilities(saved.capabilities());
    }

    private void confirmDeleteSelectedVpsRelay() {
        String relayId = selectedVpsRelayId();
        if (relayId.isEmpty()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_relay_delete_title)
                .setMessage(R.string.vps_relay_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.vps_relay_delete, (dialog, which) -> deleteSelectedVpsRelay(relayId))
                .show();
    }

    private void deleteSelectedVpsRelay(String relayId) {
        if (prefs == null) return;
        VpsRelayStore store = VpsRelayStore.fromContext(this);
        SharedPreferences.Editor editor = prefs.edit();
        if (!store.deleteRelayInto(relayId, editor)) return;
        putVpsRelaySettings(editor, VpsRelayConfig.manual(
                false, "", "", 443, true, "/apiws", "", ""));
        if (!editor.commit()) {
            DiagnosticsLog.record("VPS Relay delete commit failed");
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        clearVpsRelayForm();
        refreshVpsRelaySelector();
        refreshConnectionFields();
        Toast.makeText(this, R.string.vps_relay_deleted, Toast.LENGTH_SHORT).show();
    }

    private String selectedVpsRelayId() {
        if (spVpsRelaySaved == null || vpsRelaySelectorIds.isEmpty()) return "";
        int index = spVpsRelaySaved.getSelectedItemPosition();
        if (index < 0 || index >= vpsRelaySelectorIds.size()) return "";
        String relayId = vpsRelaySelectorIds.get(index);
        return relayId == null ? "" : relayId;
    }

    private void applyVpsSetupProgress(VpsSetupProgress progress) {
        if (progress == null) return;
        if (progressVpsSetup != null) progressVpsSetup.setProgress(progress.percent());
        if (tvVpsSetupStatus != null) tvVpsSetupStatus.setText(progress.statusLine());
    }

    private void showVpsSetupWaitingForSsh() {
        applyVpsSetupProgress(VpsSetupProgress.of(
                VpsSetupProgress.Stage.AUDIT, 0, getString(R.string.vps_setup_wait_ssh)));
    }

    private void showVpsAutoSetupDialog() {
        showVpsAutoSetupDialog(false);
    }

    private void showVpsAutoSetupDialog(boolean updateExistingRelay) {
        if (vpsSetupRunning) {
            Toast.makeText(this, R.string.vps_setup_running, Toast.LENGTH_SHORT).show();
            return;
        }
        showVpsSetupWaitingForSsh();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(4), dp(18), 0);

        VpsRelayConfig selectedRelay = activeVpsRelayConfig();
        VpsOwnerRecord savedOwner = new VpsOwnerStore(this).forRelay(selectedRelay);
        EditText sshHost = dialogField(R.string.vps_setup_ssh_host,
                savedOwner == null ? valueOrDefault(etVpsRelayHost, "") : savedOwner.sshHost(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText sshPort = dialogField(R.string.vps_setup_ssh_port,
                String.valueOf(savedOwner == null ? 22 : savedOwner.sshPort()),
                InputType.TYPE_CLASS_NUMBER);
        EditText sshUser = dialogField(R.string.vps_setup_ssh_user,
                savedOwner == null ? "root" : savedOwner.sshUser(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        EditText sshPassword = dialogField(R.string.vps_setup_ssh_password,
                savedOwner == null ? "" : savedOwner.sshPassword(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String ownerAdminToken = savedOwner != null && savedOwner.canManage()
                ? savedOwner.adminToken() : generateAdminToken();
        CheckBox rememberPassword = new CheckBox(this);
        rememberPassword.setText(R.string.vps_setup_remember_credentials);
        rememberPassword.setTextColor(getColorValue(R.color.text_primary));
        rememberPassword.setTextSize(14f);
        rememberPassword.setChecked(savedOwner == null || !savedOwner.sshPassword().isEmpty());
        String initialRelayHost = valueOrDefault(etVpsRelayHost, "");
        EditText relayHost = dialogField(R.string.vps_setup_relay_host,
                initialRelayHost, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText relayPort = dialogField(R.string.vps_setup_relay_port,
                "18080", InputType.TYPE_CLASS_NUMBER);
        CheckBox relayTls = new CheckBox(this);
        relayTls.setText(R.string.vps_setup_tls_domain);
        relayTls.setTextColor(getColorValue(R.color.text_primary));
        relayTls.setTextSize(14f);
        relayTls.setChecked(VpsSetupUiPolicy.initialTlsChecked(
                initialRelayHost,
                cbVpsRelayTls != null && cbVpsRelayTls.isChecked(),
                intOrDefault(etVpsRelayPort, 0)));
        if (relayTls.isChecked()) relayPort.setText("443");
        TextView tlsNote = new TextView(this);
        tlsNote.setText(R.string.vps_setup_tls_domain_note);
        tlsNote.setTextColor(getColorValue(R.color.text_secondary));
        tlsNote.setTextSize(12f);
        relayTls.setOnCheckedChangeListener((button, checked) -> {
            if (checked) relayPort.setText("443");
            else if ("443".equals(relayPort.getText().toString().trim())) relayPort.setText("18080");
        });

        form.addView(sshHost);
        form.addView(sshPort);
        form.addView(sshUser);
        form.addView(sshPassword);
        form.addView(rememberPassword);
        form.addView(relayHost);
        form.addView(relayPort);
        form.addView(relayTls);
        form.addView(tlsNote);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(updateExistingRelay ? R.string.vps_relay_update_server : R.string.vps_setup_title)
                .setView(form)
                .setPositiveButton(updateExistingRelay ? R.string.vps_relay_update_server : R.string.vps_relay_auto_setup, (buttonDialog, which) ->
                        startVpsAutoSetup(
                                sshHost.getText().toString(),
                                intOrDefault(sshPort, 22),
                                sshUser.getText().toString(),
                                sshPassword.getText().toString(),
                                relayHost.getText().toString(),
                                 intOrDefault(relayPort, 18080),
                                 relayTls.isChecked(), ownerAdminToken,
                                 rememberPassword.isChecked(),
                                 updateExistingRelay))
                .setNeutralButton(R.string.vps_setup_forget_ssh_key, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> confirmForgetVpsSshKey(
                        sshHost.getText().toString(), intOrDefault(sshPort, 22))));
        dialog.show();
    }

    private EditText dialogField(int hintRes, String value, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hintRes);
        editText.setText(value == null ? "" : value);
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setTextColor(getColorValue(R.color.text_primary));
        editText.setHintTextColor(getColorValue(R.color.text_hint));
        editText.setTextSize(14f);
        editText.setMinHeight(dp(52));
        editText.setBackgroundResource(R.drawable.edit_bg);
        editText.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, 0);
        editText.setLayoutParams(params);
        return editText;
    }

    private void startVpsAutoSetup(String sshHost, int sshPort, String sshUser,
                                   String sshPassword, String relayHost, int relayPort,
                                   boolean relayTls, String adminToken,
                                   boolean rememberSshPassword, boolean updateExistingRelay) {
        if ((relayHost == null || relayHost.trim().isEmpty()) && relayTls) {
            discoverVpsDomainsThenStart(sshHost, sshPort, sshUser, sshPassword,
                    relayPort, adminToken, rememberSshPassword, updateExistingRelay);
            return;
        }
        String profileKey = vpsRelayProfileKeyForUi();
        String token = valueOrDefault(etVpsRelayToken, "");
        if (token.isEmpty()) token = generateRelayToken();
        boolean tlsDomain = VpsSetupUiPolicy.useTlsDomain(relayHost, relayTls);
        int publicRelayPort = VpsSetupUiPolicy.effectiveRelayPort(relayHost, relayPort, relayTls);
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost(sshHost)
                .sshPort(sshPort)
                .sshUser(sshUser)
                .sshPassword(sshPassword)
                .relayName(valueOrDefault(etVpsRelayName, "VPS Relay"))
                .relayHost(relayHost)
                .relayPort(publicRelayPort)
                .relayTls(tlsDomain)
                .relayPath(valueOrDefault(etVpsRelayPath, "/apiws"))
                .relayToken(token)
                .adminToken(adminToken)
                .releaseVersion(VpsSetupScripts.RELAY_VERSION)
                .profileKey(profileKey)
                .updateExistingRelay(updateExistingRelay)
                .rememberSshPassword(rememberSshPassword)
                .build();
        if (!request.isValid()) {
            Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show();
            return;
        }
        vpsSetupRunning = true;
        setEnabled(btnVpsRelayAutoSetup, false);
        Map<Integer, String> dcRules = currentDcRulesOrDefault();
        new Thread(() -> runVpsAutoSetup(request, dcRules), "tg-vps-auto-setup").start();
    }

    private void discoverVpsDomainsThenStart(String sshHost, int sshPort, String sshUser,
                                             String sshPassword, int relayPort,
                                             String adminToken, boolean rememberSshPassword,
                                             boolean updateExistingRelay) {
        VpsSshCredentials credentials = new VpsSshCredentials(sshHost, sshPort, sshUser, sshPassword);
        if (!credentials.isValid()) {
            Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show();
            return;
        }
        vpsSetupRunning = true;
        setEnabled(btnVpsRelayAutoSetup, false);
        applyVpsSetupProgress(VpsSetupProgress.of(
                VpsSetupProgress.Stage.AUDIT, 5, getString(R.string.vps_domain_discovery_running)));
        VpsSetupRequest auditRequest = VpsSetupRequest.builder()
                .sshHost(sshHost)
                .sshPort(sshPort)
                .sshUser(sshUser)
                .sshPassword(sshPassword)
                .relayHost("")
                .relayPort(443)
                .relayTls(true)
                .relayPath(valueOrDefault(etVpsRelayPath, "/apiws"))
                .relayToken("audit")
                .adminToken(adminToken)
                .releaseVersion(VpsSetupScripts.RELAY_VERSION)
                .rememberSshPassword(rememberSshPassword)
                .build();
        new Thread(() -> {
            try {
                String auditText = createVpsSshClient().execute(credentials,
                        VpsSetupProgress.Stage.AUDIT, "sh -s",
                        VpsSetupScripts.audit(auditRequest), VPS_DOMAIN_DISCOVERY_TIMEOUT_MS);
                List<String> domains = VpsSetupAudit.parse(auditText).discoveredDomains();
                handler.post(() -> {
                    vpsSetupRunning = false;
                    setEnabled(btnVpsRelayAutoSetup, true);
                    applyVpsSetupProgress(VpsSetupProgress.of(
                            VpsSetupProgress.Stage.PLAN, 25,
                            getString(R.string.vps_domain_discovery_done, domains.size())));
                    showVpsDomainChoice(domains, sshHost, sshPort, sshUser, sshPassword,
                            relayPort, adminToken, rememberSshPassword, updateExistingRelay);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                handler.post(() -> {
                    vpsSetupRunning = false;
                    setEnabled(btnVpsRelayAutoSetup, true);
                    applyVpsSetupProgress(VpsSetupProgress.of(
                            VpsSetupProgress.Stage.ROLLBACK, 0,
                            getString(R.string.vps_setup_failed, firstLine(message))));
                    showVpsSetupErrorDialog(message);
                });
            }
        }, "tg-vps-domain-discovery").start();
    }

    private void showVpsDomainChoice(List<String> domains, String sshHost, int sshPort,
                                     String sshUser, String sshPassword, int relayPort,
                                     String adminToken, boolean rememberSshPassword,
                                     boolean updateExistingRelay) {
        if (domains == null || domains.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.vps_domain_discovery_title)
                    .setMessage(R.string.vps_domain_discovery_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String[] items = domains.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_domain_discovery_title)
                .setItems(items, (dialog, which) -> startVpsAutoSetup(
                        sshHost,
                        sshPort,
                        sshUser,
                        sshPassword,
                        items[which],
                         443,
                         true, adminToken, rememberSshPassword,
                         updateExistingRelay))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runVpsAutoSetup(VpsSetupRequest request, Map<Integer, String> dcRules) {
        VpsOwnerStore ownerStore = new VpsOwnerStore(this);
        VpsAutoSetupWizard wizard = new VpsAutoSetupWizard(
                createVpsSshClient(),
                (config, rules) -> new VpsRelayClient().check(config, rules),
                VpsRelayStore.fromContext(this),
                dcRules);
        try {
            // Persist the only copy of the admin token and the optional SSH password before
            // the wizard is allowed to mutate the server. A successful remote install must
            // never leave the creator without owner access because the local secure write
            // happened afterwards and failed.
            if (!ownerStore.saveSetup(request, request.relayConfig())) {
                throw new VpsSetupException(getString(R.string.vps_owner_pre_save_failed));
            }
            VpsRelayConfig relay = wizard.run(request, new VpsAutoSetupWizard.Listener() {
                @Override public void onProgress(VpsSetupProgress progress) {
                    handler.post(() -> applyVpsSetupProgress(progress));
                }

                @Override public boolean onPlan(VpsSetupPlan plan) {
                    return confirmVpsSetupPlan(plan);
                }
            });
            boolean ownerSaved = ownerStore.saveSetup(request, relay);
            if (!ownerSaved) {
                DiagnosticsLog.record("VPS owner credentials could not be persisted");
            }
            handler.post(() -> {
                fillVpsRelayForm(relay);
                if (!saveVpsRelaySettings(relay)) return;
                refreshVpsRelaySelector();
                refreshConnectionFields();
                Toast.makeText(MainActivity.this, R.string.saved, Toast.LENGTH_SHORT).show();
                if (!ownerSaved) {
                    Toast.makeText(MainActivity.this, R.string.vps_owner_save_failed,
                            Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            handler.post(() -> {
                int percent = progressVpsSetup == null ? 0 : progressVpsSetup.getProgress();
                String shortMessage = firstLine(message);
                applyVpsSetupProgress(VpsSetupProgress.of(
                        VpsSetupProgress.Stage.ROLLBACK, percent,
                        getString(R.string.vps_setup_failed, shortMessage)));
                showVpsSetupErrorDialog(message);
            });
        } finally {
            handler.post(() -> {
                vpsSetupRunning = false;
                setEnabled(btnVpsRelayAutoSetup, true);
            });
        }
    }

    private boolean confirmVpsSetupPlan(VpsSetupPlan plan) {
        AtomicBoolean approved = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_setup_plan_title)
                .setMessage(plan == null ? "" : plan.summary())
                .setPositiveButton(R.string.vps_setup_continue, (dialog, which) -> {
                    approved.set(true);
                    latch.countDown();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> latch.countDown())
                .setOnCancelListener(dialog -> latch.countDown())
                .show());
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return approved.get();
    }

    private void showVpsSetupErrorDialog(String message) {
        TextView text = new TextView(this);
        text.setText(message == null || message.trim().isEmpty()
                ? getString(R.string.test_failed)
                : message.trim());
        text.setTextColor(getColorValue(R.color.text_primary));
        text.setTextSize(13f);
        text.setTextIsSelectable(true);
        text.setPadding(dp(18), dp(10), dp(18), dp(6));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_setup_error_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static String firstLine(String message) {
        String value = message == null ? "" : message.trim();
        int newline = value.indexOf('\n');
        if (newline >= 0) value = value.substring(0, newline).trim();
        return value.isEmpty() ? "unknown" : value;
    }

    private Map<Integer, String> currentDcRulesOrDefault() {
        try {
            return MtProtoConfig.parseDcRules(etDcRules.getText().toString());
        } catch (Exception ignored) {
            return MtProtoConfig.parseDcRules(MtProtoConfig.DEFAULT_DC_RULES);
        }
    }

    private static String generateRelayToken() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder("tgpr_");
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String generateAdminToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder("tgpa_");
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private void updateVpsRelayFieldsEnabled() {
        boolean enabled = cbVpsRelayEnabled != null && cbVpsRelayEnabled.isChecked();
        setEnabled(etVpsRelayName, enabled);
        setEnabled(etVpsRelayHost, enabled);
        setEnabled(etVpsRelayPort, enabled);
        setEnabled(etVpsRelayPath, enabled);
        setEnabled(etVpsRelayToken, enabled);
        setEnabled(cbVpsRelayTls, enabled);
        setEnabled(cbVpsRelayBindProfile, enabled);
        setEnabled(btnVpsRelayTest, enabled);
        setEnabled(btnVpsRelayAutoSetup, !vpsSetupRunning);
        VpsRelayConfig current = currentVpsRelayConfig();
        VpsOwnerRecord owner = current.hasValidEndpoint()
                ? new VpsOwnerStore(this).forRelay(current) : null;
        boolean canManage = owner != null && owner.canManage();
        setVisible(btnVpsOwnerManage, canManage);
        setEnabled(btnVpsOwnerManage, canManage);
    }

    private boolean saveVpsRelaySettings(VpsRelayConfig relay) {
        return saveVpsRelaySettings(relay, "");
    }

    private boolean saveVpsRelaySettings(VpsRelayConfig relay, String preferredRelayId) {
        SharedPreferences.Editor editor = prefs.edit();
        if (!updateStoredVpsRelaySelection(editor, relay, preferredRelayId)) {
            DiagnosticsLog.record("secure VPS Relay staging failed");
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
            return false;
        }
        putVpsRelaySettings(editor, relay);
        boolean committed = editor.commit();
        if (!committed) {
            DiagnosticsLog.record("VPS Relay settings commit failed");
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
        }
        return committed;
    }

    private boolean updateStoredVpsRelaySelection(SharedPreferences.Editor editor,
                                                  VpsRelayConfig relay) {
        return updateStoredVpsRelaySelection(editor, relay, "");
    }

    private boolean updateStoredVpsRelaySelection(SharedPreferences.Editor editor,
                                                  VpsRelayConfig relay,
                                                  String preferredRelayId) {
        if (relay == null || editor == null) return false;
        VpsRelayStore store = VpsRelayStore.fromContext(this);
        String profileKey = relay.profileKey().isEmpty() ? "" : relay.profileKey();
        if (relay.isUsable()) {
            String id = preferredRelayId == null ? "" : preferredRelayId.trim();
            return id.isEmpty()
                    ? store.saveRelayInto(relay, profileKey, editor) != null
                    : store.updateRelayInto(id, relay, profileKey, editor) != null;
        }
        return store.bindProfileInto(profileKey, "", editor);
    }

    private void putVpsRelaySettings(SharedPreferences.Editor editor, VpsRelayConfig relay) {
        if (editor == null || relay == null) return;
        editor.putBoolean("vps_relay_enabled", relay.isEnabled())
                .putString("vps_relay_name", relay.name())
                .putString("vps_relay_host", relay.host())
                .putInt("vps_relay_port", relay.port())
                .putBoolean("vps_relay_tls", relay.tls())
                .putString("vps_relay_path", relay.path())
                .remove("vps_relay_token")
                .putString("vps_relay_profile_key", relay.profileKey());
    }

    private void setEnabled(View view, boolean enabled) {
        if (view != null) {
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.55f);
        }
    }

    private void setExpandableSection(View content, Button toggle, boolean visible,
                                      int showText, int hideText) {
        if (content != null) content.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (toggle != null) {
            toggle.setText(visible ? hideText : showText);
            toggle.setContentDescription(getString(visible ? hideText : showText));
        }
    }

    private RouteEngine.Settings routeSettingsFromControls() {
        Map<Integer, String> dcRules;
        try {
            dcRules = MtProtoConfig.parseDcRules(etDcRules.getText().toString());
        } catch (Exception ignored) {
            dcRules = MtProtoConfig.parseDcRules(MtProtoConfig.DEFAULT_DC_RULES);
        }
        NetworkProfileRecord profileRecord = currentActiveProfileRecord();
        RouteEngine.Settings.Builder builder = RouteEngine.Settings.builder()
                .networkProfile(profileRecord.profile())
                .routePreference(profileRecord.routePreference())
                .routeAvailability(profileRecord.routeAvailability())
                .cfMode(selectedCfProxyMode())
                .dcRedirects(dcRules)
                .workerDomains(splitDomains(etWorkerDomains.getText().toString()));
        List<String> cfDomains = cbCfCustomDomain.isChecked()
                ? splitDomains(etCfDomains.getText().toString())
                : FlowsealCfDomains.defaults();
        if (cbCfCustomDomain.isChecked()) {
            builder.customCfDomains(cfDomains);
        } else {
            builder.publicCfDomains(cfDomains);
        }
        builder.vpsRelays(VpsRelayStore.fromContext(this).relayPool(profileRecord.key()));
        return builder.build();
    }

    private NetworkProfileRecord currentProfileRecord() {
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        return store.profileOrCreate(NetworkProfileIdentifier.current(this), System.currentTimeMillis());
    }

    private NetworkProfileRecord currentActiveProfileRecord() {
        return currentProfileRecord();
    }

    private void createManualProfile() {
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        NetworkProfileRecord record = store.createManualProfile(
                getString(R.string.manual_profile_default), System.currentTimeMillis());
        displayedProfileKey = record.key();
        showProfileRecord(record);
        refreshProfileSelector(store, activeProfileKey, displayedProfileKey);
        refreshProfilesList(store, activeProfileKey);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
    }

    private void saveDisplayedProfile() {
        if (etProfileName == null || spRoutePreference == null) return;
        NetworkProfileStore current = NetworkProfileStore.fromPreferences(prefs);
        String key = selectedProfileKey(current);
        NetworkProfileStore staged = NetworkProfileStore.inMemory(current.exportProfiles());
        staged.renameProfile(key, etProfileName.getText().toString());
        staged.setRoutePreference(key, selectedRoutePreference());
        staged.setRouteAvailability(key, routeAvailabilityFromControls());
        if (!prefs.edit().putString(NetworkProfileStore.KEY_PROFILES,
                staged.exportProfiles()).commit()) {
            NetworkProfileRecord persisted = current.profile(key);
            if (persisted != null) showProfileRecord(persisted);
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        NetworkProfileRecord record = store.profile(key);
        if (record != null) showProfileRecord(record);
        refreshProfileSelector(store, activeProfileKey, key);
        refreshProfilesList(store, activeProfileKey);
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
        refreshConnectionFields();
    }

    private void saveDisplayedRoutePreference() {
        if (spRoutePreference == null) return;
        NetworkProfileStore current = NetworkProfileStore.fromPreferences(prefs);
        String key = selectedProfileKey(current);
        NetworkProfileStore staged = NetworkProfileStore.inMemory(current.exportProfiles());
        staged.setRoutePreference(key, selectedRoutePreference());
        if (!prefs.edit().putString(NetworkProfileStore.KEY_PROFILES,
                staged.exportProfiles()).commit()) {
            NetworkProfileRecord persisted = current.profile(key);
            if (persisted != null) showProfileRecord(persisted);
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        refreshProfilesList(store, activeProfileKey);
    }

    private RouteAvailability routeAvailabilityFromControls() {
        return RouteAvailability.of(
                cbRouteDirect != null && cbRouteDirect.isChecked(),
                cbRouteRelay != null && cbRouteRelay.isChecked(),
                cbRouteWorker != null && cbRouteWorker.isChecked(),
                cbRouteCustomCf != null && cbRouteCustomCf.isChecked(),
                cbRoutePublicCf != null && cbRoutePublicCf.isChecked());
    }

    private void applyRouteAvailabilityToControls(RouteAvailability availability) {
        RouteAvailability value = availability == null ? RouteAvailability.all() : availability;
        if (cbRouteDirect != null) cbRouteDirect.setChecked(value.isEnabled(RouteType.DIRECT_WS));
        if (cbRouteRelay != null) cbRouteRelay.setChecked(value.isEnabled(RouteType.VPS_RELAY));
        if (cbRouteWorker != null) cbRouteWorker.setChecked(value.isEnabled(RouteType.WORKER));
        if (cbRouteCustomCf != null) cbRouteCustomCf.setChecked(
                value.isEnabled(RouteType.CUSTOM_CLOUDFLARE));
        if (cbRoutePublicCf != null) cbRoutePublicCf.setChecked(
                value.isEnabled(RouteType.PUBLIC_CLOUDFLARE));
    }

    private void onRouteAvailabilityChanged(CheckBox source) {
        if (!profileControlReady) return;
        if (!routeAvailabilityFromControls().hasAny()) {
            profileControlReady = false;
            source.setChecked(true);
            profileControlReady = true;
            Toast.makeText(this, R.string.route_none_warning, Toast.LENGTH_LONG).show();
            return;
        }
        saveDisplayedRouteAvailability();
        refreshConnectionFields();
    }

    private void saveDisplayedRouteAvailability() {
        NetworkProfileStore current = NetworkProfileStore.fromPreferences(prefs);
        String key = selectedProfileKey(current);
        NetworkProfileStore staged = NetworkProfileStore.inMemory(current.exportProfiles());
        staged.setRouteAvailability(key, routeAvailabilityFromControls());
        if (!prefs.edit().putString(NetworkProfileStore.KEY_PROFILES,
                staged.exportProfiles()).commit()) {
            NetworkProfileRecord persisted = current.profile(key);
            if (persisted != null) showProfileRecord(persisted);
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        refreshProfilesList(NetworkProfileStore.fromPreferences(prefs), activeProfileKey);
    }

    private String selectedProfileKey(NetworkProfileStore store) {
        String key = displayedProfileKey;
        if (key == null || key.isEmpty() || store.profile(key) == null) {
            key = store.profileOrCreate(NetworkProfileIdentifier.current(this),
                    System.currentTimeMillis()).key();
            displayedProfileKey = key;
        }
        return key;
    }

    private void confirmDeleteDisplayedProfile() {
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        String key = displayedProfileKey;
        if (key == null || key.isEmpty() || store.profile(key) == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_profile_title)
                .setMessage(R.string.delete_profile_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_profile, (dialog, which) -> deleteDisplayedProfile(key))
                .show();
    }

    private void deleteDisplayedProfile(String key) {
        NetworkProfileStore persisted = NetworkProfileStore.fromPreferences(prefs);
        if (persisted.profile(key) == null) return;
        NetworkProfileStore staged = NetworkProfileStore.inMemory(persisted.exportProfiles());
        if (!staged.deleteProfile(key)) return;
        SharedPreferences.Editor editor = prefs.edit()
                .putString(NetworkProfileStore.KEY_PROFILES, staged.exportProfiles())
                .remove(NetworkProfileStore.statsKeyForProfileKey(key));
        VpsRelayStore relayStore = VpsRelayStore.fromContext(this);
        if (!relayStore.bindProfileInto(key, "", editor)) {
            DiagnosticsLog.record("secure Relay profile unbind staging failed " + key);
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (!editor.commit()) {
            DiagnosticsLog.record("profile delete commit failed " + key);
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        displayedProfileKey = "";
        refreshProfileControls(true);
        Toast.makeText(this, R.string.profile_deleted, Toast.LENGTH_SHORT).show();
        refreshConnectionFields();
    }

    private static String firstDomain(String text, String fallback) {
        String normalized = text == null ? "" : text.replace(',', ' ').replace(';', ' ').trim();
        if (normalized.isEmpty()) return fallback == null ? "" : fallback;
        return normalized.split("\\s+")[0];
    }

    private static List<String> splitDomains(String text) {
        ArrayList<String> result = new ArrayList<>();
        String normalized = text == null ? "" : text.replace(',', ' ').replace(';', ' ').trim();
        if (normalized.isEmpty()) return result;
        for (String domain : normalized.split("\\s+")) {
            String item = domain.trim().toLowerCase(Locale.US);
            if (!item.isEmpty() && !result.contains(item)) result.add(item);
        }
        return result;
    }

    private static int countImportEntries(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        int count = 0;
        for (String line : value.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) count++;
        }
        return count;
    }

    private static String valueOrDefault(EditText editText, String fallback) {
        String value = editText.getText().toString().trim();
        return value.isEmpty() ? fallback : value;
    }

    private static int intOrDefault(EditText editText, int fallback) {
        try {
            return Integer.parseInt(editText.getText().toString().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean requestNetworkIdentityPermissions() {
        return requestNetworkIdentityPermissions(false);
    }

    private boolean requestNetworkIdentityPermissions(boolean userInitiated) {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 12+ requires COARSE and FINE to be requested together when precise
            // location is needed. Some Android 10/11 vendor builds also grant FINE without
            // making COARSE/WIFI_SCAN usable, which leaves WifiInfo permanently redacted.
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (!permissions.isEmpty()) {
            BackgroundReliabilityStore reliability = new BackgroundReliabilityStore(this);
            if (!userInitiated
                    && !reliability.shouldRequestNetworkIdentityPermissions(BuildConfig.VERSION_CODE)) {
                return false;
            }
            if (userInitiated && reliability.hasRequestedNetworkIdentityPermissions()) {
                boolean canAskAgain = false;
                for (String permission : permissions) {
                    if (androidx.core.app.ActivityCompat
                            .shouldShowRequestPermissionRationale(this, permission)) {
                        canAskAgain = true;
                        break;
                    }
                }
                if (!canAskAgain) {
                    BackgroundExecutionAssistant.openAppSettings(this);
                    return true;
                }
            }
            reliability.markNetworkIdentityPermissionsRequested(BuildConfig.VERSION_CODE);
            androidx.core.app.ActivityCompat.requestPermissions(
                    this, permissions.toArray(new String[0]),
                    REQUEST_NETWORK_IDENTITY_PERMISSIONS);
            return true;
        }
        return false;
    }

    private void showBackgroundSetupOnce() {
        if (backgroundSetupDialogShowing || isFinishing()
                || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        BackgroundReliabilityStore store = new BackgroundReliabilityStore(this);
        if (!store.shouldPrompt(BuildConfig.VERSION_CODE)) return;
        showBackgroundSetupDialog();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            boolean requestedBefore = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false);
            if (requestedBefore && !androidx.core.app.ActivityCompat
                    .shouldShowRequestPermissionRationale(this,
                            Manifest.permission.POST_NOTIFICATIONS)) {
                BackgroundExecutionAssistant.openNotificationSettings(this);
                return;
            }
            prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply();
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION_PERMISSION);
            return;
        }
        BackgroundExecutionAssistant.openNotificationSettings(this);
    }

    private void showBackgroundSetupDialog() {
        if (backgroundSetupDialog != null && backgroundSetupDialog.isShowing()) {
            refreshBackgroundSetupDialog();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), 0, dp(14), dp(4));

        TextView text = new TextView(this);
        text.setText(R.string.background_setup_message);
        text.setTextColor(getColorValue(R.color.text_secondary));
        text.setTextSize(12.5f);
        text.setLineSpacing(0f, 1.04f);
        layout.addView(text);

        backgroundSetupStatusList = new LinearLayout(this);
        backgroundSetupStatusList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(7);
        layout.addView(backgroundSetupStatusList, listParams);

        BackgroundReliabilityStore reliability = new BackgroundReliabilityStore(this);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.background_setup_title)
                .setView(layout)
                .setPositiveButton(R.string.background_check_ready, null)
                .setNegativeButton(R.string.background_not_now, null)
                .create();
        backgroundSetupDialog = dialog;
        backgroundSetupDialogShowing = true;
        dialog.setOnDismissListener(ignored -> {
            backgroundSetupDialogShowing = false;
            if (backgroundSetupDialog == dialog) {
                backgroundSetupDialog = null;
                backgroundSetupStatusList = null;
                backgroundAutostartSettingsOpened = false;
            }
        });
        dialog.setOnShowListener(ignored -> {
            refreshBackgroundSetupDialog();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                reliability.markPrompted(BuildConfig.VERSION_CODE);
                refreshBackgroundExecutionStatus();
                refreshBackgroundSetupDialog();
                if (!reliability.status().ready()) {
                    Toast.makeText(this, R.string.background_missing_warning,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, R.string.background_all_ready, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                reliability.markPrompted(BuildConfig.VERSION_CODE);
                refreshBackgroundExecutionStatus();
                if (!reliability.status().ready()) {
                    Toast.makeText(this, R.string.background_missing_warning,
                            Toast.LENGTH_LONG).show();
                }
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void refreshBackgroundSetupDialog() {
        if (backgroundSetupStatusList == null) return;
        BackgroundReliabilityStore reliability = new BackgroundReliabilityStore(this);
        BackgroundReliabilityStore.Status status = reliability.status();
        boolean bootEnabled = status.bootEnabled;
        boolean manualAutostart = status.autostartState
                == BackgroundExecutionAssistant.AutostartState.UNKNOWN
                && BackgroundExecutionAssistant.requiresManualAutostartConfirmation();
        boolean autostartConfirmed = status.autostartConfirmed;

        backgroundSetupStatusList.removeAllViews();
        addBackgroundStatusRow(R.string.background_condition_boot, bootEnabled,
                R.string.background_condition_enabled, R.string.background_condition_disabled,
                bootEnabled ? null : () -> {
                    prefs.edit().putBoolean("autostart_boot", true).commit();
                    if (cbAutostartBoot != null) cbAutostartBoot.setChecked(true);
                    refreshBackgroundExecutionStatus();
                    refreshBackgroundSetupDialog();
                });
        addBackgroundStatusRow(R.string.background_condition_battery,
                status.batteryUnrestricted, R.string.background_battery_ready_short,
                R.string.background_battery_restricted_short,
                status.batteryUnrestricted ? null : () ->
                        BackgroundExecutionAssistant.requestBatteryOptimizationExemption(this));
        int autostartReadyDetail = status.autostartState
                == BackgroundExecutionAssistant.AutostartState.ALLOWED
                ? R.string.background_condition_system_allowed
                : (manualAutostart ? R.string.background_condition_confirmed
                : R.string.background_condition_not_required);
        int autostartMissingDetail = status.autostartState
                == BackgroundExecutionAssistant.AutostartState.DENIED
                ? R.string.background_condition_system_disabled
                : (manualAutostart && backgroundAutostartSettingsOpened
                ? R.string.background_condition_tap_to_confirm
                : R.string.background_condition_not_confirmed);
        addBackgroundStatusRow(R.string.background_condition_autostart, autostartConfirmed,
                autostartReadyDetail, autostartMissingDetail,
                autostartConfirmed ? null : () -> {
                    BackgroundReliabilityStore.Status current = reliability.status();
                    boolean unknown = current.autostartState
                            == BackgroundExecutionAssistant.AutostartState.UNKNOWN;
                    if (unknown && backgroundAutostartSettingsOpened) {
                        reliability.setAutostartConfirmed(true);
                        backgroundAutostartSettingsOpened = false;
                        refreshBackgroundExecutionStatus();
                        refreshBackgroundSetupDialog();
                        return;
                    }
                    backgroundAutostartSettingsOpened =
                            BackgroundExecutionAssistant.openManufacturerAutostart(this);
                    if (!backgroundAutostartSettingsOpened) {
                        Toast.makeText(this, R.string.background_settings_unavailable,
                                Toast.LENGTH_LONG).show();
                    }
                });
        addBackgroundStatusRow(R.string.background_condition_notifications,
                status.notificationsAllowed, R.string.background_condition_allowed,
                R.string.background_condition_denied,
                status.notificationsAllowed ? null : this::requestNotificationPermission);
        addBackgroundStatusRow(R.string.background_condition_network_identity,
                status.networkIdentityAllowed, R.string.background_condition_allowed,
                R.string.background_condition_denied,
                status.networkIdentityAllowed ? null : () ->
                        requestNetworkIdentityPermissions(true));
        addBackgroundStatusRow(R.string.background_condition_location,
                status.locationEnabled, R.string.background_condition_enabled,
                R.string.background_condition_disabled,
                status.locationEnabled ? null : () ->
                        BackgroundExecutionAssistant.openLocationSettings(this));
    }

    private void addBackgroundStatusRow(int titleRes, boolean ready,
                                        int readyDetailRes, int missingDetailRes,
                                        Runnable missingAction) {
        if (backgroundSetupStatusList == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(11), dp(6), dp(9), dp(6));
        row.setBackgroundResource(ready ? R.drawable.background_status_ready_row
                : R.drawable.background_status_missing_row);

        ImageView icon = new ImageView(this);
        icon.setImageResource(ready ? R.drawable.ic_status_check : R.drawable.ic_status_error);
        icon.setImageTintList(ColorStateList.valueOf(getColorValue(
                ready ? R.color.green : R.color.red)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconParams.rightMargin = dp(9);
        row.addView(icon, iconParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextColor(getColorValue(R.color.text_primary));
        title.setTextSize(12.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        copy.addView(title);

        int detailRes = ready ? readyDetailRes : missingDetailRes;
        TextView detail = new TextView(this);
        detail.setText(detailRes);
        detail.setTextColor(getColorValue(ready ? R.color.green : R.color.red));
        detail.setTextSize(10.5f);
        copy.addView(detail);
        row.addView(copy, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!ready && missingAction != null) {
            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.ic_chevron_right);
            chevron.setImageTintList(ColorStateList.valueOf(getColorValue(R.color.red)));
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(view -> missingAction.run());
        }
        row.setContentDescription(getString(titleRes) + ". " + getString(detailRes)
                + (!ready && missingAction != null
                ? ". " + getString(R.string.background_condition_tap_action) : ""));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(3);
        backgroundSetupStatusList.addView(row, rowParams);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NETWORK_IDENTITY_PERMISSIONS && handler != null) {
            // Never compete with Android's runtime-permission window. The reliability dialog is
            // shown only after the system has fully dismissed it, regardless of allow/deny.
            refreshProfileControls(true);
            refreshConnectionFields();
            ProxyService.refreshNetworkProfileIfRunning();
            refreshBackgroundSetupDialog();
            handler.postDelayed(this::showBackgroundSetupOnce, 350L);
        } else if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            refreshBackgroundExecutionStatus();
            refreshBackgroundSetupDialog();
        }
    }

    private JschVpsSshClient createVpsSshClient() {
        return new JschVpsSshClient(new File(getFilesDir(), VPS_SSH_KNOWN_HOSTS_FILE),
                this::confirmFirstVpsHostKey);
    }

    private boolean confirmFirstVpsHostKey(String host, String algorithm, String fingerprint) {
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(false);
        handler.post(() -> {
            if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) {
                latch.countDown();
                return;
            }
            AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.vps_setup_ssh_key_confirm_title)
                    .setMessage(getString(R.string.vps_setup_ssh_key_confirm_message,
                            host, algorithm, fingerprint))
                    .setPositiveButton(R.string.vps_setup_ssh_key_trust,
                            (ignored, which) -> {
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

    private void confirmForgetVpsSshKey(String host, int port) {
        String normalizedHost = host == null ? "" : host.trim();
        if (normalizedHost.isEmpty()) {
            Toast.makeText(this, R.string.vps_setup_ssh_host_required, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vps_setup_forget_ssh_key)
                .setMessage(getString(R.string.vps_setup_forget_ssh_key_confirm,
                        normalizedHost, port))
                .setPositiveButton(R.string.vps_setup_forget_ssh_key, (dialog, which) -> {
                    try {
                        boolean removed = createVpsSshClient().forgetHost(normalizedHost, port);
                        Toast.makeText(this, removed
                                        ? R.string.vps_setup_ssh_key_forgotten
                                        : R.string.vps_setup_ssh_key_not_found,
                                Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.vps_setup_ssh_key_forget_failed,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshBackgroundExecutionStatus() {
        if (tvBackgroundStatus == null || prefs == null) return;
        BackgroundReliabilityStore.Status reliability =
                new BackgroundReliabilityStore(this).status();
        boolean exempt = reliability.batteryUnrestricted;
        boolean desired = ProxyRunStateStore.fromPreferences(prefs).desiredRunning();
        boolean alive = ProxyService.getInstance() != null;
        int serviceStatus = alive ? R.string.background_service_alive
                : (desired ? R.string.background_service_waiting
                : R.string.background_service_stopped);
        tvBackgroundStatus.setText(getString(R.string.background_status_extended,
                getString(exempt ? R.string.background_battery_ready_short
                        : R.string.background_battery_restricted_short),
                getString(reliability.autostartConfirmed && reliability.bootEnabled
                        ? R.string.background_autostart_ready_short
                        : R.string.background_autostart_missing_short),
                getString(reliability.notificationsAllowed
                        ? R.string.background_notifications_ready_short
                        : R.string.background_notifications_missing_short),
                getString(reliability.networkIdentityAllowed && reliability.locationEnabled
                        ? R.string.background_wifi_ready_short
                        : R.string.background_wifi_missing_short),
                getString(desired ? R.string.background_desired_on
                        : R.string.background_desired_off),
                getString(serviceStatus)));
        tvBackgroundStatus.setTextColor(getColorValue(
                reliability.ready() ? R.color.text_secondary : R.color.red));
    }

    private boolean hasMissingNetworkIdentityPermission() {
        return !BackgroundExecutionAssistant.hasNetworkIdentityPermissions(this);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshBackgroundExecutionStatus();
        refreshBackgroundSetupDialog();
        refreshProfileControls(true);
        ProxyService.refreshNetworkProfileIfRunning();
        if (statsUpdater != null) handler.post(statsUpdater);
        if (pendingInstallAfterPermission && GithubReleaseUpdater.canInstallPackages(this)) {
            pendingInstallAfterPermission = false;
            installLastRelease();
        }
        if (pendingInstallVersion != null && !pendingInstallVersion.trim().isEmpty()) {
            handler.postDelayed(this::verifyPendingUpdateInstall, 800L);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (statsUpdater != null) handler.removeCallbacks(statsUpdater);
    }

    @Override public void onBackPressed() {
        if (diagnosticsScreen != null && diagnosticsScreen.getVisibility() == View.VISIBLE) {
            hideDiagnosticsScreen();
            return;
        }
        if (settingsScreen != null && settingsScreen.getVisibility() == View.VISIBLE) {
            closeSettingsSaving();
            return;
        }
        super.onBackPressed();
    }
}
