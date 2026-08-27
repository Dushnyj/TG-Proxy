package com.dushnyj.tgproxy;

import java.util.Locale;

final class VpsSetupUiPolicy {
    private VpsSetupUiPolicy() {}

    static boolean initialTlsChecked(String host, boolean currentTls, int currentPort) {
        String value = host == null ? "" : host.trim();
        return currentTls && currentPort == 443
                && (value.isEmpty() || useTlsEndpoint(value, true));
    }

    static boolean useTlsDomain(String host, boolean tlsChecked) {
        return tlsChecked && isDomainHost(host);
    }

    static boolean useTlsEndpoint(String host, boolean tlsChecked) {
        return tlsChecked && (isDomainHost(host)
                || VpsEndpointPolicy.isIpLiteral(VpsEndpointPolicy.normalizeHost(host)));
    }

    static int effectiveRelayPort(String host, int requestedPort, boolean tlsChecked) {
        if (useTlsEndpoint(host, tlsChecked)) return 443;
        if (requestedPort == 443) return 18080;
        return requestedPort <= 0 || requestedPort > 65535 ? 18080 : requestedPort;
    }

    static boolean isDomainHost(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.US);
        if (value.startsWith("https://")) value = value.substring("https://".length());
        else if (value.startsWith("http://")) value = value.substring("http://".length());
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int colon = value.indexOf(':');
        if (colon > 0 && value.indexOf(']') < 0) value = value.substring(0, colon);
        return value.contains(".") && !value.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }
}
