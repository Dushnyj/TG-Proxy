package com.dushnyj.tgproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.preference.PreferenceManager;

import java.util.Arrays;
import java.util.Map;

public class ProxyService extends Service {

    private static final String CHANNEL_ID = "proxy_channel";
    private static final int    NOTIF_ID   = 1;
    private static final String ACTION_STOP = "com.dushnyj.tgproxy.action.STOP";
    private static final String ACTION_RESUME = "com.dushnyj.tgproxy.action.RESUME";

    private MtProtoProxyEngine engine;
    private PowerManager.WakeLock wakeLock;
    private Handler handler;
    private int     port;
    private String  boundIp = "127.0.0.1";
    private boolean isMobile = false;
    private NetworkProfileStore profileStore;
    private NetworkProfile activeNetworkProfile = NetworkProfile.defaultProfile();
    private NetworkProfileRecord activeProfileRecord;

    private static ProxyService instance;

    private ScreenStateReceiver screenReceiver;
    private boolean smartSleepEnabled = TgRoutePolicy.DEFAULT_SMART_SLEEP;
    private volatile boolean paused = false;
    private SharedPreferences prefs;

    private ConnectivityManager.NetworkCallback networkCallback;

    private Runnable pendingReconnect = null;
    private Runnable notificationTicker = null;
    private Runnable engineStartRetry = null;
    private Bitmap notificationLargeIcon;
    private volatile boolean engineStartInProgress = false;
    private boolean recheckOnNetworkChange = true;
    private static final long RECONNECT_DEBOUNCE_MS = 2500;
    private static final long ENGINE_START_RETRY_MS = 5000;

    public static ProxyService getInstance() { return instance; }
    public MtProtoProxyEngine getEngine()     { return engine; }
    public int         getPort()              { return port; }
    public boolean     isPaused()             { return paused; }
    public boolean     isMobileNetwork()      { return isMobile; }

    private long startTime;

    public long getUptime() { return System.currentTimeMillis() - startTime; }

    public String getIp() {
        return (engine != null) ? engine.getBoundIp() : boundIp;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler  = new Handler(Looper.getMainLooper());
        prefs    = PreferenceManager.getDefaultSharedPreferences(this);
        createNotificationChannel();
        DiagnosticsLog.record("service created");

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TGProxy::ProxyWake");

        profileStore = NetworkProfileStore.fromPreferences(prefs);
        activateNetworkProfile(false);
        registerNetworkCallback();
        registerScreenReceiver();
    }

