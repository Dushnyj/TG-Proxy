package com.dushnyj.tgproxy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class DnsAddressVerifier {
    private DnsAddressVerifier() {}

    static boolean matches(String host, String expectedIp) {
        try {
            byte[] expected = InetAddress.getByName(expectedIp).getAddress();
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (java.util.Arrays.equals(expected, address.getAddress())) return true;
            }
        } catch (Exception ignored) {
            // Try independent resolvers below. This also avoids stale carrier DNS caches after a
            // freshly created DuckDNS record.
        }
        return matchesDoh("https://cloudflare-dns.com/dns-query", host, expectedIp)
                || matchesDoh("https://dns.google/resolve", host, expectedIp);
    }

    static boolean waitUntilMatches(String host, String expectedIp,
                                    long timeoutMs, long intervalMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        do {
            if (matches(host, expectedIp)) return true;
            if (System.currentTimeMillis() >= deadline) break;
            try {
                Thread.sleep(Math.max(250L, intervalMs));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (true);
        return false;
    }

    private static boolean matchesDoh(String endpoint, String host, String expectedIp) {
        HttpURLConnection connection = null;
        try {
            String normalizedHost = VpsEndpointPolicy.normalizeHost(host);
            String normalizedIp = VpsEndpointPolicy.normalizeHost(expectedIp);
            if (!VpsEndpointPolicy.isDomain(normalizedHost)
                    || !VpsEndpointPolicy.isIpLiteral(normalizedIp)) return false;
            String type = normalizedIp.contains(":") ? "AAAA" : "A";
            String separator = endpoint.contains("?") ? "&" : "?";
            URL url = new URL(endpoint + separator + "name="
                    + URLEncoder.encode(normalizedHost, "UTF-8") + "&type=" + type);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(4_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/dns-json");
            connection.setRequestProperty("User-Agent", "TG-Proxy-Android/"
                    + BuildConfig.VERSION_NAME);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[512];
                int read;
                while ((read = reader.read(buffer)) >= 0 && body.length() < 32_768) {
                    body.append(buffer, 0, Math.min(read, 32_768 - body.length()));
                }
            }
            String compact = body.toString().toLowerCase(Locale.US)
                    .replace(" ", "").replace("\n", "").replace("\r", "");
            return compact.contains("\"data\":\"" + normalizedIp.toLowerCase(Locale.US) + "\"");
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
