package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConnectBudgetTest {
    @Test
    public void capsEachAttemptAndEventuallyExpiresWholeFallbackPlan() {
        AtomicLong now = new AtomicLong(10L);
        ConnectBudget budget = new ConnectBudget(22_000L, now::get);

        assertEquals(7_000, budget.remainingTimeoutMs(7_000));
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(20_000L));
        assertEquals(2_000, budget.remainingTimeoutMs(7_000));
        assertTrue(budget.hasTime());

        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(2_001L));
        assertEquals(0, budget.remainingTimeoutMs(7_000));
        assertFalse(budget.hasTime());
    }
}
