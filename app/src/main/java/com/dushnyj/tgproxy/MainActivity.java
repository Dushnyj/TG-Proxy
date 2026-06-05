package com.dushnyj.tgproxy;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.widget.LinearLayout;
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
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.FileProvider;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity {
    private static final String REPO_URL = "https://github.com/Dushnyj/TG-Proxy";
    private static final int REQUEST_IMPORT_FILE = 1101;
    private static final int REQUEST_INSTALL_UPDATE = 1102;
    private static final String STATE_SCREEN = "screen";
    private static final String STATE_SECTION = "settings_section";
    private static final String STATE_DIAGNOSTICS_RETURN = "diagnostics_return";
    private static final String SCREEN_MAIN = "main";
    private static final String SCREEN_SETTINGS = "settings";
    private static final String SCREEN_DIAGNOSTICS = "diagnostics";
    private static final int VPS_DOMAIN_DISCOVERY_TIMEOUT_MS = 30_000;

    private ImageButton btnStart;
    private Button btnStop, btnRegenerateSecret;
    private View btnOpenSettings, btnBackMain, btnOpenDiagnostics, btnOpenGithub, btnOpenTelegram, btnMainMenu;
    private Button btnTestCf, btnTestWorker;
    private Button btnVpsRelayTest;
    private Button btnVpsRelayNew, btnVpsRelaySave, btnVpsRelayAutoSetup;
    private View btnCfHelp, btnWorkerHelp;
    private Button btnCheckUpdate, btnOpenRelease, btnInstallUpdate;
    private Button btnCreateProfile, btnSaveProfile, btnDeleteProfile;
    private Button btnExportSafeProfile, btnExportVpsRelay, btnExportEncryptedProfile, btnImportSettings;
    private Button btnSettingsDiagnostics;
    private Button btnDiagnosticsSaveZip, btnDiagnosticsSaveTxt, btnDiagnosticsCopyShort;
    private Button btnDiagnosticsShare, btnDiagnosticsReset;
    private TextView tvStatus, tvAddress, tvRoute, tvCfDomain, tvPort, tvTgLink, tvPing, tvTraffic, tvUptime;
    private TextView tvMainProfile, tvQuality, tvConnections;
    private TextView tvActiveProfile, tvProfileKey, tvProfilesList;
    private TextView tvUpdateStatus, tvUpdateProgress, tvVersion;
    private TextView tvVpsSetupStatus;
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
    private ProgressBar progressUpdate;
    private ProgressBar progressVpsSetup;
    private Spinner spCfMode, spTheme, spLanguage, spProfileSelector, spRoutePreference, spVpsRelaySaved;
    private View mainScreen, settingsScreen, diagnosticsScreen, btnBackDiagnostics;
    private View navConnection, navSystem, navAbout;
    private View sectionConnection, sectionRoute, sectionProfiles, sectionOptimization, sectionVpsRelay;
    private View sectionImportExport, sectionDiagnosticsLogs, sectionBehavior;
    private View sectionInterface, sectionUpdates, sectionAdvanced, sectionAbout;

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
    private boolean vpsSetupRunning;
    private final AtomicBoolean vpsRelayVersionCheckRunning = new AtomicBoolean(false);
    private String vpsRelayUpdateDialogKey = "";
    private boolean diagnosticsReturnToSettings;
    private String activeProfileKey = "";
    private String displayedProfileKey = "";
    private final AutoPingGate autoPingGate = new AutoPingGate(MainUiState.AUTO_PING_INTERVAL_MS);
    private int lastMeasuredPingMs = -1;
    private long lastMeasuredPingAt;
    private String lastMeasuredPingKey = "";
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

        ensureDefaults();
        bindViews();
        loadSettings();
        setupActions();
        refreshConnectionFields();
        updateRunningState(ProxyService.getInstance() != null);
        requestPermissions();
        requestBatteryOptimizationHint();
        handleImportIntent(getIntent());
        restoreUiState(savedInstanceState);

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
        if (!prefs.contains("smart_sleep")) e.putBoolean("smart_sleep", TgRoutePolicy.DEFAULT_SMART_SLEEP);
        if (!prefs.contains("autostart_boot")) e.putBoolean("autostart_boot", false);
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
        btnVpsRelayTest = findViewById(R.id.btn_vps_relay_test);
        btnVpsRelayNew = findViewById(R.id.btn_vps_relay_new);
        btnVpsRelaySave = findViewById(R.id.btn_vps_relay_save);
        btnVpsRelayAutoSetup = findViewById(R.id.btn_vps_relay_auto_setup);
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
        btnSettingsDiagnostics = findViewById(R.id.btn_settings_diagnostics);
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
        tvGithub = findViewById(R.id.tv_github);
        tvActiveProfile = findViewById(R.id.tv_active_profile);
        tvProfileKey = findViewById(R.id.tv_profile_key);
        tvProfilesList = findViewById(R.id.tv_profiles_list);
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
        spCfMode = findViewById(R.id.sp_cf_mode);
        spTheme = findViewById(R.id.sp_theme);
        spLanguage = findViewById(R.id.sp_language);
        spProfileSelector = findViewById(R.id.sp_profile_selector);
        spRoutePreference = findViewById(R.id.sp_route_preference);
        spVpsRelaySaved = findViewById(R.id.sp_vps_relay_saved);

        navConnection = findViewById(R.id.nav_connection);
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
        cbAutostartBoot.setChecked(prefs.getBoolean("autostart_boot", false));
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
        btnBackMain.setOnClickListener(v -> showSettingsScreen(false));
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
        btnVpsRelayTest.setOnClickListener(v -> testVpsRelay());
        btnVpsRelayNew.setOnClickListener(v -> {
            clearVpsRelayForm();
            refreshVpsRelaySelector();
        });
        btnVpsRelaySave.setOnClickListener(v -> {
            if (saveCurrentVpsRelayFromForm()) {
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            }
        });
        btnVpsRelayAutoSetup.setOnClickListener(v -> showVpsAutoSetupDialog());
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
        btnSettingsDiagnostics.setOnClickListener(v -> showDiagnostics(true));
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
            updateVpsRelayFieldsEnabled();
            prefs.edit().putBoolean("vps_relay_enabled", checked).apply();
            refreshConnectionFields();
        });
        cbVpsRelayTls.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("vps_relay_tls", checked).apply();
            if (checked && "80".equals(etVpsRelayPort.getText().toString().trim())) {
                etVpsRelayPort.setText("443");
            } else if (!checked && "443".equals(etVpsRelayPort.getText().toString().trim())) {
                etVpsRelayPort.setText("80");
            }
        });
        cbVpsRelayBindProfile.setOnCheckedChangeListener((v, checked) -> {
            String key = checked ? vpsRelayProfileKeyForUi() : "";
            prefs.edit().putString("vps_relay_profile_key", key).apply();
            refreshVpsRelaySelector();
            refreshConnectionFields();
        });
        cbVerbose.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("verbose_logging", checked).apply());
        cbCheckUpdates.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("check_updates", checked).apply());
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
                if (relayId.isEmpty()) return;
                VpsRelayStore store = VpsRelayStore.fromPreferences(prefs);
                VpsRelayStore.Record record = store.relay(relayId);
                if (record == null) return;
                String profileKey = cbVpsRelayBindProfile != null && cbVpsRelayBindProfile.isChecked()
                        ? vpsRelayProfileKeyForUi() : "";
                store.bindProfile(profileKey, relayId);
                fillVpsRelayForm(record.config().withProfileKey(profileKey));
                refreshConnectionFields();
                Toast.makeText(MainActivity.this, R.string.vps_relay_selected, Toast.LENGTH_SHORT).show();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        VpsRelayConfig legacyRelay = currentVpsRelayConfig();
        VpsRelayStore.fromPreferences(prefs)
                .importLegacyIfNeeded(legacyRelay, legacyRelay.profileKey());
        refreshVpsRelaySelector();
        applyVpsSetupProgress(VpsSetupProgress.of(
                VpsSetupProgress.Stage.AUDIT, 0, getString(R.string.vps_setup_idle)));
    }

    private void refreshProfileControls(boolean force) {
        if (prefs == null || tvActiveProfile == null || spProfileSelector == null) return;
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        NetworkProfileRecord active = store.ensureProfile(
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
            record = store.ensureProfile(profile, System.currentTimeMillis());
            activeProfileKey = record.key();
        } else if (!activeProfileKey.isEmpty()) {
            record = store.profile(activeProfileKey);
        }
        if (record == null) {
            record = store.ensureProfile(NetworkProfileIdentifier.current(this), System.currentTimeMillis());
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
        if (!MainUiState.canOpenSettings(isProxyRunning())) {
            Toast.makeText(this, R.string.settings_locked, Toast.LENGTH_SHORT).show();
            return;
        }
        showSettingsScreen(true);
    }

    private void showSettingsScreen(boolean show) {
        if (show && !MainUiState.canOpenSettings(isProxyRunning())) {
            Toast.makeText(this, R.string.settings_locked, Toast.LENGTH_SHORT).show();
            return;
        }
        if (show) {
            refreshProfileControls(true);
            showSettingsSection(currentSettingsSection);
        }
        if (diagnosticsScreen != null) diagnosticsScreen.setVisibility(View.GONE);
        mainScreen.setVisibility(show ? View.GONE : View.VISIBLE);
        settingsScreen.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setupSettingsNavigation() {
        navConnection.setOnClickListener(v -> showSettingsSection(MainUiState.SettingsSection.CONNECTION));
        navSystem.setOnClickListener(v -> showSettingsSection(MainUiState.SettingsSection.SYSTEM));
        navAbout.setOnClickListener(v -> showSettingsSection(MainUiState.SettingsSection.ABOUT));
        showSettingsSection(currentSettingsSection);
    }

    private void showSettingsSection(MainUiState.SettingsSection section) {
        currentSettingsSection = section == null ? MainUiState.SettingsSection.CONNECTION : section;

        boolean connection = currentSettingsSection == MainUiState.SettingsSection.CONNECTION;
        boolean system = currentSettingsSection == MainUiState.SettingsSection.SYSTEM;
        boolean about = currentSettingsSection == MainUiState.SettingsSection.ABOUT;

        setVisible(sectionConnection, connection);
        setVisible(sectionProfiles, connection);
        setVisible(sectionRoute, connection);
        setVisible(sectionOptimization, connection);
        setVisible(sectionVpsRelay, connection);
        setVisible(sectionImportExport, connection);
        setVisible(sectionDiagnosticsLogs, system);
        setVisible(sectionBehavior, system);
        setVisible(sectionAdvanced, system);
        setVisible(sectionInterface, system);
        setVisible(sectionUpdates, system);
        setVisible(sectionAbout, about);

        setNavState(navConnection, connection);
        setNavState(navSystem, system);
        setNavState(navAbout, about);
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
        Intent si = new Intent(this, ProxyService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si);
        else startService(si);
        handler.postDelayed(() -> updateRunningState(true), 500);
    }

    private void stopProxy() {
        stopService(new Intent(this, ProxyService.class));
        updateRunningState(false);
    }

    private boolean saveSettings() {
        try {
            int port = Integer.parseInt(etCustomPort.getText().toString().trim());
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            String secret = etSecret.getText().toString().trim().toLowerCase(Locale.US);
            if (secret.startsWith("dd") && secret.length() == 34) secret = secret.substring(2);
            if (!secret.matches("[0-9a-f]{32}")) throw new IllegalArgumentException();
            String dcRules = MtProtoConfig.formatDcRules(
                    MtProtoConfig.parseUserDcRules(etDcRules.getText().toString()));
            VpsRelayConfig relay = currentVpsRelayConfig();
            if (relay.isEnabled() && !relay.isUsable()) throw new IllegalArgumentException();
            updateStoredVpsRelaySelection(relay);

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
            putVpsRelaySettings(editor, relay);
            editor.apply();
            saveDisplayedRoutePreference();
            refreshConnectionFields();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void refreshConnectionFields() {
        String ip = valueOrDefault(etCustomIp, MtProtoConfig.DEFAULT_HOST);
        int port = intOrDefault(etCustomPort, MtProtoConfig.DEFAULT_PORT);
        String secret = MtProtoConfig.normalizeSecretHex(etSecret.getText().toString());
        tvAddress.setText(ip + ":" + port);
        tvRoute.setText(currentRouteLabel());
        tvCfDomain.setText(currentCfDomainLabel());
        tvPort.setText(String.valueOf(port));
        tvMainProfile.setText(currentMainProfileLabel(null));
        tvTgLink.setText(MtProtoConfig.telegramProxyLink(ip, port, secret));
    }

    private String currentRouteLabel() {
        ProxyService svc = ProxyService.getInstance();
        if (svc != null) {
            return routeLabel(svc.diagnosticsSnapshot().serviceState().routeState());
        }
        RoutePlan plan = new RouteEngine().plan(
                routeSettingsFromControls(), firstDcId(), false, "",
                java.util.Collections.emptyMap(), System.currentTimeMillis());
        return plan.isEmpty() ? getString(R.string.route_searching) : routeLabel(plan.selected());
    }

    private String currentCfDomainLabel() {
        if (MtProtoProxyEngine.CF_MODE_OFF.equals(selectedCfProxyMode())) return getString(R.string.route_off);
        ProxyService svc = ProxyService.getInstance();
        if (svc != null) {
            RouteState route = svc.diagnosticsSnapshot().serviceState().routeState();
            if (route.type() == RouteType.PUBLIC_CLOUDFLARE
                    || route.type() == RouteType.CUSTOM_CLOUDFLARE) {
                return route.activeEndpoint().isEmpty()
                        ? getString(R.string.route_searching)
                        : route.activeEndpoint();
            }
            return "-";
        }
        String active = CfProxyDomainState.shared().activeDomain(currentCfNetworkProfile());
        return active.isEmpty() ? getString(R.string.route_searching) : active;
    }

    private void updateRunningState(boolean running) {
        updateRunningState(currentServiceState(running));
    }

    private void updateRunningState(ServiceState state) {
        boolean serviceStarted = state.serviceStarted();
        btnStart.setEnabled(true);
        btnStart.setContentDescription(getString(serviceStarted ? R.string.stop : R.string.start));
        btnStart.setBackgroundResource(serviceStarted ? R.drawable.power_button_active : R.drawable.power_button_idle);
        btnStop.setEnabled(serviceStarted);
        btnOpenSettings.setAlpha(MainUiState.canOpenSettings(serviceStarted) ? 1f : 0.45f);
        if (serviceStarted && settingsScreen != null && settingsScreen.getVisibility() == View.VISIBLE) {
            showSettingsScreen(false);
        }
        if (serviceStarted) {
            if (state.status() == ServiceState.Status.ACTIVE) {
                tvStatus.setText(R.string.status_active);
                tvStatus.setTextColor(getColorValue(R.color.green));
            } else if (state.status() == ServiceState.Status.SLEEP) {
                tvStatus.setText(R.string.status_sleep);
                tvStatus.setTextColor(getColorValue(R.color.warning));
            } else if (state.status() == ServiceState.Status.DEGRADED) {
                tvStatus.setText(R.string.status_degraded);
                tvStatus.setTextColor(getColorValue(R.color.warning));
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
        }
        refreshConnectionFields();
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

    private String routeLabel(RouteCandidate route) {
        if (route == null) return getString(R.string.route_searching);
        if (route.type() == RouteType.WORKER) return getString(R.string.route_cloudflare_worker);
        if (route.type() == RouteType.PUBLIC_CLOUDFLARE
                || route.type() == RouteType.CUSTOM_CLOUDFLARE) {
            return getString(R.string.route_cloudflare_cdn);
        }
        if (route.type() == RouteType.DIRECT_WS) return getString(R.string.route_direct);
        if (route.type() == RouteType.VPS_RELAY) return "VPS Relay";
        return route.displayName();
    }

    private void updateStats() {
        ProxyService svc = ProxyService.getInstance();
        if (svc == null || svc.getEngine() == null) {
            updateRunningState(false);
            return;
        }
        DiagnosticsSnapshot snapshot = svc.diagnosticsSnapshot();
        updateRunningState(snapshot.serviceState());
        MtProtoProxyEngine engine = svc.getEngine();
        RouteState routeState = snapshot.serviceState().routeState();
        tvMainProfile.setText(currentMainProfileLabel(snapshot.networkProfile()));
        updateRouteQuality(routeState);
        updateDisplayedPing(routeState);
        tvConnections.setText(MainUiState.connectionSummary(engine.activeConnectionCount()));
        tvTraffic.setText(coloredTrafficSummary(
                TgConstants.humanBytes(engine.bytesUp.get()),
                TgConstants.humanBytes(engine.bytesDown.get())));
        tvUptime.setText(MainUiState.uptimeSummary(svc.getUptime()));
        scheduleAutoPing(routeState);
        svc.refreshNotification();
        refreshDiagnosticsScreen(snapshot);
    }

    private void updateDisplayedPing(RouteState routeState) {
        int ping = MainUiState.displayedPing(
                routeState,
                lastMeasuredPingKey,
                lastMeasuredPingMs,
                lastMeasuredPingAt,
                System.currentTimeMillis());
        tvPing.setText(MainUiState.pingSummary(ping));
        tvPing.setTextColor(pingColor(ping));
    }

    private void scheduleAutoPing(RouteState routeState) {
        if (routeState == null || !routeState.active()) return;
        List<RoutePingTarget> targets = ActiveRoutePingPlanner.targetsFor(
                routeState, activeVpsRelayConfig(), currentDcRulesOrDefault());
        if (targets.isEmpty()) return;
        String routeKey = routeState.key();
        if (!autoPingGate.tryStart(routeKey, System.currentTimeMillis())) return;
        new Thread(() -> {
            Integer ms = RouteProbeClient.measureFirst(targets, 5000);
            if (ms != null) {
                postPingResult(routeKey, ms);
            } else {
                handler.post(() -> {
                    autoPingGate.finish(routeKey);
                    lastMeasuredPingKey = routeKey == null ? "" : routeKey;
                    lastMeasuredPingMs = MainUiState.PING_ERROR_MS;
                    lastMeasuredPingAt = System.currentTimeMillis();
                    DiagnosticsLog.record("auto ping " + lastMeasuredPingKey + " error");
                    tvPing.setText(MainUiState.pingSummary(MainUiState.PING_ERROR_MS));
                    tvPing.setTextColor(pingColor(MainUiState.PING_ERROR_MS));
                });
            }
        }, "tg-auto-ping").start();
    }

    private void postPingResult(String routeKey, int ms) {
        handler.post(() -> {
            autoPingGate.finish(routeKey);
            lastMeasuredPingKey = routeKey == null ? "" : routeKey;
            lastMeasuredPingMs = ms;
            lastMeasuredPingAt = System.currentTimeMillis();
            DiagnosticsLog.record("auto ping " + lastMeasuredPingKey + " " + ms + "ms");
            tvPing.setText(MainUiState.pingSummary(ms));
            tvPing.setTextColor(pingColor(ms));
        });
    }

    private int pingColor(int ms) {
        if (ms == MainUiState.PING_ERROR_MS) return 0xFFF44336;
        if (ms < 0) return getColorValue(R.color.text_secondary);
        return ms < 100 ? 0xFF4CAF50 : ms < 300 ? 0xFFFFAB00 : 0xFFF44336;
    }

    private void updateRouteQuality(RouteState routeState) {
        if (routeState == null || !routeState.active()) {
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
        btnVpsRelayTest.setEnabled(false);
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
                btnVpsRelayTest.setEnabled(true);
                btnVpsRelayTest.setText(R.string.test);
                if (result.status() == VpsRelayCheckResult.Status.OK) {
                    saveVpsRelaySettings(relay);
                    DiagnosticsLog.record("vps relay check ok " + relay.host());
                    Toast.makeText(this, R.string.vps_relay_test_ok, Toast.LENGTH_LONG).show();
                    checkActiveVpsRelayVersion(true);
                } else {
                    DiagnosticsLog.record("vps relay check failed "
                            + relay.host() + " " + result.status().name());
                    Toast.makeText(this, getString(R.string.vps_relay_test_failed,
                            result.status().name()), Toast.LENGTH_LONG).show();
                }
                refreshConnectionFields();
            });
        }, "tg-vps-relay-test").start();
    }

    private void checkActiveVpsRelayVersion(boolean userTriggered) {
        VpsRelayConfig relay = activeVpsRelayConfig();
        if (relay == null || !relay.isUsable()) return;
        if (!vpsRelayVersionCheckRunning.compareAndSet(false, true)) return;
        new Thread(() -> {
            VpsRelayInfo info = new VpsRelayClient().inspect(relay, VpsSetupScripts.RELAY_VERSION);
            handler.post(() -> {
                vpsRelayVersionCheckRunning.set(false);
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.vps_relay_update_title)
                .setMessage(getString(R.string.vps_relay_update_message,
                        info.relayVersion(), info.targetVersion()))
                .setPositiveButton(R.string.vps_relay_update_server,
                        (dialog, which) -> showVpsAutoSetupDialog(true))
                .setNegativeButton(R.string.skip_update, null)
                .show();
    }

    private void showAutoCfProxyResult(FlowsealConnectivity.Result result) {
        int ok = result.okCount();
        int total = FlowsealConnectivity.TEST_DCS.length;
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
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showMultiConnectivityResults(int titleRes, Map<String, FlowsealConnectivity.Result> results,
                                              String prefix) {
        StringBuilder message = new StringBuilder();
        int total = FlowsealConnectivity.TEST_DCS.length;
        boolean anyOk = false;
        for (Map.Entry<String, FlowsealConnectivity.Result> entry : results.entrySet()) {
            FlowsealConnectivity.Result result = entry.getValue();
            int ok = result.okCount();
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
        new AlertDialog.Builder(this)
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
                    try {
                        startActivityForResult(intent, REQUEST_INSTALL_UPDATE);
                    } catch (Exception e) {
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_dialog_title)
                .setMessage(getString(R.string.update_dialog_message, info.version))
                .setPositiveButton(R.string.install_update, (dialog, which) -> installLastRelease())
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.open_release, (dialog, which) -> openLink(info.htmlUrl))
                .show();
    }

    private void showInstallPermissionDialog(Intent intent) {
        new AlertDialog.Builder(this)
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
        String expected = pendingInstallVersion == null ? "" : pendingInstallVersion.trim();
        if (expected.isEmpty()) return;
        String installed = installedVersionName();
        if (expected.equals(installed)) {
            pendingInstallVersion = "";
            tvUpdateStatus.setText(R.string.update_installed_restart);
            btnInstallUpdate.setEnabled(false);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.update_installed_title)
                    .setMessage(R.string.update_installed_message)
                    .setPositiveButton(R.string.restart_app, (dialog, which) -> restartApp())
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
        } else {
            tvUpdateStatus.setText(getString(R.string.update_install_not_applied, installed));
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
        VpsRelayConfig relay = activeVpsRelayConfig();
        if (relay == null || !relay.isUsable()) {
            Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show();
            return;
        }
        showExportPayload(SettingsTransfer.exportVpsRelay(relay), "tgproxy-vps-relay.tgproxy");
    }

    private void showEncryptedExportDialog() {
        EditText password = new EditText(this);
        password.setHint(R.string.import_password_hint);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setTextColor(getColorValue(R.color.text_primary));
        password.setHintTextColor(getColorValue(R.color.text_hint));
        new AlertDialog.Builder(this)
                .setTitle(R.string.export_password_title)
                .setView(password)
                .setPositiveButton(R.string.export_encrypted_profile, (dialog, which) -> {
                    try {
                        String pass = password.getText().toString();
                        if (pass.trim().isEmpty()) throw new SettingsTransferException(
                                getString(R.string.export_password_required));
                        showExportPayload(SettingsTransfer.exportEncrypted(currentTransferData(), pass),
                                "tgproxy-full-profile.tgproxy");
                    } catch (Exception e) {
                        Toast.makeText(this, getString(R.string.import_failed,
                                e.getMessage()), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showImportSettingsDialog() {
        showImportSettingsDialog("");
    }

    private void showImportSettingsDialog(String initialPayload) {
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.import_settings)
                .setView(form)
                .setPositiveButton(R.string.import_settings, (dialog, which) ->
                        importSettingsPayload(payload.getText().toString(),
                                password.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.open_import_file, (dialog, which) -> openImportFilePicker())
                .show();
    }

    private void showExportPayload(String payload, String fileName) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.export_ready)
                .setMessage(payload)
                .setPositiveButton(R.string.copy_done, (dialog, which) -> {
                    copy(payload);
                    Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.share_export, (dialog, which) -> shareTransferPayload(payload))
                .setNeutralButton(R.string.show_qr, (dialog, which) -> showTransferQr(payload))
                .show();
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

    private void showTransferQr(String payload) {
        String link;
        try {
            link = SettingsTransfer.toDeepLink(payload);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.export_qr_title)
                .setView(layout)
                .setPositiveButton(R.string.share_qr, (dialog, which) -> shareTransferQrImage(payload))
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void shareTransferQrImage(String payload) {
        try {
            String link = SettingsTransfer.toDeepLink(payload);
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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getContentResolver().openInputStream(uri), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
        }
        return out.toString();
    }

    private void importSettingsPayload(String payload, String password) {
        try {
            SettingsTransfer.Imported imported = payload != null && payload.trim().startsWith("tgproxy://")
                    ? SettingsTransfer.parseDeepLink(payload.trim(), password)
                    : SettingsTransfer.parse(payload, password);
            applyImportedSettings(imported);
        } catch (SettingsTransferException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("password") && (password == null || password.trim().isEmpty())) {
                showImportSettingsDialog(payload);
            } else {
                Toast.makeText(this, getString(R.string.import_failed,
                        e.getMessage()), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void handleImportIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String raw = intent.getData().toString();
        if (!raw.startsWith("tgproxy://import")) return;
        try {
            SettingsTransfer.Imported imported = SettingsTransfer.parseDeepLink(raw, "");
            applyImportedSettings(imported);
        } catch (SettingsTransferException e) {
            showImportSettingsDialog(raw);
        }
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
        NetworkProfileRecord current = store.ensureProfile(
                NetworkProfileIdentifier.current(this), System.currentTimeMillis());
        List<VpsRelayImportTarget.Option> options =
                VpsRelayImportTarget.options(current, store.profilesSnapshot());
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            labels[i] = relayImportTargetLabel(options.get(i));
        }
        new AlertDialog.Builder(this)
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
        if (imported.kind() != SettingsTransfer.Kind.VPS_RELAY) {
            etCustomIp.setText(data.customIp());
            etCustomPort.setText(String.valueOf(data.customPort()));
            if (!data.mtProtoSecret().isEmpty()) etSecret.setText(data.mtProtoSecret());
            if (!data.dcRules().isEmpty()) etDcRules.setText(data.dcRules());
            spCfMode.setSelection(cfModeIndex(data.cfMode()));
            etCfDomains.setText(data.cfDomains());
            cbCfCustomDomain.setChecked(!data.cfDomains().trim().isEmpty());
            etWorkerDomains.setText(data.workerDomains());
            updateCfCustomDomainEnabled();
            spRoutePreference.setSelection(routePreferenceIndex(data.routePreference()));
        }
        VpsRelayConfig relay = data.relayConfig();
        if (relay != null && relay.isUsable()) {
            String targetProfileKey = relayProfileKey == null ? "" : relayProfileKey.trim();
            VpsRelayConfig boundRelay = relay.withProfileKey(targetProfileKey);
            fillVpsRelayForm(boundRelay);
            saveVpsRelaySettings(boundRelay);
        }
        if (imported.kind() != SettingsTransfer.Kind.VPS_RELAY) {
            saveSettings();
        }
        refreshVpsRelaySelector();
        refreshConnectionFields();
        Toast.makeText(this, R.string.import_applied, Toast.LENGTH_LONG).show();
        if (relay != null && relay.isUsable()) testVpsRelay();
    }

    private SettingsTransfer.Data currentTransferData() {
        NetworkProfileRecord record = currentProfileRecord();
        return SettingsTransfer.Data.builder()
                .profileName(record == null ? "" : record.displayName())
                .routePreference(selectedRoutePreference())
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
        VpsRelayConfig selected = VpsRelayStore.fromPreferences(prefs).selectedRelay(profileKey);
        if (selected != null) return selected;
        return currentVpsRelayConfig();
    }

    private String vpsRelayProfileKeyForUi() {
        if (displayedProfileKey != null && !displayedProfileKey.trim().isEmpty()) {
            return displayedProfileKey;
        }
        return currentProfileRecord().key();
    }

    private void refreshVpsRelaySelector() {
        if (spVpsRelaySaved == null || prefs == null) return;
        VpsRelayStore store = VpsRelayStore.fromPreferences(prefs);
        List<VpsRelayStore.Record> relays = store.relays();
        vpsRelaySelectorReady = false;
        vpsRelaySelectorIds.clear();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        if (relays.isEmpty()) {
            vpsRelaySelectorIds.add("");
            adapter.add(getString(R.string.vps_relay_none));
        } else {
            for (VpsRelayStore.Record record : relays) {
                vpsRelaySelectorIds.add(record.id());
                adapter.add(record.displayName());
            }
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
    }

    private void fillVpsRelayForm(VpsRelayConfig relay) {
        if (relay == null) return;
        cbVpsRelayEnabled.setChecked(relay.isEnabled());
        etVpsRelayName.setText(relay.name());
        etVpsRelayHost.setText(relay.host());
        etVpsRelayPort.setText(String.valueOf(relay.port() > 0 ? relay.port() : 443));
        cbVpsRelayTls.setChecked(relay.tls());
        etVpsRelayPath.setText(relay.path());
        etVpsRelayToken.setText(relay.token());
        cbVpsRelayBindProfile.setChecked(!relay.profileKey().isEmpty());
        updateVpsRelayFieldsEnabled();
    }

    private void clearVpsRelayForm() {
        cbVpsRelayEnabled.setChecked(false);
        etVpsRelayName.setText("");
        etVpsRelayHost.setText("");
        etVpsRelayPort.setText("443");
        cbVpsRelayTls.setChecked(true);
        etVpsRelayPath.setText("/apiws");
        etVpsRelayToken.setText("");
        cbVpsRelayBindProfile.setChecked(true);
        updateVpsRelayFieldsEnabled();
        applyVpsSetupProgress(VpsSetupProgress.of(
                VpsSetupProgress.Stage.AUDIT, 0, getString(R.string.vps_setup_idle)));
    }

    private boolean saveCurrentVpsRelayFromForm() {
        VpsRelayConfig relay = currentVpsRelayConfig();
        if (relay.isEnabled() && !relay.isUsable()) {
            Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show();
            return false;
        }
        saveVpsRelaySettings(relay);
        refreshVpsRelaySelector();
        refreshConnectionFields();
        return true;
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

        EditText sshHost = dialogField(R.string.vps_setup_ssh_host,
                valueOrDefault(etVpsRelayHost, ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText sshPort = dialogField(R.string.vps_setup_ssh_port,
                "22", InputType.TYPE_CLASS_NUMBER);
        EditText sshUser = dialogField(R.string.vps_setup_ssh_user,
                "root", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        EditText sshPassword = dialogField(R.string.vps_setup_ssh_password,
                "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
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
        form.addView(relayHost);
        form.addView(relayPort);
        form.addView(relayTls);
        form.addView(tlsNote);

        new AlertDialog.Builder(this)
                .setTitle(updateExistingRelay ? R.string.vps_relay_update_server : R.string.vps_setup_title)
                .setView(form)
                .setPositiveButton(updateExistingRelay ? R.string.vps_relay_update_server : R.string.vps_relay_auto_setup, (dialog, which) ->
                        startVpsAutoSetup(
                                sshHost.getText().toString(),
                                intOrDefault(sshPort, 22),
                                sshUser.getText().toString(),
                                sshPassword.getText().toString(),
                                relayHost.getText().toString(),
                                intOrDefault(relayPort, 18080),
                                relayTls.isChecked(),
                                updateExistingRelay))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, 0);
        editText.setLayoutParams(params);
        return editText;
    }

    private void startVpsAutoSetup(String sshHost, int sshPort, String sshUser,
                                   String sshPassword, String relayHost, int relayPort,
                                   boolean relayTls, boolean updateExistingRelay) {
        if ((relayHost == null || relayHost.trim().isEmpty()) && relayTls) {
            discoverVpsDomainsThenStart(sshHost, sshPort, sshUser, sshPassword,
                    relayPort, updateExistingRelay);
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
                .releaseVersion(VpsSetupScripts.RELAY_VERSION)
                .profileKey(profileKey)
                .updateExistingRelay(updateExistingRelay)
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
                .releaseVersion(VpsSetupScripts.RELAY_VERSION)
                .build();
        new Thread(() -> {
            try {
                String auditText = new JschVpsSshClient().execute(credentials,
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
                            relayPort, updateExistingRelay);
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
                                     boolean updateExistingRelay) {
        if (domains == null || domains.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.vps_domain_discovery_title)
                    .setMessage(R.string.vps_domain_discovery_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String[] items = domains.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.vps_domain_discovery_title)
                .setItems(items, (dialog, which) -> startVpsAutoSetup(
                        sshHost,
                        sshPort,
                        sshUser,
                        sshPassword,
                        items[which],
                        443,
                        true,
                        updateExistingRelay))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runVpsAutoSetup(VpsSetupRequest request, Map<Integer, String> dcRules) {
        VpsAutoSetupWizard wizard = new VpsAutoSetupWizard(
                new JschVpsSshClient(),
                (config, rules) -> new VpsRelayClient().check(config, rules),
                VpsRelayStore.fromPreferences(prefs),
                dcRules);
        try {
            VpsRelayConfig relay = wizard.run(request, new VpsAutoSetupWizard.Listener() {
                @Override public void onProgress(VpsSetupProgress progress) {
                    handler.post(() -> applyVpsSetupProgress(progress));
                }

                @Override public boolean onPlan(VpsSetupPlan plan) {
                    return confirmVpsSetupPlan(plan);
                }
            });
            handler.post(() -> {
                fillVpsRelayForm(relay);
                saveVpsRelaySettings(relay);
                refreshVpsRelaySelector();
                refreshConnectionFields();
                Toast.makeText(MainActivity.this, R.string.saved, Toast.LENGTH_SHORT).show();
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
        handler.post(() -> new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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
    }

    private void saveVpsRelaySettings(VpsRelayConfig relay) {
        updateStoredVpsRelaySelection(relay);
        SharedPreferences.Editor editor = prefs.edit();
        putVpsRelaySettings(editor, relay);
        editor.apply();
    }

    private void updateStoredVpsRelaySelection(VpsRelayConfig relay) {
        if (relay == null) return;
        VpsRelayStore store = VpsRelayStore.fromPreferences(prefs);
        String profileKey = relay.profileKey().isEmpty() ? "" : relay.profileKey();
        if (relay.isUsable()) store.saveRelay(relay, profileKey);
        else store.bindProfile(profileKey, "");
    }

    private void putVpsRelaySettings(SharedPreferences.Editor editor, VpsRelayConfig relay) {
        if (editor == null || relay == null) return;
        editor.putBoolean("vps_relay_enabled", relay.isEnabled())
                .putString("vps_relay_name", relay.name())
                .putString("vps_relay_host", relay.host())
                .putInt("vps_relay_port", relay.port())
                .putBoolean("vps_relay_tls", relay.tls())
                .putString("vps_relay_path", relay.path())
                .putString("vps_relay_token", relay.token())
                .putString("vps_relay_profile_key", relay.profileKey());
    }

    private void setEnabled(View view, boolean enabled) {
        if (view != null) {
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.55f);
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
        VpsRelayConfig relay = activeVpsRelayConfig();
        if (relay.isAllowedForProfile(profileRecord.key())) {
            builder.vpsRelay(relay.name(), relay.host(), relay.port());
        }
        return builder.build();
    }

    private NetworkProfileRecord currentProfileRecord() {
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        return store.ensureProfile(NetworkProfileIdentifier.current(this), System.currentTimeMillis());
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
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        String key = selectedProfileKey(store);
        store.renameProfile(key, etProfileName.getText().toString());
        store.setRoutePreference(key, selectedRoutePreference());
        NetworkProfileRecord record = store.profile(key);
        if (record != null) showProfileRecord(record);
        refreshProfileSelector(store, activeProfileKey, key);
        refreshProfilesList(store, activeProfileKey);
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
        refreshConnectionFields();
    }

    private void saveDisplayedRoutePreference() {
        if (spRoutePreference == null) return;
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        String key = selectedProfileKey(store);
        store.setRoutePreference(key, selectedRoutePreference());
        refreshProfilesList(store, activeProfileKey);
    }

    private String selectedProfileKey(NetworkProfileStore store) {
        String key = displayedProfileKey;
        if (key == null || key.isEmpty() || store.profile(key) == null) {
            key = store.ensureProfile(NetworkProfileIdentifier.current(this),
                    System.currentTimeMillis()).key();
            displayedProfileKey = key;
        }
        return key;
    }

    private void confirmDeleteDisplayedProfile() {
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        String key = displayedProfileKey;
        if (key == null || key.isEmpty() || store.profile(key) == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_profile_title)
                .setMessage(R.string.delete_profile_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_profile, (dialog, which) -> deleteDisplayedProfile(key))
                .show();
    }

    private void deleteDisplayedProfile(String key) {
        NetworkProfileStore store = NetworkProfileStore.fromPreferences(prefs);
        if (!store.deleteProfile(key)) return;
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

    private void requestPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), 100);
        }
    }

    private void requestBatteryOptimizationHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !cbSmartSleep.isChecked()) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try { startActivity(intent); } catch (Exception ignored) {}
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshProfileControls(true);
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
            showSettingsScreen(false);
            return;
        }
        super.onBackPressed();
    }
}
