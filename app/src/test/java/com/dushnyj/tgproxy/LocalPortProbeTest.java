package com.dushnyj.tgproxy;

import org.junit.Test;

import java.net.ServerSocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalPortProbeTest {
    @Test
    public void detectsListeningLoopbackPort() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            assertTrue(LocalPortProbe.isListening("127.0.0.1", server.getLocalPort(), 500));
        }
    }

    @Test
    public void closedPortIsNotListening() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0)) {
            port = server.getLocalPort();
        }

        assertFalse(LocalPortProbe.isListening("127.0.0.1", port, 200));
    }
}
