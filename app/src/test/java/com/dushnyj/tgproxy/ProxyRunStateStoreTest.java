package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProxyRunStateStoreTest {
    @Test
    public void explicitUserIntentSurvivesIndependentOfServiceInstance() {
        ProxyRunStateStore store = ProxyRunStateStore.inMemory();
        assertFalse(store.hasDesiredState());
        assertFalse(store.desiredRunning());
        assertTrue(store.setDesiredRunning(true));
        assertTrue(store.hasDesiredState());
        assertTrue(store.desiredRunning());
        assertTrue(store.setDesiredRunning(false));
        assertFalse(store.desiredRunning());
    }
}
