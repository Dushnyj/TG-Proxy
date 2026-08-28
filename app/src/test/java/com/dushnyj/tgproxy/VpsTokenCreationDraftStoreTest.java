package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpsTokenCreationDraftStoreTest {
    @Test
    public void validDraftCarriesRecoverableSecretAndIdempotencyKey() {
        VpsTokenCreationDraftStore.Draft draft = new VpsTokenCreationDraftStore.Draft(
                "ri_0123456789abcdef0123456789abcdef", "Телефон",
                "tgpr_0123456789abcdefghijklmnopqrstuvwxyzABCDE",
                "req_0123456789abcdef0123456789abcdef", 1L);

        assertTrue(draft.valid());
    }

    @Test
    public void malformedOrUnnamedDraftCannotBeSent() {
        assertFalse(new VpsTokenCreationDraftStore.Draft(
                "server", "", "tgpr_short", "bad key", 1L).valid());
    }
}
