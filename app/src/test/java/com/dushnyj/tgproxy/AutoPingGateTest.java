package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoPingGateTest {
    @Test
    public void gateUsesThreeSecondCadenceWithoutOverlap() {
        AutoPingGate gate = new AutoPingGate(3_000L);

        long first = gate.tryStart("route-a", 1_000L);
        assertTrue(first > 0L);
        assertEquals(0L, gate.tryStart("route-a", 2_000L));

        assertTrue(gate.finish(first));

        assertEquals(0L, gate.tryStart("route-a", 3_999L));
        assertTrue(gate.tryStart("route-a", 4_000L) > 0L);
    }

    @Test
    public void runningProbeBlocksOtherRoutesToo() {
        AutoPingGate gate = new AutoPingGate(3_000L);

        assertTrue(gate.tryStart("route-a", 10_000L) > 0L);
        assertEquals(0L, gate.tryStart("route-b", 14_000L));
    }

    @Test
    public void staleCompletionAfterResetCannotFinishNewAttempt() {
        AutoPingGate gate = new AutoPingGate(0L);
        long oldToken = gate.tryStart("route-a", 1_000L);
        gate.reset();
        long newToken = gate.tryStart("route-a", 1_001L);

        assertFalse(gate.finish(oldToken));
        assertEquals(0L, gate.tryStart("route-a", 1_002L));
        assertTrue(gate.finish(newToken));
    }
}
