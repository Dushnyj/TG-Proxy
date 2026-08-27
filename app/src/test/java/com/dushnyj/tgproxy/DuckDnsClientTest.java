package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DuckDnsClientTest {
    @Test
    public void buildsOfficialHttpsUpdateRequestWithEncodedSecret() throws Exception {
        String url = DuckDnsClient.updateUrl(
                "my-relay.duckdns.org", "token+/= value", "203.0.113.10");

        assertTrue(url.startsWith("https://www.duckdns.org/update?"));
        assertTrue(url.contains("domains=my-relay"));
        assertTrue(url.contains("token=token%2B%2F%3D%20value"));
        assertTrue(url.contains("ip=203.0.113.10"));
        assertFalse(url.contains("http://"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDomainOutsideDuckDns() throws Exception {
        DuckDnsClient.updateUrl("relay.example.com", "secret", "203.0.113.10");
    }
}
