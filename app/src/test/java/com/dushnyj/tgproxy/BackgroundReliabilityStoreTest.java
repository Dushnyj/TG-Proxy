package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackgroundReliabilityStoreTest {
    @Test
    public void readinessRequiresEveryDisplayedCondition() {
        assertTrue(new BackgroundReliabilityStore.Status(
                true, true, true, true, true, true).ready());

        for (int missing = 0; missing < 6; missing++) {
            boolean[] values = {true, true, true, true, true, true};
            values[missing] = false;
            assertFalse("condition " + missing + " must be required",
                    new BackgroundReliabilityStore.Status(
                            values[0], values[1], values[2], values[3],
                            values[4], values[5]).ready());
        }
    }
}
