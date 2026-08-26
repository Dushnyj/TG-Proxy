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

    @Test
    public void invalidScriptInputsAreRejectedBeforeSshExecution() {
        assertFalse(validRequest().relayPath("/apiws;include").build().isValid());
        assertFalse(validRequest().relayPath("/apiws%").build().isValid());
        assertFalse(validRequest().relayPath("/test-routes").build().isValid());
        assertFalse(validRequest().relayToken("token with spaces").build().isValid());
        assertFalse(validRequest().releaseVersion("1.0.4/other").build().isValid());
        assertFalse(validRequest().relayHost("relay.example.com\ninvalid").build().isValid());
    }

    @Test
    public void ipv6LiteralIsNormalizedAndRenderedWithBrackets() {
        VpsSetupRequest request = validRequest()
                .relayHost("[2001:db8::1]:18080")
                .build();

        assertTrue(request.isValid());
        assertFalse(request.relayHostIsDomain());
        assertTrue(request.publicUrl().startsWith("http://[2001:db8::1]:18080/"));
    }

    private static VpsSetupRequest.Builder validRequest() {
        return VpsSetupRequest.builder()
                .sshHost("203.0.113.10")
                .sshUser("root")
                .relayHost("relay.example.com")
                .relayPort(18080)
                .relayTls(false)
                .relayPath("/apiws")
                .relayToken("token")
                .releaseVersion("1.0.4");
    }
}

