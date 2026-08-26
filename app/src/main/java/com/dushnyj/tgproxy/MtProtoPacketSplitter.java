package com.dushnyj.tgproxy;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits the encrypted client stream into transport packets suitable for Telegram WebSocket
 * messages. Both encrypted and decrypted views use primitive, bounded buffers: a forged length
 * header must never turn a local client connection into an Android OOM.
 */
public final class MtProtoPacketSplitter {
    static final int MAX_PACKET_LEN = RawWebSocket.MAX_MESSAGE_LEN;

    private final SICBlockCipher decryptor;
    private final int proto;
    private byte[] cipherBuf = new byte[64 * 1024];
    private byte[] plainBuf = new byte[64 * 1024];
    private int buffered;
    private final boolean passthrough;

    public MtProtoPacketSplitter(byte[] relayInit, int proto) {
        if (relayInit == null || relayInit.length < 56) {
            throw new IllegalArgumentException("relay init is too short");
        }
        decryptor = new SICBlockCipher(new AESEngine());
        decryptor.init(true, new ParametersWithIV(
                new KeyParameter(Arrays.copyOfRange(relayInit, 8, 40)),
                Arrays.copyOfRange(relayInit, 40, 56)));
        byte[] zero = new byte[64];
        byte[] ignored = new byte[64];
        decryptor.processBytes(zero, 0, zero.length, ignored, 0);
        this.proto = proto;
        this.passthrough = proto != MtProtoCrypto.PROTO_ABRIDGED_INT
                && proto != MtProtoCrypto.PROTO_INTERMEDIATE_INT
                && proto != MtProtoCrypto.PROTO_PADDED_INTERMEDIATE_INT;
    }

    public List<byte[]> split(byte[] chunk) throws IOException {
        ArrayList<byte[]> parts = new ArrayList<>();
        if (chunk == null || chunk.length == 0) return parts;
        if (passthrough) {
            if (chunk.length > MAX_PACKET_LEN) throw tooLarge(chunk.length);
            parts.add(chunk);
            return parts;
        }

        int inputOffset = 0;
        while (inputOffset < chunk.length) {
            if (buffered == MAX_PACKET_LEN) {
                int emitted = drainPackets(parts);
                if (emitted == 0 && buffered == MAX_PACKET_LEN) {
                    throw tooLarge(buffered);
                }
            }

            int take = Math.min(chunk.length - inputOffset, MAX_PACKET_LEN - buffered);
            ensureCapacity(buffered + take);
            System.arraycopy(chunk, inputOffset, cipherBuf, buffered, take);
            decryptor.processBytes(chunk, inputOffset, take, plainBuf, buffered);
            buffered += take;
            inputOffset += take;
            drainPackets(parts);
        }
        return parts;
    }

    /** A clean TCP half-close is valid only on an MTProto transport-packet boundary. */
    public List<byte[]> flush() throws IOException {
        if (buffered != 0) {
            int truncated = buffered;
            buffered = 0;
            throw new PacketException("truncated MTProto packet: " + truncated + " buffered bytes");
        }
        return new ArrayList<>();
    }

    int bufferedBytes() {
        return buffered;
    }

    private int drainPackets(List<byte[]> parts) throws IOException {
        int offset = 0;
        int emitted = 0;
        while (offset < buffered) {
            Integer packetLen = nextPacketLen(offset, buffered - offset);
            if (packetLen == null) break;
            if (packetLen <= 0) throw new PacketException("invalid zero-length MTProto packet");
            parts.add(Arrays.copyOfRange(cipherBuf, offset, offset + packetLen));
            offset += packetLen;
            emitted++;
        }
        if (offset > 0) removePrefix(offset);
        return emitted;
    }

    private Integer nextPacketLen(int offset, int available) throws IOException {
        if (available <= 0) return null;
        if (proto == MtProtoCrypto.PROTO_ABRIDGED_INT) {
            return nextAbridgedLen(offset, available);
        }
        return nextIntermediateLen(offset, available);
    }

    private Integer nextAbridgedLen(int offset, int available) throws IOException {
        int first = plainBuf[offset] & 0xFF;
        long payloadLen;
        int headerLen;
        if (first == 0x7F || first == 0xFF) {
            if (available < 4) return null;
            long words = (plainBuf[offset + 1] & 0xFFL)
                    | ((plainBuf[offset + 2] & 0xFFL) << 8)
                    | ((plainBuf[offset + 3] & 0xFFL) << 16);
            payloadLen = words * 4L;
            headerLen = 4;
        } else {
            payloadLen = (first & 0x7FL) * 4L;
            headerLen = 1;
        }
        return checkedPacketLength(headerLen, payloadLen, available);
    }

    private Integer nextIntermediateLen(int offset, int available) throws IOException {
        if (available < 4) return null;
        long payloadLen = (plainBuf[offset] & 0xFFL)
                | ((plainBuf[offset + 1] & 0xFFL) << 8)
                | ((plainBuf[offset + 2] & 0xFFL) << 16)
                | ((plainBuf[offset + 3] & 0xFFL) << 24);
        // The high bit requests a transport quick acknowledgement and is not part of length.
        payloadLen &= 0x7FFFFFFFL;
        return checkedPacketLength(4, payloadLen, available);
    }

    private static Integer checkedPacketLength(int headerLen, long payloadLen, int available)
            throws IOException {
        if (payloadLen <= 0L) throw new PacketException("invalid zero-length MTProto packet");
        long packetLen = headerLen + payloadLen;
        if (packetLen > MAX_PACKET_LEN || packetLen > Integer.MAX_VALUE) {
            throw tooLarge(packetLen);
        }
        return available < packetLen ? null : (int) packetLen;
    }

    private void ensureCapacity(int wanted) throws IOException {
        if (wanted < 0 || wanted > MAX_PACKET_LEN) throw tooLarge(wanted);
        if (wanted <= cipherBuf.length) return;
        int next = cipherBuf.length;
        while (next < wanted) {
            next = Math.min(MAX_PACKET_LEN, Math.max(next + 1, next << 1));
        }
        cipherBuf = Arrays.copyOf(cipherBuf, next);
        plainBuf = Arrays.copyOf(plainBuf, next);
    }

    private void removePrefix(int count) {
        int remaining = buffered - count;
        if (remaining > 0) {
            System.arraycopy(cipherBuf, count, cipherBuf, 0, remaining);
            System.arraycopy(plainBuf, count, plainBuf, 0, remaining);
        }
        buffered = remaining;
    }

    private static PacketException tooLarge(long length) {
        return new PacketException("MTProto packet exceeds " + MAX_PACKET_LEN
                + " bytes: " + length);
    }

    static final class PacketException extends IOException {
        PacketException(String message) {
            super(message);
        }
    }
}
