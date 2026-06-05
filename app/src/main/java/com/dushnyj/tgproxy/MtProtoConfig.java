package com.dushnyj.tgproxy;

import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class MtProtoConfig {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 1443;
    public static final String DEFAULT_DC_RULES = "2:149.154.167.220\n4:149.154.167.220";
    public static final String DEFAULT_RELAY_DC_RULES =
            "1:149.154.175.50\n"
                    + "2:149.154.167.51\n"
                    + "3:149.154.175.100\n"
                    + "4:149.154.167.91\n"
                    + "5:149.154.171.5\n"
                    + "203:91.105.192.100";
    public static final int DEFAULT_BUFFER_KB = 256;
    public static final int DEFAULT_POOL_SIZE = 4;

    private static final SecureRandom RNG = new SecureRandom();

    public static String generateSecretHex() {
        byte[] secret = new byte[16];
        RNG.nextBytes(secret);
        return toHex(secret);
    }

    public static String normalizeSecretHex(String value) {
        if (value == null) return generateSecretHex();
        String secret = value.trim().toLowerCase(Locale.US);
        if (secret.startsWith("dd") && secret.length() == 34) {
            secret = secret.substring(2);
        }
        if (!secret.matches("[0-9a-f]{32}")) {
            return generateSecretHex();
        }
        return secret;
    }

    public static String telegramProxyLink(String host, int port, String secretHex) {
        try {
            String encodedHost = URLEncoder.encode(host, "UTF-8");
            return "tg://proxy?server=" + encodedHost
                    + "&port=" + port
                    + "&secret=dd" + normalizeSecretHex(secretHex);
        } catch (Exception ignored) {
            return "tg://proxy?server=" + host
                    + "&port=" + port
                    + "&secret=dd" + normalizeSecretHex(secretHex);
        }
    }

    public static Map<Integer, String> parseDcRules(String text) {
        return parseDcRules(text, true, false);
    }

    public static Map<Integer, String> parseUserDcRules(String text) {
        return parseDcRules(text, false, true);
    }

    public static Map<Integer, String> relayDcRules() {
        return parseDcRules(DEFAULT_RELAY_DC_RULES, false, false);
    }

    private static Map<Integer, String> parseDcRules(String text, boolean allowDefault,
                                                     boolean rejectDuplicates) {
        LinkedHashMap<Integer, String> result = new LinkedHashMap<>();
        String source = text == null ? "" : text.trim();
        if (source.isEmpty()) {
            if (!allowDefault) throw new IllegalArgumentException("No DC rules");
            source = DEFAULT_DC_RULES;
        }
        for (String raw : source.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0 || colon == line.length() - 1) {
                throw new IllegalArgumentException("Invalid DC rule: " + line);
            }
            int dc;
            try {
                dc = Integer.parseInt(line.substring(0, colon).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid DC id: " + line);
            }
            String ip = line.substring(colon + 1).trim();
            if (!isValidDc(dc) || !isIpv4(ip)) {
                throw new IllegalArgumentException("Invalid DC rule: " + line);
            }
            if (rejectDuplicates && result.containsKey(dc)) {
                throw new IllegalArgumentException("Duplicate DC rule: " + dc);
            }
            result.put(dc, ip);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No DC rules");
        }
        return result;
    }

    public static String formatDcRules(Map<Integer, String> rules) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> entry : rules.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return sb.toString();
    }

    public static byte[] secretBytes(String secretHex) {
        return fromHex(normalizeSecretHex(secretHex));
    }

    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] fromHex(String value) {
        String hex = value.trim();
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static boolean isIpv4(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                int v = Integer.parseInt(part);
                if (v < 0 || v > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    static boolean isValidDc(int dc) {
        return (dc >= 1 && dc <= 5) || dc == 203;
    }

    private MtProtoConfig() {
    }
}
