package com.dushnyj.tgproxy;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RawWebSocketTest {
    @Test
    public void relayPathCarriesNormalizedTestDcScope() {
        assertEquals("/private?dc=2&media=1&test=1",
                RawWebSocket.relayPath("/private", 2, true, true));
        assertEquals("/apiws?dc=4&media=0&test=0",
                RawWebSocket.relayPath("/apiws", 4, false));
    }
    @Test
    public void fragmentedBinaryMessageIsReassembled() throws Exception {
        MemorySocket socket = new MemorySocket(concat(
                serverFrame(false, 0x2, bytes("ab")),
                serverFrame(true, 0x0, bytes("cd"))));

        byte[] message = new RawWebSocket(socket).recv();

        assertArrayEquals(bytes("abcd"), message);
    }

    @Test
    public void pingBetweenFragmentsProducesPongWithoutCorruptingMessage() throws Exception {
        MemorySocket socket = new MemorySocket(concat(
                serverFrame(false, 0x2, bytes("ab")),
                serverFrame(true, 0x9, bytes("nonce")),
                serverFrame(true, 0x0, bytes("cd"))));

        byte[] message = new RawWebSocket(socket).recv();

        assertArrayEquals(bytes("abcd"), message);
        ClientFrame pong = clientFrame(socket.written());
        assertEquals(0xA, pong.opcode);
        assertTrue(pong.masked);
        assertArrayEquals(bytes("nonce"), pong.payload);
    }

    @Test
    public void oversizedFrameIsRejectedBeforePayloadAllocation() throws Exception {
        byte[] header = new byte[]{(byte) 0x82, 127, 0, 0, 0, 0, 1, 0, 0, 1};
        MemorySocket socket = new MemorySocket(header);

        try {
            new RawWebSocket(socket).recv();
            fail("oversized frame must fail");
        } catch (RawWebSocket.WsProtocolException expected) {
            assertTrue(expected.getMessage().contains("too large"));
            assertTrue(socket.closed);
        }
    }

    @Test
    public void invalidUnsigned64BitLengthIsRejected() throws Exception {
        byte[] header = new byte[]{(byte) 0x82, 127,
                (byte) 0x80, 0, 0, 0, 0, 0, 0, 1};

        try {
            new RawWebSocket(new MemorySocket(header)).recv();
            fail("invalid 64-bit length must fail");
        } catch (RawWebSocket.WsProtocolException expected) {
            assertTrue(expected.getMessage().contains("64-bit"));
        }
    }

    @Test
    public void sequentialFragmentedMessagesDoNotShareBuffers() throws Exception {
        MemorySocket socket = new MemorySocket(concat(
                serverFrame(false, 0x2, bytes("a")),
                serverFrame(true, 0x0, bytes("b")),
                serverFrame(false, 0x2, bytes("c")),
                serverFrame(true, 0x0, bytes("d"))));
        RawWebSocket ws = new RawWebSocket(socket);

        assertArrayEquals(bytes("ab"), ws.recv());
        assertArrayEquals(bytes("cd"), ws.recv());
    }

    @Test
    public void textFramesAreRejectedInsteadOfEnteringMtProtoStream() throws Exception {
        MemorySocket socket = new MemorySocket(serverFrame(true, 0x1, bytes("not binary")));

        try {
            new RawWebSocket(socket).recv();
            fail("text frame must fail");
        } catch (RawWebSocket.WsProtocolException expected) {
            assertTrue(expected.getMessage().contains("text frames"));
            assertTrue(socket.closed);
        }
    }

    @Test
    public void closeIsAcknowledgedAndPreservesPeerCode() throws Exception {
        MemorySocket socket = new MemorySocket(serverFrame(true, 0x8, new byte[]{0x03, (byte) 0xE8}));

        try {
            new RawWebSocket(socket).recv();
            fail("peer close must be structured");
        } catch (RawWebSocket.WebSocketCloseException expected) {
            assertEquals(1000, expected.code);
            assertTrue(expected.peerInitiated);
        }

        ClientFrame close = clientFrame(socket.written());
        assertEquals(0x8, close.opcode);
        assertArrayEquals(new byte[]{0x03, (byte) 0xE8}, close.payload);
        assertTrue(socket.closed);
    }

    @Test
    public void sendBatchCreatesIndependentMaskedBinaryFrames() throws Exception {
        MemorySocket socket = new MemorySocket(new byte[0]);
        RawWebSocket ws = new RawWebSocket(socket);

        ws.sendBatch(Arrays.asList(bytes("first"), bytes("second")));

        byte[] written = socket.written();
        ClientFrame first = clientFrame(written);
        ClientFrame second = clientFrame(Arrays.copyOfRange(written, first.frameLength, written.length));
        assertEquals(0x2, first.opcode);
        assertEquals(0x2, second.opcode);
        assertTrue(first.masked);
        assertTrue(second.masked);
        assertArrayEquals(bytes("first"), first.payload);
        assertArrayEquals(bytes("second"), second.payload);
    }

    @Test
    public void nonMinimalExtendedLengthIsRejected() throws Exception {
        MemorySocket socket = new MemorySocket(new byte[]{(byte) 0x82, 126, 0, 125});

        try {
            new RawWebSocket(socket).recv();
            fail("non-minimal length must fail");
        } catch (RawWebSocket.WsProtocolException expected) {
            assertTrue(expected.getMessage().contains("non-minimal"));
        }
    }

    @Test
    public void invalidOneByteClosePayloadIsRejected() throws Exception {
        MemorySocket socket = new MemorySocket(serverFrame(true, 0x8, new byte[]{1}));

        try {
            new RawWebSocket(socket).recv();
            fail("one-byte close payload must fail");
        } catch (RawWebSocket.WsProtocolException expected) {
            assertTrue(expected.getMessage().contains("close payload"));
        }
    }

    @Test
    public void invalidCloseUtf8UsesInvalidPayloadCloseCode() throws Exception {
        MemorySocket socket = new MemorySocket(serverFrame(true, 0x8,
                new byte[]{0x03, (byte) 0xE8, (byte) 0xC3, 0x28}));

        try {
            new RawWebSocket(socket).recv();
            fail("invalid UTF-8 close reason must fail");
        } catch (RawWebSocket.WsProtocolException expected) {
            ClientFrame close = clientFrame(socket.written());
            assertEquals(0x8, close.opcode);
            assertEquals(1007, ((close.payload[0] & 0xFF) << 8)
                    | (close.payload[1] & 0xFF));
        }
    }

    @Test
    public void reservedOpcodeIsRejectedBeforeReadingAdvertisedPayload() throws Exception {
        MemorySocket socket = new MemorySocket(new byte[]{(byte) 0x83, 125});

        try {
            new RawWebSocket(socket).recv();
            fail("reserved opcode must fail before payload read");
        } catch (RawWebSocket.WsProtocolException expected) {
            assertTrue(expected.getMessage().contains("unsupported websocket opcode"));
        }
    }

    @Test
    public void fragmentedPhysicalReadsCannotExtendAbsoluteReceiveDeadline() throws Exception {
        MutableClock clock = new MutableClock();
        MemorySocket socket = new MemorySocket(new DripInputStream(
                serverFrame(true, 0x2, bytes("ok")), clock, 6_000_000L));

        try {
            new RawWebSocket(socket).recv(new ConnectBudget(10L, clock), 10);
            fail("drip-fed frame must not outlive absolute deadline");
        } catch (java.net.SocketTimeoutException expected) {
            assertTrue(expected.getMessage().contains("deadline"));
        }
    }

    private static byte[] serverFrame(boolean fin, int opcode, byte[] payload) {
        if (payload.length >= 126) throw new IllegalArgumentException("test helper only supports short frames");
        byte[] result = new byte[2 + payload.length];
        result[0] = (byte) ((fin ? 0x80 : 0) | opcode);
        result[1] = (byte) payload.length;
        System.arraycopy(payload, 0, result, 2, payload.length);
        return result;
    }

    private static ClientFrame clientFrame(byte[] frame) throws IOException {
        if (frame.length < 6) throw new IOException("short frame");
        int opcode = frame[0] & 0x0F;
        boolean masked = (frame[1] & 0x80) != 0;
        int length = frame[1] & 0x7F;
        int offset = 2;
        if (length == 126) {
            length = ((frame[offset] & 0xFF) << 8) | (frame[offset + 1] & 0xFF);
            offset += 2;
        } else if (length == 127) {
            throw new IOException("test helper does not need 64-bit frames");
        }
        byte[] mask = masked ? Arrays.copyOfRange(frame, offset, offset + 4) : new byte[0];
        offset += mask.length;
        byte[] payload = Arrays.copyOfRange(frame, offset, offset + length);
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }
        return new ClientFrame(opcode, masked, mask, payload, offset + length);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... chunks) {
        int length = 0;
        for (byte[] chunk : chunks) length += chunk.length;
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    private static final class ClientFrame {
        final int opcode;
        final boolean masked;
        final byte[] mask;
        final byte[] payload;
        final int frameLength;

        ClientFrame(int opcode, boolean masked, byte[] mask, byte[] payload, int frameLength) {
            this.opcode = opcode;
            this.masked = masked;
            this.mask = mask;
            this.payload = payload;
            this.frameLength = frameLength;
        }
    }

    private static final class MemorySocket extends Socket {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean closed;

        MemorySocket(byte[] input) {
            this(new ByteArrayInputStream(input));
        }

        MemorySocket(InputStream input) {
            this.input = input;
        }

        @Override public InputStream getInputStream() {
            return input;
        }

        @Override public OutputStream getOutputStream() {
            return output;
        }

        @Override public synchronized void close() {
            closed = true;
        }

        @Override public boolean isClosed() {
            return closed;
        }

        @Override public boolean isConnected() {
            return !closed;
        }

        @Override public void setSoTimeout(int timeout) throws SocketException {
            // In-memory test transport has no kernel socket, but deadline code must be callable.
        }

        byte[] written() {
            return output.toByteArray();
        }
    }

    private static final class MutableClock implements ConnectBudget.NanoClock {
        long nowNanos;

        @Override public long nanoTime() {
            return nowNanos;
        }
    }

    private static final class DripInputStream extends InputStream {
        private final byte[] data;
        private final MutableClock clock;
        private final long stepNanos;
        private int offset;

        DripInputStream(byte[] data, MutableClock clock, long stepNanos) {
            this.data = data;
            this.clock = clock;
            this.stepNanos = stepNanos;
        }

        @Override public int read() {
            if (offset >= data.length) return -1;
            clock.nowNanos += stepNanos;
            return data[offset++] & 0xFF;
        }

        @Override public int read(byte[] target, int targetOffset, int length) {
            if (offset >= data.length) return -1;
            clock.nowNanos += stepNanos;
            target[targetOffset] = data[offset++];
            return 1;
        }
    }
}