    private void registerScreenReceiver() {
        smartSleepEnabled = prefs.getBoolean("smart_sleep", TgRoutePolicy.DEFAULT_SMART_SLEEP);
        if (!smartSleepEnabled) return;

        screenReceiver = new ScreenStateReceiver();
        screenReceiver.setCallback(new ScreenStateReceiver.Callback() {
            @Override public void onScreenOn() {
                if (paused && engine != null) {
                    DiagnosticsLog.record("screen on, resuming engine");
                    resumeEngine();
                }
            }
            @Override public void onScreenOff() {
                if (!paused && engine != null && smartSleepEnabled) {
                    handler.postDelayed(() -> {
                        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                        if (pm != null && !pm.isInteractive()) pauseEngine();
                    }, 10_000);
                }
            }
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    String previous = activeProfileKey();
                    NetworkProfileRecord record = activateNetworkProfile(true);
                    if (record != null && !previous.equals(record.key())) {
                        DiagnosticsLog.record("network profile changed " + previous + " -> " + record.key());
                        if (engine != null && recheckOnNetworkChange) scheduleReconnect();
                    }
                }

                @Override
                public void onLost(Network network) {
                    cancelPendingReconnect();
                }

                @Override
                public void onAvailable(Network network) {
                    if (paused) {
                        handler.post(() -> resumeEngine());
                    } else {
                        String previous = activeProfileKey();
                        NetworkProfileRecord record = activateNetworkProfile(true);
                        if (record != null && !previous.equals(record.key())) {
                            DiagnosticsLog.record("network available, profile changed "
                                    + previous + " -> " + record.key());
                            if (engine != null && recheckOnNetworkChange) scheduleReconnect();
                        }
                    }
                }
            };

            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(req, networkCallback);
        } catch (Exception ignored) {}
    }

    private void scheduleReconnect() {
        cancelPendingReconnect();
        DiagnosticsLog.record("route reconnect scheduled");
        pendingReconnect = () -> {
            pendingReconnect = null;
            if (engine != null && engine.isRunning() && !paused) {
                DiagnosticsLog.record("route reconnect executing");
                engine.reconnectPool();
            }
        };
        handler.postDelayed(pendingReconnect, RECONNECT_DEBOUNCE_MS);
    }

    private void cancelPendingReconnect() {
        if (pendingReconnect != null) {
            handler.removeCallbacks(pendingReconnect);
            pendingReconnect = null;
        }
    }

    private void pauseEngine() {
        paused = true;
        cancelPendingReconnect();
        DiagnosticsLog.record("engine paused by smart sleep");
        if (engine != null) engine.pause();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        refreshNotification();
    }

    private void resumeEngine() {
        paused = false;
        DiagnosticsLog.record("engine resume requested");
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        if (engine != null) {
            new Thread(() -> {
                try {
                    engine.resume(port);
                    DiagnosticsLog.record("engine resumed on " + boundIp + ":" + port);
                } catch (Exception e) {
                    DiagnosticsLog.record("engine resume failed " + errorSummary(e));
                }
            }).start();
        }
        refreshNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            DiagnosticsLog.record("notification stop action");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RESUME.equals(intent.getAction())) {
            DiagnosticsLog.record("notification resume action");
            if (paused && engine != null) resumeEngine();
            refreshNotification();
            return START_STICKY;
        }

        if (engine != null) {
            cancelEngineStartRetry();
            persistCurrentRouteStats();
            engine.stop();
            engine = null;
            DiagnosticsLog.record("existing engine stopped before restart");
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        port = prefs.getInt("custom_port", MtProtoConfig.DEFAULT_PORT);
        if (port < 1 || port > 65535) port = MtProtoConfig.DEFAULT_PORT;

        boundIp = prefs.getString("custom_ip", MtProtoConfig.DEFAULT_HOST);
        if (boundIp == null || boundIp.trim().isEmpty()) boundIp = MtProtoConfig.DEFAULT_HOST;

        smartSleepEnabled = prefs.getBoolean("smart_sleep", TgRoutePolicy.DEFAULT_SMART_SLEEP);
        recheckOnNetworkChange = prefs.getBoolean("cf_recheck_network", true);

        startTime = System.currentTimeMillis();
        paused = false;
        engineStartInProgress = true;
        startForeground(NOTIF_ID, buildNotification());
        DiagnosticsLog.record("service start requested " + boundIp + ":" + port);

        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();

        profileStore = NetworkProfileStore.fromPreferences(prefs);
        NetworkProfileRecord profileRecord = activateNetworkProfile(false);

        engine = new MtProtoProxyEngine();
        engine.setBoundIp(boundIp);
        engine.setSecretHex(prefs.getString("mtproto_secret", MtProtoConfig.generateSecretHex()));
        engine.setDcRules(prefs.getString("dc_rules", MtProtoConfig.DEFAULT_DC_RULES));
        engine.setCfProxyMode(storedCfProxyMode());
        engine.setCfProxyDomains(splitDomains(prefs.getString("cfproxy_domains", "")));
        engine.setCfProxyCustomDomains(prefs.getBoolean("cfproxy_custom_enabled", false));
        engine.setCfWarmupEnabled(prefs.getBoolean("cf_warmup", true));
        engine.setCfWorkerDomains(splitDomains(prefs.getString("worker_domains", "")));
        engine.setVpsRelayConfig(vpsRelayConfigFromPrefs(profileRecord == null ? "" : profileRecord.key()));
        engine.setVerbose(prefs.getBoolean("verbose_logging", false));
        if (profileRecord != null) {
            engine.setNetworkProfile(profileRecord.profile());
            engine.setRoutePreference(profileRecord.routePreference());
            engine.replaceRouteStats(profileStore.routeStats(profileRecord.profile()));
            DiagnosticsLog.record("active profile " + profileRecord.key()
                    + " routePreference=" + profileRecord.routePreference().name());
        }
        engine.setRouteStatsChangedListener(this::persistCurrentRouteStats);

        startEngineAsync();
        startNotificationTicker();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        DiagnosticsLog.record("service destroyed");
        engineStartInProgress = false;
        cancelEngineStartRetry();
        cancelPendingReconnect();
        stopNotificationTicker();
        persistCurrentRouteStats();
        if (engine != null) { engine.stop(); engine = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        }
        if (networkCallback != null) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        DiagnosticsLog.record("task removed, foreground proxy service remains active");
        refreshNotification();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "TG Proxy", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notification_channel_description));
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        ServiceState state = diagnosticsSnapshot().serviceState();
        PendingIntent pi = contentPendingIntent();
        PendingIntent actionPi = paused
                ? servicePendingIntent(ACTION_RESUME, 3)
                : servicePendingIntent(ACTION_STOP, 2);
        String actionLabel = paused
                ? getString(R.string.notification_action_start)
                : getString(R.string.notification_action_stop);
        String address = getIp() + ":" + port;
        String up = "-";
        String down = "-";
        if (engine != null) {
            up = TgConstants.humanBytes(engine.bytesUp.get());
            down = TgConstants.humanBytes(engine.bytesDown.get());
        }
        String traffic = MainUiState.trafficSummary(up, down);
        String uptime = MainUiState.uptimeSummary(getUptime());
        String status = serviceStatusLabel(state.status());
        CharSequence compact = colorTrafficInText(status + " • " + address + " • " + traffic, -1);
        CharSequence details = colorTrafficInText(
                getString(R.string.notification_status_line, status)
                        + "\n" + getString(R.string.notification_address_line, address)
                        + "\n" + getString(R.string.notification_uptime_line, uptime)
                        + "\n" + getString(R.string.notification_traffic_line, traffic),
                -1);
        long when = startTime > 0 ? startTime : System.currentTimeMillis();

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle("TG Proxy")
                .setContentText(compact)
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(notificationLargeIcon())
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(when)
                .setUsesChronometer(!paused)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(R.drawable.ic_notification, actionLabel, actionPi);
        return builder.build();
    }

    private String serviceStatusLabel(ServiceState.Status status) {
        if (status == ServiceState.Status.ACTIVE) return getString(R.string.status_active);
        if (status == ServiceState.Status.SLEEP) return getString(R.string.status_sleep);
        if (status == ServiceState.Status.DEGRADED) return getString(R.string.status_degraded);
        if (status == ServiceState.Status.RETRYING) return getString(R.string.status_retrying);
        if (status == ServiceState.Status.DEAD) return getString(R.string.status_dead);
        if (status == ServiceState.Status.STARTING) return getString(R.string.status_starting);
        return getString(R.string.status_stopped);
    }

    private PendingIntent contentPendingIntent() {
        Intent ni = new Intent(this, MainActivity.class);
        ni.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(this, 0, ni, piFlags);
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(action);
        int piFlags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(this, requestCode, intent, piFlags);
    }

    private Bitmap notificationLargeIcon() {
        if (notificationLargeIcon == null) {
            notificationLargeIcon = BitmapFactory.decodeResource(getResources(), R.drawable.app_icon_full);
        }
        return notificationLargeIcon;
    }

    private String storedCfProxyMode() {
        String fallback = prefs.getBoolean("cfproxy_enabled", true)
                ? MtProtoProxyEngine.CF_MODE_AUTO
                : MtProtoProxyEngine.CF_MODE_OFF;
        return MtProtoProxyEngine.normalizeCfProxyMode(
                prefs.getString("cfproxy_mode", fallback));
    }

    private VpsRelayConfig vpsRelayConfigFromPrefs(String profileKey) {
        VpsRelayConfig selected = VpsRelayStore.fromPreferences(prefs).selectedRelay(profileKey);
        return selected == null ? VpsRelayConfig.disabled() : selected;
    }

    private CharSequence colorTrafficInText(String text, int trafficStartHint) {
        SpannableString span = new SpannableString(text);
        int up = trafficStartHint >= 0 ? trafficStartHint : text.indexOf('↑');
        int down = text.indexOf('↓', Math.max(0, up));
        if (up >= 0 && down > up) {
            span.setSpan(new ForegroundColorSpan(colorValue(R.color.green)),
                    up, down, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(colorValue(R.color.red)),
                    down, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    private int colorValue(int colorRes) {
        if (Build.VERSION.SDK_INT >= 23) return getColor(colorRes);
        return getResources().getColor(colorRes);
    }

    public void refreshNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {}
    }

    private void startEngineAsync() {
        engineStartInProgress = true;
        new Thread(() -> {
            try {
                MtProtoProxyEngine current = engine;
                if (current == null) {
                    engineStartInProgress = false;
                    return;
                }
                if (current.isRunning() && !current.isListening()) {
                    current.stop();
                }
                current.start(port);
                engineStartInProgress = false;
                DiagnosticsLog.record("engine started " + boundIp + ":" + port);
                cancelEngineStartRetry();
                refreshNotification();
            } catch (Exception e) {
                engineStartInProgress = false;
                DiagnosticsLog.record("engine start failed " + errorSummary(e));
                scheduleEngineStartRetry();
            }
        }, "tg-engine-start").start();
    }

    private void scheduleEngineStartRetry() {
        if (handler == null || engine == null || paused) return;
        if (engineStartRetry != null) return;
        engineStartRetry = () -> {
            engineStartRetry = null;
            if (engineNeedsStart()) {
                DiagnosticsLog.record("engine start retry");
                startEngineAsync();
            }
        };
        handler.postDelayed(engineStartRetry, ENGINE_START_RETRY_MS);
        refreshNotification();
    }

    private void cancelEngineStartRetry() {
        if (handler != null && engineStartRetry != null) {
            handler.removeCallbacks(engineStartRetry);
        }
        engineStartRetry = null;
    }

    DiagnosticsSnapshot diagnosticsSnapshot() {
        MtProtoProxyEngine currentEngine = engine;
        RouteState routeState = currentEngine == null
                ? RouteState.inactive("engine is not started")
                : currentEngine.currentRouteState();
        boolean engineRunning = currentEngine != null && currentEngine.isRunning();
        boolean localPortListening = currentEngine != null && currentEngine.isListening();
        ServiceState serviceState = ServiceState.from(
                instance != null,
                engineRunning,
                localPortListening,
                paused,
                routeState,
                engineStartInProgress,
                engineStartRetry != null,
                System.currentTimeMillis());
        Map<String, RouteStats> stats = currentEngine == null
                ? java.util.Collections.emptyMap()
                : currentEngine.routeStatsSnapshot();
        return new DiagnosticsSnapshot(
                serviceState,
                activeNetworkProfile,
                stats,
                currentEngine == null ? 0L : currentEngine.activeConnectionCount(),
                currentEngine == null ? 0L : currentEngine.connections.get(),
                currentEngine == null ? 0L : currentEngine.bytesUp.get(),
                currentEngine == null ? 0L : currentEngine.bytesDown.get(),
                currentEngine == null ? 0L : currentEngine.errors.get(),
                DiagnosticsSnapshot.totalRouteFailures(stats),
                startTime <= 0L ? 0L : getUptime());
    }

    void resetDiagnosticsState() {
        MtProtoProxyEngine currentEngine = engine;
        if (currentEngine != null) {
            currentEngine.resetDiagnosticsState();
        }
        DiagnosticsLog.record("service diagnostics state reset");
        refreshNotification();
    }

    private void startNotificationTicker() {
        stopNotificationTicker();
        notificationTicker = new Runnable() {
            @Override public void run() {
                watchdogTick();
                refreshNotification();
                if (engine != null) {
                    handler.postDelayed(this, MainUiState.NOTIFICATION_REFRESH_INTERVAL_MS);
                }
            }
        };
        handler.post(notificationTicker);
    }

    private void stopNotificationTicker() {
        if (notificationTicker != null) {
            handler.removeCallbacks(notificationTicker);
            notificationTicker = null;
        }
    }

    private void watchdogTick() {
        if (paused || engineStartInProgress || engineStartRetry != null) return;
        if (engineNeedsStart()) {
            DiagnosticsLog.record("watchdog detected inactive engine listener");
            scheduleEngineStartRetry();
        }
    }

    private boolean engineNeedsStart() {
        MtProtoProxyEngine current = engine;
        return current != null && !paused && !current.isListening();
    }

    private static java.util.List<String> splitDomains(String text) {
        if (text == null || text.trim().isEmpty()) return java.util.Collections.emptyList();
        return Arrays.asList(text.replace(',', ' ').replace(';', ' ').trim().split("\\s+"));
    }

    private synchronized NetworkProfileRecord activateNetworkProfile(boolean savePreviousStats) {
        if (profileStore == null) {
            profileStore = NetworkProfileStore.fromPreferences(prefs);
        }
        if (savePreviousStats) persistCurrentRouteStats();
        NetworkProfile profile = NetworkProfileIdentifier.current(this);
        NetworkProfileRecord record = profileStore.ensureProfile(profile, System.currentTimeMillis());
        activeNetworkProfile = record.profile();
        activeProfileRecord = record;
        isMobile = activeNetworkProfile.isMobile();
        DiagnosticsLog.record("network profile active " + record.key());
        if (engine != null) {
            engine.setNetworkProfile(activeNetworkProfile);
            engine.setRoutePreference(record.routePreference());
            engine.replaceRouteStats(profileStore.routeStats(activeNetworkProfile));
        }
        return record;
    }

    private synchronized void persistCurrentRouteStats() {
        if (profileStore == null || activeNetworkProfile == null || engine == null) return;
        profileStore.saveRouteStats(activeNetworkProfile, engine.routeStatsSnapshot());
    }

    private synchronized String activeProfileKey() {
        return activeProfileRecord == null ? "" : activeProfileRecord.key();
    }

    private static String errorSummary(Exception error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message.trim());
    }
}
