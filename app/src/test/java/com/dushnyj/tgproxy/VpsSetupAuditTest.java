package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VpsSetupAuditTest {
    @Test
    public void discoveredDomainsAreParsedDedupedAndSortedByAuditOrder() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "discovered_domains=one.duckdns.org,two.duckdns.org,one.duckdns.org, invalid,three.example.com\n");

        assertEquals(Arrays.asList("one.duckdns.org", "two.duckdns.org", "three.example.com"),
                audit.discoveredDomains());
    }

    @Test
    public void existingRelayTokenInventoryIsParsedWithoutDuplicates() {
        VpsSetupAudit audit = VpsSetupAudit.parse(
                "existing_relay_token_ids_known=yes\n"
                        + "existing_relay_token_ids=tok_phone,cfg_0123456789abcdef,tok_phone\n");

        assertTrue(audit.existingRelayTokenInventoryKnown());
        assertEquals(Arrays.asList("tok_phone", "cfg_0123456789abcdef"),
                audit.existingRelayTokenIds());
    }
}

