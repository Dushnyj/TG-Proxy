package com.dushnyj.tgproxy;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

import javax.net.ssl.HttpsURLConnection;

final class DuckDnsClient {
    private static final String ENDPOINT = "https://www.duckdns.org/update";

    boolean update(String domain, String token, String ipv4) throws Exception {
        String url = updateUrl(domain, token, ipv4);
        try {
            HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/plain");
            connection.setRequestProperty("User-Agent", "TG-Proxy-Android/" + BuildConfig.VERSION_NAME);
            int code = connection.getResponseCode();
            String response;
            try (InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream()) {
                response = readLimited(stream, 1024);
            } finally {
                connection.disconnect();
            }
            return code == HttpURLConnection.HTTP_OK
                    && ("OK".equalsIgnoreCase(response.trim())
                    || response.toUpperCase(java.util.Locale.US).startsWith("OK\n"));
        } catch (Exception error) {
            // URL contains the owner-only DuckDNS token. Never propagate a provider/URL message
            // into UI, diagnostics, crash reports, or logs.
            throw new IOException("DuckDNS request failed", error);
        }
    }

    static String updateUrl(String domain, String token, String ipv4) throws Exception {
        String subdomain = VpsEndpointPolicy.duckDnsSubdomain(domain);
        String secret = token == null ? "" : token.trim();
        String ip = ipv4 == null ? "" : ipv4.trim();
        if (subdomain.isEmpty() || secret.isEmpty() || !VpsEndpointPolicy.isIpLiteral(ip)) {
            throw new IllegalArgumentException("invalid DuckDNS update");
        }
        return ENDPOINT + "?domains=" + encode(subdomain)
                + "&token=" + encode(secret)
                + "&ip=" + encode(ip);
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static String readLimited(InputStream input, int limit) throws Exception {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[256];
            int read;
            while ((read = reader.read(buffer)) >= 0 && out.length() < limit) {
                out.append(buffer, 0, Math.min(read, limit - out.length()));
            }
        }
        return out.toString();
    }
}
