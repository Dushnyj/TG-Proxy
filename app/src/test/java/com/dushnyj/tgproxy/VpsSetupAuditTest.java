package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class VpsSetupAuditTest {
    @Test
    public void discoveredDomainsAreParsedDedupedAndSortedByAuditOrder() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "discovered_domains=one.duckdns.org,two.duckdns.org,one.duckdns.org, invalid,three.example.com\n");

        assertEquals(Arrays.asList("one.duckdns.org", "two.duckdns.org", "three.example.com"),
                audit.discoveredDomains());
    }
}

