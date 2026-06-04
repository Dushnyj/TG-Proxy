package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public final class GithubReleaseUpdater {
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/Dushnyj/TG-Proxy/releases/latest";

    public interface Callback {
        void onResult(ReleaseInfo info);
        void onError(Exception error);
    }

    public interface InstallCallback {
        void onPermissionRequired(Intent intent);
        void onProgress(long downloaded, long total, long bytesPerSecond);
        void onInstallerStarted();
        void onError(Exception error);
    }

    public static void checkLatest(Callback callback) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("User-Agent", "TG-Proxy-Android");
                String body = readAll(conn.getInputStream());
                JSONObject json = new JSONObject(body);
                String tag = json.optString("tag_name", "");
                String htmlUrl = json.optString("html_url", "https://github.com/Dushnyj/TG-Proxy/releases");
                JSONArray assets = json.optJSONArray("assets");
                String apkUrl = selectReleaseApkUrl(assets);
                callback.onResult(new ReleaseInfo(cleanVersion(tag), htmlUrl, apkUrl));
            } catch (Exception e) {
                callback.onError(e);
            }
        }, "tg-update-check").start();
    }

    public static void downloadAndInstall(Context context, ReleaseInfo info, InstallCallback callback) {
        new Thread(() -> {
            try {
                if (info == null || info.apkUrl == null || info.apkUrl.isEmpty()) {
                    throw new IllegalArgumentException("release APK is missing");
                }
                if (!canInstallPackages(context)) {
                    callback.onPermissionRequired(installPermissionIntent(context));
                    return;
                }

                File dir = new File(context.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdir failed");
                File apk = new File(dir, "TG-Proxy-v" + info.version + "-android-universal-release.apk");

                HttpURLConnection conn = (HttpURLConnection) new URL(info.apkUrl).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(60_000);
                conn.setRequestProperty("User-Agent", "TG-Proxy-Android");
                long total = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    long downloaded = 0;
                    long lastBytes = 0;
                    long lastTime = System.currentTimeMillis();
                    callback.onProgress(0, total, 0);
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        long now = System.currentTimeMillis();
                        if (now - lastTime >= 500) {
                            long speed = (downloaded - lastBytes) * 1000 / Math.max(1, now - lastTime);
                            callback.onProgress(downloaded, total, speed);
                            lastBytes = downloaded;
                            lastTime = now;
                        }
                    }
                    long elapsed = Math.max(1, System.currentTimeMillis() - lastTime);
                    long speed = (downloaded - lastBytes) * 1000 / elapsed;
                    callback.onProgress(downloaded, total, speed);
                }

                Uri uri = FileProvider.getUriForFile(context,
                        context.getPackageName() + ".provider", apk);
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(uri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                callback.onInstallerStarted();
                context.startActivity(install);
            } catch (Exception e) {
                callback.onError(e);
            }
        }, "tg-update-install").start();
    }

    public static boolean canInstallPackages(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || context.getPackageManager().canRequestPackageInstalls();
    }

    public static Intent installPermissionIntent(Context context) {
        Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
        permission.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return permission;
    }

    static String selectReleaseApkUrl(JSONArray assets) throws Exception {
        if (assets == null) return "";
        String[] names = new String[assets.length()];
        String[] urls = new String[assets.length()];
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            names[i] = asset.optString("name", "");
            urls[i] = asset.optString("browser_download_url", "");
        }
        return selectReleaseApkUrl(names, urls);
    }

    static String selectReleaseApkUrl(String[] names, String[] urls) {
        String fallback = "";
        int count = Math.min(names == null ? 0 : names.length, urls == null ? 0 : urls.length);
        for (int i = 0; i < count; i++) {
            String name = names[i] == null ? "" : names[i].toLowerCase(Locale.US);
            String url = urls[i] == null ? "" : urls[i];
            if (!name.endsWith(".apk") || name.contains("debug") || url.isEmpty()) continue;
            if (name.contains("universal") && name.contains("release")) return url;
            if (fallback.isEmpty()) fallback = url;
        }
        return fallback;
    }

    public static boolean isNewerVersion(String candidate, String current) {
        int[] c = parseVersion(candidate);
        int[] cur = parseVersion(current);
        for (int i = 0; i < Math.max(c.length, cur.length); i++) {
            int a = i < c.length ? c[i] : 0;
            int b = i < cur.length ? cur[i] : 0;
            if (a > b) return true;
            if (a < b) return false;
        }
        return false;
    }

    private static int[] parseVersion(String value) {
        String clean = cleanVersion(value);
        String[] parts = clean.split("\\.");
        int[] result = new int[Math.max(3, parts.length)];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (Exception ignored) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static String cleanVersion(String value) {
        if (value == null) return "0.0.0";
        String clean = value.trim().toLowerCase(Locale.US);
        return clean.startsWith("v") ? clean.substring(1) : clean;
    }

    private static String readAll(InputStream in) throws Exception {
        byte[] buf = new byte[16 * 1024];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        return sb.toString();
    }

    public static final class ReleaseInfo {
        public final String version;
        public final String htmlUrl;
        public final String apkUrl;

        ReleaseInfo(String version, String htmlUrl, String apkUrl) {
            this.version = version;
            this.htmlUrl = htmlUrl;
            this.apkUrl = apkUrl;
        }
    }

    private GithubReleaseUpdater() {
    }
}
