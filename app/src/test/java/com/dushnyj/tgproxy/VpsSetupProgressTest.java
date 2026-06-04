package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VpsSetupProgressTest {
    @Test
    public void defaultAutoSetupStepsExposePercentAndHumanStatus() {
        List<VpsSetupProgress> steps = VpsSetupProgress.defaultSteps();

        assertEquals(6, steps.size());
        assertEquals(5, steps.get(0).percent());
        assertEquals(VpsSetupProgress.Stage.AUDIT, steps.get(0).stage());
        assertEquals(100, steps.get(5).percent());
        assertEquals(VpsSetupProgress.Stage.SAVE, steps.get(5).stage());
        assertTrue(steps.get(0).statusLine().contains("5%"));
        assertTrue(steps.get(0).statusLine().contains("аудит"));
    }

    @Test
    public void progressClampsPercentAndKeepsCurrentActionText() {
        VpsSetupProgress progress = VpsSetupProgress.of(
                VpsSetupProgress.Stage.INSTALL, 120, "Установка tgproxy-relay");

        assertEquals(100, progress.percent());
        assertEquals("Установка tgproxy-relay", progress.message());
        assertEquals("100% • Установка tgproxy-relay", progress.statusLine());
    }
}

