package com.dushnyj.tgproxy;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RouteProbeClientTest {
    @Test
    public void localDeadlineCloseIsReportedAsProbeTimeout() {
        IOException closed = new IOException("Socket closed");

        IOException normalized = RouteProbeClient.normalizeReadFailure(closed, false);

        assertTrue(normalized instanceof SocketTimeoutException);
        assertEquals("telegram probe deadline exceeded", normalized.getMessage());
        assertSame(closed, normalized.getCause());
    }

    @Test
    public void readFailureBeforeDeadlineIsPreserved() {
        IOException reset = new IOException("Connection reset");

        assertSame(reset, RouteProbeClient.normalizeReadFailure(reset, true));
    }

    @Test
    public void peerWebSocketCloseIsPreservedEvenAtDeadline() {
        IOException close = new RawWebSocket.WebSocketCloseException(1011, "upstream", true);

        assertSame(close, RouteProbeClient.normalizeReadFailure(close, false));
    }

    @Test
    public void exhaustedLoopWithoutReadExceptionIsReportedAsProbeTimeout() {
        IOException failure = RouteProbeClient.invalidResponseFailure(false);

        assertTrue(failure instanceof SocketTimeoutException);
        assertEquals("telegram probe deadline exceeded", failure.getMessage());
    }

    @Test
    public void invalidResponseBeforeDeadlineRemainsProtocolFailure() {
        IOException failure = RouteProbeClient.invalidResponseFailure(true);

        assertEquals(IOException.class, failure.getClass());
        assertEquals("telegram dc response is invalid", failure.getMessage());
    }
}
