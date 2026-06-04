package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoPingGateTest {
    @Test
    public void gateUsesThreeSecondCadenceWithoutOverlap() {
        AutoPingGate gate = new AutoPingGate(3_000L);

        assertTrue(gate.tryStart("route-a", 1_000L));
        assertFalse(gate.tryStart("route-a", 2_000L));

        gate.finish("route-a");

        assertFalse(gate.tryStart("route-a", 3_999L));
        assertTrue(gate.tryStart("route-a", 4_000L));
    }

    @Test
    public void runningProbeBlocksOtherRoutesToo() {
        AutoPingGate gate = new AutoPingGate(3_000L);

        assertTrue(gate.tryStart("route-a", 10_000L));
        assertFalse(gate.tryStart("route-b", 14_000L));
    }
}
