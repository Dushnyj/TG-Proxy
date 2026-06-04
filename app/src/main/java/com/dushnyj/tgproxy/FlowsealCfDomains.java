package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FlowsealCfDomains {
    private static final List<String> ENCODED = Arrays.asList(
            "virkgj.com",
            "vmmzovy.com",
            "mkuosckvso.com",
            "zaewayzmplad.com",
            "twdmbzcm.com",
            "awzwsldi.com",
            "clngqrflngqin.com",
            "tjacxbqtj.com",
            "bxaxtxmrw.com",
            "dmohrsgmohcrwb.com"
    );

    public static List<String> defaults() {
        ArrayList<String> result = new ArrayList<>();
        for (String encoded : ENCODED) {
            result.add(decode(encoded));
        }
        return result;
    }

    static String decode(String encoded) {
        if (encoded == null || !encoded.endsWith(".com")) return encoded;
        String prefix = encoded.substring(0, encoded.length() - 4);
        int alphaCount = 0;
        for (int i = 0; i < prefix.length(); i++) {
            if (Character.isLetter(prefix.charAt(i))) alphaCount++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!Character.isLetter(c)) {
                sb.append(c);
                continue;
            }
            char base = Character.isUpperCase(c) ? 'A' : 'a';
            int shifted = (c - base - alphaCount) % 26;
            if (shifted < 0) shifted += 26;
            sb.append((char) (base + shifted));
        }
        sb.append(".co.uk");
        return sb.toString();
    }

    private FlowsealCfDomains() {
    }
}
