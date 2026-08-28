package com.dushnyj.tgproxy;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Builds one consistent, user-facing Android device label for headers, diagnostics and UI. */
final class DeviceDisplayName {
    private static final Map<String, String> MODEL_NAMES = new HashMap<>();

    static {
        // Verified retail names for model identifiers observed in the owner UI. Unknown
        // identifiers deliberately stay visible instead of being guessed.
        MODEL_NAMES.put("2407FPN8EG", "Xiaomi 14T Pro");
        MODEL_NAMES.put("SM-S926B", "Samsung Galaxy S24+");
    }

    private DeviceDisplayName() {}

    static Identity resolve(String manufacturer, String brand, String model,
                            String deviceName) {
        String maker = canonicalBrand(manufacturer);
        String retailBrand = canonicalBrand(brand);
        String rawModel = clean(model);
        String explicit = MODEL_NAMES.get(rawModel.toUpperCase(Locale.US));
        String friendly = usefulDeviceName(deviceName, rawModel)
                && looksLikeRetailName(deviceName, maker, retailBrand)
                ? clean(deviceName) : "";
        // A verified model mapping is more reliable than Settings.Global.DEVICE_NAME: the latter
        // is user-editable and can contain values such as "My phone" or a person's name.
        String marketing = explicit != null && !explicit.isEmpty() ? explicit : friendly;
        if (marketing == null || marketing.isEmpty()) {
            marketing = rawModel;
        }

        String consumerBrand = retailBrand;
        if (consumerBrand.isEmpty() || genericBrand(consumerBrand)) consumerBrand = maker;
        if (startsWithBrand(marketing, consumerBrand) || startsWithRetailFamily(marketing)) {
            return new Identity(consumerBrand, marketing);
        }
        if (!consumerBrand.isEmpty() && !marketing.isEmpty()) {
            marketing = consumerBrand + " " + marketing;
        } else if (marketing.isEmpty()) {
            marketing = consumerBrand;
        }
        return new Identity(consumerBrand, marketing);
    }

    static String format(String manufacturer, String brand, String model, String marketingName) {
        String marketing = clean(marketingName);
        if (!marketing.isEmpty()) return marketing;
        return resolve(manufacturer, brand, model, "").marketingName;
    }

    static String canonicalBrand(String raw) {
        String value = clean(raw);
        if (value.isEmpty()) return "";
        String key = value.toLowerCase(Locale.US).replace(" ", "");
        switch (key) {
            case "samsung": return "Samsung";
            case "xiaomi": return "Xiaomi";
            case "redmi": return "Redmi";
            case "poco": return "POCO";
            case "huawei": return "Huawei";
            case "honor": return "HONOR";
            case "oneplus": return "OnePlus";
            case "oppo": return "OPPO";
            case "realme": return "realme";
            case "vivo": return "vivo";
            case "google": return "Google";
            case "motorola": return "Motorola";
            case "nothing": return "Nothing";
            default:
                if (value.equals(value.toLowerCase(Locale.US))) {
                    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
                }
                return value;
        }
    }

    private static boolean startsWithBrand(String value, String brand) {
        if (brand.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.US);
        String prefix = brand.toLowerCase(Locale.US);
        return lower.equals(prefix) || lower.startsWith(prefix + " ") || lower.startsWith(prefix + "-");
    }

    private static boolean startsWithRetailFamily(String value) {
        String lower = value.toLowerCase(Locale.US);
        return lower.startsWith("redmi ") || lower.startsWith("poco ") ||
                lower.startsWith("honor ") || lower.startsWith("oneplus ") ||
                lower.startsWith("realme ") || lower.startsWith("oppo ") ||
                lower.startsWith("vivo ");
    }

    private static boolean looksLikeRetailName(String value, String maker, String brand) {
        String candidate = clean(value);
        if (startsWithBrand(candidate, maker) || startsWithBrand(candidate, brand)
                || startsWithRetailFamily(candidate)) return true;
        String lower = candidate.toLowerCase(Locale.US);
        // Galaxy and Pixel are product families rather than manufacturer names; retain them,
        // then prefix the canonical Samsung/Google brand in resolve().
        return lower.startsWith("galaxy ") || lower.startsWith("pixel ");
    }

    private static boolean usefulDeviceName(String candidate, String rawModel) {
        String value = clean(candidate);
        if (value.isEmpty() || value.equalsIgnoreCase(rawModel)) return false;
        String lower = value.toLowerCase(Locale.US);
        return !lower.equals("android") && !lower.equals("android device") &&
                !lower.equals("phone") && !lower.equals("my phone") &&
                !lower.matches("[a-f0-9]{8,}");
    }

    private static boolean genericBrand(String brand) {
        String lower = brand.toLowerCase(Locale.US);
        return lower.equals("android") || lower.equals("unknown") || lower.equals("generic");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    static final class Identity {
        final String brand;
        final String marketingName;

        Identity(String brand, String marketingName) {
            this.brand = clean(brand);
            this.marketingName = clean(marketingName);
        }
    }
}
