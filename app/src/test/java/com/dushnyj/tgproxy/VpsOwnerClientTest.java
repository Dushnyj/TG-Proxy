package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsOwnerClientTest {
    @Test
    public void onlyLegacyUnknownFieldResponseAllowsNonIdempotentFallback() {
        assertTrue(VpsOwnerClient.legacyCreateRejectedUnknownFields(
                new VpsRelayClient.HttpResult(400, "invalid json\n")));
        assertFalse(VpsOwnerClient.legacyCreateRejectedUnknownFields(
                new VpsRelayClient.HttpResult(400, "invalid client secret\n")));
        assertFalse(VpsOwnerClient.legacyCreateRejectedUnknownFields(
                new VpsRelayClient.HttpResult(409,
                        "idempotency key belongs to a deleted token\n")));
        assertFalse(VpsOwnerClient.legacyCreateRejectedUnknownFields(null));
    }
}
