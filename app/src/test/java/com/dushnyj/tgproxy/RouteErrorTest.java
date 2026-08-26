package com.dushnyj.tgproxy;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteErrorTest {
    @Test
    public void classifiesKnownNetworkFailures() {
        assertEquals(RouteError.TOO_MANY_REQUESTS,
                RouteError.classify(new IOException("WS handshake failed: 429")));
        assertEquals(RouteError.HTTP_UNAVAILABLE,
                RouteError.classify(new IOException("WS handshake failed: 503")));
        assertEquals(RouteError.HTTP_FORBIDDEN,
                RouteError.classify(new RawWebSocket.WsHandshakeException(403)));
        assertEquals(RouteError.RELAY_AUTH,
                RouteError.classify(new RawWebSocket.WsHandshakeException(401)));
        assertEquals(RouteError.WS_PROTOCOL,
                RouteError.classify(new RawWebSocket.WsProtocolException("bad continuation")));
        assertEquals(RouteError.TIMEOUT,
                RouteError.classify(new SocketTimeoutException("connect timed out")));
        assertEquals(RouteError.RESET,
                RouteError.classify(new IOException("Connection reset")));
        assertEquals(RouteError.DNS,
                RouteError.classify(new UnknownHostException("kws2.example")));
        assertEquals(RouteError.TLS,
                RouteError.classify(new SSLHandshakeException("cert path failed")));
        assertEquals(RouteError.IO,
                RouteError.classify(new IOException("broken pipe")));
    }

    @Test
    public void nullFailureIsUnknown() {
        assertEquals(RouteError.UNKNOWN, RouteError.classify(null));
    }

    @Test
    public void distinguishesCleanWebSocketSessionCloseFromTransportFailure() {
        assertTrue(RouteError.isCleanSessionClose(
                new RawWebSocket.WebSocketCloseException(1000, "telegram closed", true)));
        assertTrue(RouteError.isCleanSessionClose(
                new RawWebSocket.WebSocketCloseException(1001, "going away", true)));
        assertFalse(RouteError.isCleanSessionClose(
                new RawWebSocket.WebSocketCloseException(1011, "upstream failed", true)));
        assertFalse(RouteError.isCleanSessionClose(new IOException("remote EOF")));
    }
}
