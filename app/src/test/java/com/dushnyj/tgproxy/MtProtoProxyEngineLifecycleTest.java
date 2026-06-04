package com.dushnyj.tgproxy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class MtProtoProxyEngineLifecycleTest {
    @Test
    public void failedStartDoesNotLeaveEngineRunning() {
        MtProtoProxyEngine engine = new MtProtoProxyEngine();
        engine.setBoundIp("203.0.113.255");

        try {
            engine.start(0);
            fail("start should fail for an unavailable bind address");
        } catch (Exception expected) {
            assertFalse(engine.isRunning());
            assertFalse(engine.isListening());
        } finally {
            engine.stop();
        }
    }
}
