package com.dushnyj.tgproxy;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.assertEquals;

public class RouteErrorTest {
    @Test
    public void classifiesKnownNetworkFailures() {
        assertEquals(RouteError.TOO_MANY_REQUESTS,
                RouteError.classify(new IOException("WS handshake failed: 429")));
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
}
