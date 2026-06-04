package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsSetupRequestTest {
    @Test
    public void ipOnlyTlsDoesNotEnterReverseProxyMode() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("203.0.113.10")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        assertFalse(request.relayHostIsDomain());
        assertFalse(request.reverseProxyMode());
    }

    @Test
    public void domainTlsCanEnterReverseProxyMode() {
        VpsSetupRequest request = VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(443)
                .relayTls(true)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.0")
                .build();

        assertTrue(request.relayHostIsDomain());
        assertTrue(request.reverseProxyMode());
    }
}

