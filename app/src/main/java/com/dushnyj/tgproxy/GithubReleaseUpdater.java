package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class GithubReleaseUpdater {
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/Dushnyj/TG-Proxy/releases/latest";
    private static final long MAX_APK_BYTES = 300L * 1024L * 1024L;
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;

    public interface Callback {
        void onResult(ReleaseInfo info);
        void onError(Exception error);
    }

    public interface InstallCallback {
        void onPermissionRequired(Intent intent);
        void onProgress(long downloaded, long total, long bytesPerSecond);
        void onInstallerReady(Intent intent);
        void onError(Exception error);
    }

    public static void checkLatest(Callback callback) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("User-Agent", "TG-Proxy-Android");
                requireHttpSuccess(conn);
                String body;
                try (InputStream input = conn.getInputStream()) {
                    body = readAll(input, MAX_METADATA_BYTES);
                }
                JSONObject json = new JSONObject(body);
                String tag = json.optString("tag_name", "");
                String htmlUrl = json.optString("html_url", "https://github.com/Dushnyj/TG-Proxy/releases");
                JSONArray assets = json.optJSONArray("assets");
                ReleaseAsset apk = selectReleaseApk(assets);
                String checksumUrl = selectAssetUrl(assets, "SHA256SUMS.txt");
                callback.onResult(new ReleaseInfo(cleanVersion(tag), htmlUrl,
                        apk == null ? "" : apk.url,
                        apk == null ? "" : apk.name,
                        checksumUrl));
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
                String apkName = info.apkName == null || info.apkName.isEmpty()
                        ? "TG-Proxy-v" + info.version + "-android-universal-release.apk"
                        : info.apkName;
                apkName = requireSafeApkName(apkName);
                String expectedSha256 = fetchExpectedChecksum(info.checksumUrl, apkName);
                File apk = new File(dir, apkName);
                File partial = new File(dir, apkName + ".part");
                if (partial.exists() && !partial.delete()) {
                    throw new IllegalStateException("cannot remove partial APK");
                }

                URL apkUrl = new URL(info.apkUrl);
                requireHttps(apkUrl);
                HttpURLConnection conn = (HttpURLConnection) apkUrl.openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(60_000);
                conn.setRequestProperty("User-Agent", "TG-Proxy-Android");
                requireHttpSuccess(conn);
                long total = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? conn.getContentLengthLong()
                        : conn.getContentLength();
                if (total > MAX_APK_BYTES) throw new IllegalStateException("release APK is too large");
                long downloaded = 0;
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    long lastBytes = 0;
                    long lastTime = System.currentTimeMillis();
                    callback.onProgress(0, total, 0);
                    while ((n = in.read(buf)) != -1) {
                        if (downloaded + n > MAX_APK_BYTES) {
                            throw new IllegalStateException("release APK is too large");
                        }
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
                    out.getFD().sync();
                }
                if (downloaded <= 0 || (total >= 0 && downloaded != total)) {
                    throw new IllegalStateException("release APK download is incomplete");
                }
                String actualSha256 = sha256(partial);
                if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                    throw new SecurityException("release APK checksum mismatch");
                }
                verifyPackageIdentityAndSignature(context, partial, info.version);
                if (apk.exists() && !apk.delete()) {
                    throw new IllegalStateException("cannot replace cached APK");
                }
                if (!partial.renameTo(apk)) throw new IllegalStateException("cannot finalize APK");

                Uri uri = FileProvider.getUriForFile(context,
                        context.getPackageName() + ".provider", apk);
                Intent install = new Intent(installIntentAction());
                install.setDataAndType(uri, "application/vnd.android.package-archive");
                install.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
                install.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                callback.onInstallerReady(install);
            } catch (Exception e) {
                callback.onError(e);
            }
        }, "tg-update-install").start();
    }

    static String installIntentAction() {
        return Intent.ACTION_INSTALL_PACKAGE;
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
        ReleaseAsset selected = selectReleaseApk(assets);
        return selected == null ? "" : selected.url;
    }

    private static ReleaseAsset selectReleaseApk(JSONArray assets) throws Exception {
        if (assets == null) return null;
        String[] names = new String[assets.length()];
        String[] urls = new String[assets.length()];
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            names[i] = asset.optString("name", "");
            urls[i] = asset.optString("browser_download_url", "");
        }
        int index = selectReleaseApkIndex(names, urls);
        return index < 0 ? null : new ReleaseAsset(names[index], urls[index]);
    }

    static String selectReleaseApkUrl(String[] names, String[] urls) {
        int index = selectReleaseApkIndex(names, urls);
        return index < 0 ? "" : urls[index];
    }

    private static int selectReleaseApkIndex(String[] names, String[] urls) {
        int fallback = -1;
        int count = Math.min(names == null ? 0 : names.length, urls == null ? 0 : urls.length);
        for (int i = 0; i < count; i++) {
            String name = names[i] == null ? "" : names[i].toLowerCase(Locale.US);
            String url = urls[i] == null ? "" : urls[i];
            if (!name.endsWith(".apk") || name.contains("debug") || url.isEmpty()) continue;
            if (name.contains("universal") && name.contains("release")) return i;
            if (fallback < 0) fallback = i;
        }
        return fallback;
    }

    private static String selectAssetUrl(JSONArray assets, String expectedName) throws Exception {
        if (assets == null || expectedName == null) return "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (expectedName.equalsIgnoreCase(asset.optString("name", ""))) {
                return asset.optString("browser_download_url", "");
            }
        }
        return "";
    }

    private static String fetchExpectedChecksum(String checksumUrl, String apkName) throws Exception {
        if (checksumUrl == null || checksumUrl.isEmpty()) {
            throw new SecurityException("release checksum manifest is missing");
        }
        URL url = new URL(checksumUrl);
        requireHttps(url);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent", "TG-Proxy-Android");
        requireHttpSuccess(conn);
        String manifest;
        try (InputStream input = conn.getInputStream()) {
            manifest = readAll(input, MAX_METADATA_BYTES);
        }
        String checksum = checksumForAsset(manifest, apkName);
        if (checksum.isEmpty()) throw new SecurityException("release APK checksum is missing");
        return checksum;
    }

    static String checksumForAsset(String manifest, String assetName) {
        if (manifest == null || assetName == null || assetName.trim().isEmpty()) return "";
        for (String line : manifest.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length != 2 || !parts[0].matches("(?i)[0-9a-f]{64}")) continue;
            String listed = parts[1].trim();
            if (listed.startsWith("*")) listed = listed.substring(1);
            if (assetName.equals(listed)) return parts[0].toLowerCase(Locale.US);
        }
        return "";
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) out.append(String.format(Locale.US, "%02x", value & 0xff));
        return out.toString();
    }

    private static void verifyPackageIdentityAndSignature(Context context, File apk,
                                                          String expectedVersion) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || !context.getPackageName().equals(archive.packageName)) {
            throw new SecurityException("release APK package does not match");
        }
        String archiveVersion = archive.versionName == null ? "" : cleanVersion(archive.versionName);
        if (!cleanVersion(expectedVersion).equals(archiveVersion)) {
            throw new SecurityException("release APK version does not match");
        }
        Set<String> installedCurrent = signerDigests(installed, false);
        Set<String> candidateLineage = signerDigests(archive, true);
        candidateLineage.retainAll(installedCurrent);
        if (candidateLineage.isEmpty()) {
            throw new SecurityException("release APK signer does not match");
        }
    }

    private static Set<String> signerDigests(PackageInfo info, boolean includeHistory) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = includeHistory && !info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getSigningCertificateHistory()
                    : info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        Set<String> result = new HashSet<>();
        if (signatures == null) return result;
        for (Signature signature : signatures) {
            if (signature == null) continue;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(signature.toByteArray());
            StringBuilder out = new StringBuilder(64);
            for (byte value : bytes) out.append(String.format(Locale.US, "%02x", value & 0xff));
            result.add(out.toString());
        }
        return result;
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

    private static void requireHttpSuccess(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        requireHttps(connection.getURL());
        if (status < 200 || status >= 300) {
            throw new java.io.IOException("HTTP " + status);
        }
    }

    static String requireSafeApkName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 200
                || !name.toLowerCase(Locale.US).endsWith(".apk")
                || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new SecurityException("invalid release APK name");
        }
        for (int i = 0; i < name.length(); i++) {
            char value = name.charAt(i);
            if (value < 0x20 || value == 0x7f) {
                throw new SecurityException("invalid release APK name");
            }
        }
        return name;
    }

    private static void requireHttps(URL url) {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new SecurityException("release URL must use HTTPS");
        }
    }

    private static String readAll(InputStream in, int maxBytes) throws Exception {
        byte[] buf = new byte[16 * 1024];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() > maxBytes - n) throw new java.io.IOException("metadata is too large");
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static final class ReleaseInfo {
        public final String version;
        public final String htmlUrl;
        public final String apkUrl;
        public final String apkName;
        public final String checksumUrl;

        ReleaseInfo(String version, String htmlUrl, String apkUrl) {
            this(version, htmlUrl, apkUrl, "", "");
        }

        ReleaseInfo(String version, String htmlUrl, String apkUrl,
                    String apkName, String checksumUrl) {
            this.version = version;
            this.htmlUrl = htmlUrl;
            this.apkUrl = apkUrl;
            this.apkName = apkName == null ? "" : apkName;
            this.checksumUrl = checksumUrl == null ? "" : checksumUrl;
        }
    }

    private static final class ReleaseAsset {
        final String name;
        final String url;

        ReleaseAsset(String name, String url) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
        }
    }

    private GithubReleaseUpdater() {
    }
}
