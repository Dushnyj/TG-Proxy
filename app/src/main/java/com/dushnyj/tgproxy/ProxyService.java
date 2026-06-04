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

    private static ProxyService instance;

    private ScreenStateReceiver screenReceiver;
    private boolean smartSleepEnabled = TgRoutePolicy.DEFAULT_SMART_SLEEP;
    private volatile boolean paused = false;
    private SharedPreferences prefs;

    private ConnectivityManager.NetworkCallback networkCallback;

    private Runnable pendingReconnect = null;
    private Runnable notificationTicker = null;
    private Bitmap notificationLargeIcon;
    private boolean recheckOnNetworkChange = true;
    private static final long RECONNECT_DEBOUNCE_MS = 2500;

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

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TGProxy::ProxyWake");

        isMobile = NetworkUtils.isMobileNetwork(this);
        registerNetworkCallback();
        registerScreenReceiver();
    }

    private void registerScreenReceiver() {
        smartSleepEnabled = prefs.getBoolean("smart_sleep", TgRoutePolicy.DEFAULT_SMART_SLEEP);
        if (!smartSleepEnabled) return;

        screenReceiver = new ScreenStateReceiver();
        screenReceiver.setCallback(new ScreenStateReceiver.Callback() {
            @Override public void onScreenOn() {
                if (paused && engine != null) resumeEngine();
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
                    boolean wasMobile = isMobile;
                    isMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                    if (engine != null) engine.setMobileNetwork(isMobile);
                    if (engine != null && recheckOnNetworkChange && wasMobile != isMobile) {
                        scheduleReconnect();
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
                    } else if (recheckOnNetworkChange) {
                        scheduleReconnect();
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
        pendingReconnect = () -> {
            pendingReconnect = null;
            if (engine != null && engine.isRunning() && !paused) {
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
        if (engine != null) engine.pause();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        refreshNotification();
    }

    private void resumeEngine() {
        paused = false;
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        if (engine != null) {
            new Thread(() -> {
                try { engine.resume(port); } catch (Exception ignored) {}
            }).start();
        }
        refreshNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RESUME.equals(intent.getAction())) {
            if (paused && engine != null) resumeEngine();
            refreshNotification();
            return START_STICKY;
        }

        if (engine != null) { engine.stop(); engine = null; }

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        port = prefs.getInt("custom_port", MtProtoConfig.DEFAULT_PORT);
        if (port < 1 || port > 65535) port = MtProtoConfig.DEFAULT_PORT;

        boundIp = prefs.getString("custom_ip", MtProtoConfig.DEFAULT_HOST);
        if (boundIp == null || boundIp.trim().isEmpty()) boundIp = MtProtoConfig.DEFAULT_HOST;

        smartSleepEnabled = prefs.getBoolean("smart_sleep", TgRoutePolicy.DEFAULT_SMART_SLEEP);
        recheckOnNetworkChange = prefs.getBoolean("cf_recheck_network", true);

        startTime = System.currentTimeMillis();
        paused = false;
        startForeground(NOTIF_ID, buildNotification());

        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();

        isMobile = NetworkUtils.isMobileNetwork(this);

        engine = new MtProtoProxyEngine();
        engine.setBoundIp(boundIp);
        engine.setSecretHex(prefs.getString("mtproto_secret", MtProtoConfig.generateSecretHex()));
        engine.setDcRules(prefs.getString("dc_rules", MtProtoConfig.DEFAULT_DC_RULES));
        engine.setCfProxyMode(storedCfProxyMode());
        engine.setCfProxyDomains(splitDomains(prefs.getString("cfproxy_domains", "")));
        engine.setCfWarmupEnabled(prefs.getBoolean("cf_warmup", true));
        engine.setCfWorkerDomains(splitDomains(prefs.getString("worker_domains", "")));
        engine.setVerbose(prefs.getBoolean("verbose_logging", false));
        engine.setMobileNetwork(isMobile);

        new Thread(() -> {
            try { engine.start(port); }
            catch (Exception e) { handler.post(this::stopSelf); }
        }).start();
        startNotificationTicker();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        cancelPendingReconnect();
        stopNotificationTicker();
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
        CharSequence compact = colorTrafficInText(address + " • " + traffic, address.length() + 3);
        CharSequence details = colorTrafficInText(
                getString(R.string.notification_address_line, address)
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

    private void startNotificationTicker() {
        stopNotificationTicker();
        notificationTicker = new Runnable() {
            @Override public void run() {
                refreshNotification();
                if (engine != null) {
                    handler.postDelayed(this, 2000);
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

    private static java.util.List<String> splitDomains(String text) {
        if (text == null || text.trim().isEmpty()) return java.util.Collections.emptyList();
        return Arrays.asList(text.replace(',', ' ').replace(';', ' ').trim().split("\\s+"));
    }
}
