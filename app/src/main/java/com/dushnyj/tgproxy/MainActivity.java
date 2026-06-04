package com.dushnyj.tgproxy;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String REPO_URL = "https://github.com/Dushnyj/TG-Proxy";

    private ImageButton btnStart;
    private Button btnStop, btnRegenerateSecret;
    private View btnOpenSettings, btnBackMain, btnPingNow, btnOpenGithub, btnOpenTelegram, btnMainMenu;
    private Button btnTestCf, btnTestWorker;
    private View btnCfHelp, btnWorkerHelp;
    private Button btnCheckUpdate, btnOpenRelease, btnInstallUpdate;
    private TextView tvStatus, tvAddress, tvRoute, tvCfDomain, tvPort, tvTgLink, tvPing, tvTraffic, tvUptime;
    private TextView tvUpdateStatus, tvUpdateProgress, tvVersion;
    private View tvGithub;
    private EditText etCustomIp, etCustomPort, etSecret, etDcRules, etCfDomains, etWorkerDomains;
    private CheckBox cbSmartSleep, cbAutostartOpen, cbAutostartBoot;
    private CheckBox cbCfCustomDomain, cbCfWarmup, cbCfRecheckNetwork, cbVerbose, cbCheckUpdates;
    private ProgressBar progressUpdate;
    private Spinner spCfMode, spTheme, spLanguage;
    private View mainScreen, settingsScreen;

    private SharedPreferences prefs;
    private Handler handler;
    private Runnable statsUpdater;
    private GithubReleaseUpdater.ReleaseInfo lastRelease;
    private String updateDialogVersion = "";
    private boolean pendingInstallAfterPermission;
    private boolean spinnerInit;

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

        statsUpdater = new Runnable() {
            @Override public void run() {
                updateStats();
                handler.postDelayed(this, 2000);
            }
        };

        if (cbAutostartOpen.isChecked() && ProxyService.getInstance() == null) {
            startProxy();
        }
        if (cbCheckUpdates.isChecked()) {
            checkUpdates(false);
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
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        btnBackMain = findViewById(R.id.btn_back_main);
        btnPingNow = findViewById(R.id.btn_ping_now);
        btnOpenGithub = findViewById(R.id.btn_open_github);
        btnOpenTelegram = findViewById(R.id.btn_open_telegram);
        btnMainMenu = findViewById(R.id.btn_main_menu);
        btnRegenerateSecret = findViewById(R.id.btn_regenerate_secret);
        btnTestCf = findViewById(R.id.btn_test_cf);
        btnTestWorker = findViewById(R.id.btn_test_worker);
        btnCfHelp = findViewById(R.id.btn_cf_help);
        btnWorkerHelp = findViewById(R.id.btn_worker_help);
        btnCheckUpdate = findViewById(R.id.btn_check_update);
        btnOpenRelease = findViewById(R.id.btn_open_release);
        btnInstallUpdate = findViewById(R.id.btn_install_update);

        tvStatus = findViewById(R.id.tv_status);
        tvAddress = findViewById(R.id.tv_address);
        tvRoute = findViewById(R.id.tv_route);
        tvCfDomain = findViewById(R.id.tv_cf_domain);
        tvPort = findViewById(R.id.tv_port);
        tvTgLink = findViewById(R.id.tv_tg_link);
        tvPing = findViewById(R.id.tv_ping);
        tvTraffic = findViewById(R.id.tv_traffic);
        tvUptime = findViewById(R.id.tv_uptime);
        tvUpdateStatus = findViewById(R.id.tv_update_status);
        tvUpdateProgress = findViewById(R.id.tv_update_progress);
        tvVersion = findViewById(R.id.tv_version);
        tvGithub = findViewById(R.id.tv_github);
        progressUpdate = findViewById(R.id.progress_update);

        etCustomIp = findViewById(R.id.et_custom_ip);
        etCustomPort = findViewById(R.id.et_custom_port);
        etSecret = findViewById(R.id.et_secret);
        etDcRules = findViewById(R.id.et_dc_rules);
        etCfDomains = findViewById(R.id.et_cf_domains);
        etWorkerDomains = findViewById(R.id.et_worker_domains);

        cbSmartSleep = findViewById(R.id.cb_smart_sleep);
        cbAutostartOpen = findViewById(R.id.cb_autostart_open);
        cbAutostartBoot = findViewById(R.id.cb_autostart_boot);
        cbCfCustomDomain = findViewById(R.id.cb_cf_custom_domain);
        cbCfWarmup = findViewById(R.id.cb_cf_warmup);
        cbCfRecheckNetwork = findViewById(R.id.cb_cf_recheck_network);
        cbVerbose = findViewById(R.id.cb_verbose);
        cbCheckUpdates = findViewById(R.id.cb_check_updates);
        spCfMode = findViewById(R.id.sp_cf_mode);
        spTheme = findViewById(R.id.sp_theme);
        spLanguage = findViewById(R.id.sp_language);
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
        cbVerbose.setChecked(prefs.getBoolean("verbose_logging", false));
        cbCheckUpdates.setChecked(prefs.getBoolean("check_updates", true));
        tvVersion.setText("Version " + BuildConfig.VERSION_NAME + " " + getString(R.string.app_by));
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
        btnPingNow.setOnClickListener(v -> measurePing());
        btnOpenGithub.setOnClickListener(v -> openLink(REPO_URL));
        btnOpenTelegram.setOnClickListener(v -> openTelegramLink());
        btnMainMenu.setOnClickListener(this::showMainMenu);
        btnRegenerateSecret.setOnClickListener(v -> {
            etSecret.setText(MtProtoConfig.generateSecretHex());
            saveSettings();
            refreshConnectionFields();
        });
        btnTestCf.setOnClickListener(v -> testCfProxy());
        btnTestWorker.setOnClickListener(v -> testWorker());
        btnCfHelp.setOnClickListener(v -> openLink("https://github.com/Flowseal/tg-ws-proxy/blob/main/docs/CfProxy.md"));
        btnWorkerHelp.setOnClickListener(v -> openLink("https://github.com/Flowseal/tg-ws-proxy/blob/main/docs/CfWorker.md"));
        btnCheckUpdate.setOnClickListener(v -> checkUpdates(true));
        btnOpenRelease.setOnClickListener(v -> openLink(lastRelease != null ? lastRelease.htmlUrl : REPO_URL + "/releases"));
        btnInstallUpdate.setOnClickListener(v -> installLastRelease());
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
        mainScreen.setVisibility(show ? View.GONE : View.VISIBLE);
        settingsScreen.setVisibility(show ? View.VISIBLE : View.GONE);
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
            MtProtoConfig.parseDcRules(etDcRules.getText().toString());

            SharedPreferences.Editor editor = prefs.edit()
                    .putString("custom_ip", valueOrDefault(etCustomIp, MtProtoConfig.DEFAULT_HOST))
                    .putInt("custom_port", port)
                    .putString("mtproto_secret", secret)
                    .putString("dc_rules", etDcRules.getText().toString().trim())
                    .putString("cfproxy_mode", selectedCfProxyMode())
                    .putBoolean("cfproxy_enabled", !MtProtoProxyEngine.CF_MODE_OFF.equals(selectedCfProxyMode()))
                    .putBoolean("cfproxy_custom_enabled", cbCfCustomDomain.isChecked())
                    .putString("cfproxy_domains", cbCfCustomDomain.isChecked()
                            ? etCfDomains.getText().toString().trim() : "")
                    .putString("worker_domains", etWorkerDomains.getText().toString().trim())
                    .putBoolean("verbose_logging", cbVerbose.isChecked())
                    .putBoolean("cf_warmup", cbCfWarmup.isChecked())
                    .putBoolean("cf_recheck_network", cbCfRecheckNetwork.isChecked());
            editor.apply();
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
        tvTgLink.setText(MtProtoConfig.telegramProxyLink(ip, port, secret));
    }

    private String currentRouteLabel() {
        if (!splitDomains(etWorkerDomains.getText().toString()).isEmpty()) {
            return getString(R.string.route_cloudflare_worker);
        }
        String mode = selectedCfProxyMode();
        if (MtProtoProxyEngine.CF_MODE_ON.equals(mode)) return getString(R.string.route_cloudflare_cdn);
        if (MtProtoProxyEngine.CF_MODE_AUTO.equals(mode)) {
            return NetworkUtils.isMobileNetwork(this)
                    ? getString(R.string.route_auto_cf)
                    : getString(R.string.route_auto_direct);
        }
        return getString(R.string.route_direct);
    }

    private String currentCfDomainLabel() {
        if (MtProtoProxyEngine.CF_MODE_OFF.equals(selectedCfProxyMode())) return getString(R.string.route_off);
        String active = CfProxyDomainState.shared().activeDomain(currentCfNetworkProfile());
        return active.isEmpty() ? getString(R.string.route_searching) : active;
    }

    private void updateRunningState(boolean running) {
        btnStart.setEnabled(true);
        btnStart.setContentDescription(getString(running ? R.string.stop : R.string.start));
        btnStart.setBackgroundResource(running ? R.drawable.power_button_active : R.drawable.power_button_idle);
        btnStop.setEnabled(running);
        btnOpenSettings.setAlpha(MainUiState.canOpenSettings(running) ? 1f : 0.45f);
        if (running && settingsScreen != null && settingsScreen.getVisibility() == View.VISIBLE) {
            showSettingsScreen(false);
        }
        if (running) {
            ProxyService svc = ProxyService.getInstance();
            boolean paused = svc != null && svc.isPaused();
            tvStatus.setText(paused ? R.string.status_sleep : R.string.status_active);
            tvStatus.setTextColor(getColorValue(paused ? R.color.warning : R.color.green));
        } else {
            tvStatus.setText(R.string.status_stopped);
            tvStatus.setTextColor(getColorValue(R.color.text_secondary));
            tvTraffic.setText(coloredTrafficText(MainUiState.emptyTrafficSummary()));
            tvUptime.setText("-");
        }
        refreshConnectionFields();
    }

    private void updateStats() {
        ProxyService svc = ProxyService.getInstance();
        if (svc == null || svc.getEngine() == null) {
            updateRunningState(false);
            return;
        }
        updateRunningState(true);
        MtProtoProxyEngine engine = svc.getEngine();
        tvTraffic.setText(coloredTrafficSummary(
                TgConstants.humanBytes(engine.bytesUp.get()),
                TgConstants.humanBytes(engine.bytesDown.get())));
        tvUptime.setText(MainUiState.uptimeSummary(svc.getUptime()));
        svc.refreshNotification();
    }

    private void measurePing() {
        List<PingProbe> probes = pingProbes();
        tvPing.setText(R.string.checking);
        new Thread(() -> {
            ArrayList<PingProbe> cfProbes = new ArrayList<>();
            for (PingProbe probe : probes) {
                if (probe.isCfProbe()) {
                    cfProbes.add(probe);
                    continue;
                }

                Integer cfMs = connectCfPingProbes(cfProbes);
                if (cfMs != null) {
                    postPingResult(cfMs);
                    return;
                }
                cfProbes.clear();

                Integer ms = connectSinglePingProbe(probe);
                if (ms != null) {
                    postPingResult(ms);
                    return;
                }
            }

            Integer cfMs = connectCfPingProbes(cfProbes);
            if (cfMs != null) {
                postPingResult(cfMs);
                return;
            }
            handler.post(() -> {
                tvPing.setText(R.string.not_available);
                tvPing.setTextColor(0xFF9AA5B1);
            });
        }, "tg-ping").start();
    }

    private Integer connectSinglePingProbe(PingProbe probe) {
        long start = System.currentTimeMillis();
        try {
            probe.connect();
            return (int) (System.currentTimeMillis() - start);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer connectCfPingProbes(List<PingProbe> probes) {
        if (probes.isEmpty()) return null;
        String networkProfile = currentCfNetworkProfile();
        ArrayList<String> domains = new ArrayList<>();
        for (PingProbe probe : probes) {
            domains.add(probe.baseDomain);
        }

        return new ParallelCfConnector<Integer>(
                CfProxyDomainState.shared(), 2, networkProfile).connect(
                domains,
                baseDomain -> {
                    PingProbe probe = null;
                    for (PingProbe candidate : probes) {
                        if (candidate.baseDomain.equals(baseDomain)) {
                            probe = candidate;
                            break;
                        }
                    }
                    if (probe == null) throw new IllegalStateException("Missing CF ping probe");
                    long start = System.currentTimeMillis();
                    probe.connect(networkProfile);
                    return (int) (System.currentTimeMillis() - start);
                },
                value -> {});
    }

    private void postPingResult(int ms) {
        int color = ms < 100 ? 0xFF4CAF50 : ms < 300 ? 0xFFFFAB00 : 0xFFF44336;
        handler.post(() -> {
            tvPing.setText(ms + " ms");
            tvPing.setTextColor(color);
            refreshConnectionFields();
        });
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

            @Override public void onInstallerStarted() {
                handler.post(() -> {
                    tvUpdateStatus.setText(R.string.installer_started);
                    progressUpdate.setIndeterminate(false);
                    progressUpdate.setProgress(progressUpdate.getMax());
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

    private String firstDcIp() {
        try {
            return MtProtoConfig.parseDcRules(etDcRules.getText().toString()).values().iterator().next();
        } catch (Exception ignored) {
            return "149.154.167.220";
        }
    }

    private int firstDcId() {
        try {
            return MtProtoConfig.parseDcRules(etDcRules.getText().toString()).keySet().iterator().next();
        } catch (Exception ignored) {
            return 2;
        }
    }

    private List<PingProbe> pingProbes() {
        ArrayList<PingProbe> probes = new ArrayList<>();
        int dc = firstDcId();
        List<String> workerDomains = splitDomains(etWorkerDomains.getText().toString());
        for (String domain : workerDomains) {
            String path = "/apiws?dst=" + Uri.encode(defaultTelegramIp(dc)) + "&dc=" + dc;
            probes.add(PingProbe.websocket(domain, domain, path, ""));
        }

        ArrayList<PingProbe> cfProbes = new ArrayList<>();
        if (!MtProtoProxyEngine.CF_MODE_OFF.equals(selectedCfProxyMode())) {
            List<String> domains = cbCfCustomDomain.isChecked()
                    ? splitDomains(etCfDomains.getText().toString())
                    : FlowsealCfDomains.defaults();
            if (domains.isEmpty()) domains = FlowsealCfDomains.defaults();
            domains = CfProxyDomainState.shared()
                    .orderedDomains(domains, currentCfNetworkProfile(), System.currentTimeMillis());
            for (String baseDomain : domains) {
                String domain = "kws" + dc + "." + baseDomain;
                cfProbes.add(PingProbe.websocket(domain, domain, "/apiws", baseDomain));
            }
        }

        PingProbe directProbe = PingProbe.tcp(firstDcIp());
        String mode = selectedCfProxyMode();
        boolean cfFirst = MtProtoProxyEngine.CF_MODE_ON.equals(mode)
                || (MtProtoProxyEngine.CF_MODE_AUTO.equals(mode)
                && CfProxyDomainState.PROFILE_MOBILE.equals(currentCfNetworkProfile()));
        if (cfFirst) {
            probes.addAll(cfProbes);
            probes.add(directProbe);
        } else {
            probes.add(directProbe);
            probes.addAll(cfProbes);
        }
        return probes;
    }

    private String currentCfNetworkProfile() {
        return NetworkUtils.isMobileNetwork(this)
                ? CfProxyDomainState.PROFILE_MOBILE
                : CfProxyDomainState.PROFILE_WIFI;
    }

    private static String defaultTelegramIp(int dc) {
        switch (dc) {
            case 1: return "149.154.175.50";
            case 2: return "149.154.167.51";
            case 3: return "149.154.175.100";
            case 4: return "149.154.167.91";
            case 5: return "149.154.171.5";
            case 203: return "91.105.192.100";
            default: return "149.154.167.51";
        }
    }

    private static final class PingProbe {
        final String host;
        final String sni;
        final String path;
        final String baseDomain;
        final boolean websocket;

        private PingProbe(String host, String sni, String path, String baseDomain, boolean websocket) {
            this.host = host;
            this.sni = sni;
            this.path = path;
            this.baseDomain = baseDomain;
            this.websocket = websocket;
        }

        static PingProbe websocket(String host, String sni, String path, String baseDomain) {
            return new PingProbe(host, sni, path, baseDomain, true);
        }

        static PingProbe tcp(String host) {
            return new PingProbe(host, host, "", "", false);
        }

        boolean isCfProbe() {
            return websocket && baseDomain != null && !baseDomain.isEmpty();
        }

        void connect() throws Exception {
            connect(CfProxyDomainState.PROFILE_DEFAULT);
        }

        void connect(String networkProfile) throws Exception {
            if (websocket) {
                try {
                    RawWebSocket ws = RawWebSocket.connect(host, sni, 5000, path, true);
                    try {
                        CfProxyDomainState.shared()
                                .markSuccess(baseDomain, networkProfile, System.currentTimeMillis());
                    } finally {
                        ws.close();
                    }
                } catch (Exception e) {
                    if (CfProxyDomainState.isTooManyRequests(e)) {
                        CfProxyDomainState.shared()
                                .markTooManyRequests(
                                        baseDomain, networkProfile, System.currentTimeMillis());
                    }
                    throw e;
                }
                return;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, 443), 5000);
            }
        }
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
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
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
        if (statsUpdater != null) handler.post(statsUpdater);
        if (pendingInstallAfterPermission && GithubReleaseUpdater.canInstallPackages(this)) {
            pendingInstallAfterPermission = false;
            installLastRelease();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (statsUpdater != null) handler.removeCallbacks(statsUpdater);
    }

    @Override public void onBackPressed() {
        if (settingsScreen != null && settingsScreen.getVisibility() == View.VISIBLE) {
            showSettingsScreen(false);
            return;
        }
        super.onBackPressed();
    }
}
