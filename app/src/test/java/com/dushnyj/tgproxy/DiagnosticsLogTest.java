package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosticsLogTest {
    @Test
    public void clearDropsCurrentDiagnosticHistory() {
        DiagnosticsLog.clearForTests();
        DiagnosticsLog.record("route switched");

        assertFalse(DiagnosticsLog.snapshot().isEmpty());

        DiagnosticsLog.clear();

        assertTrue(DiagnosticsLog.snapshot().isEmpty());
    }
}
