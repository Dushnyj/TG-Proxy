package com.dushnyj.tgproxy;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MtProtoPacketSplitter {
    private final SICBlockCipher decryptor;
    private final int proto;
    private final ArrayList<Byte> cipherBuf = new ArrayList<>();
    private final ArrayList<Byte> plainBuf = new ArrayList<>();
    private boolean disabled = false;

    public MtProtoPacketSplitter(byte[] relayInit, int proto) {
        decryptor = new SICBlockCipher(new AESEngine());
        decryptor.init(true, new ParametersWithIV(
                new KeyParameter(Arrays.copyOfRange(relayInit, 8, 40)),
                Arrays.copyOfRange(relayInit, 40, 56)));
        byte[] zero = new byte[64];
        byte[] ignored = new byte[64];
        decryptor.processBytes(zero, 0, zero.length, ignored, 0);
        this.proto = proto;
    }

    public List<byte[]> split(byte[] chunk) {
        ArrayList<byte[]> parts = new ArrayList<>();
        if (chunk == null || chunk.length == 0) return parts;
        if (disabled) {
            parts.add(chunk);
            return parts;
        }

        byte[] plain = new byte[chunk.length];
        decryptor.processBytes(chunk, 0, chunk.length, plain, 0);
        append(cipherBuf, chunk);
        append(plainBuf, plain);

        int offset = 0;
        while (offset < cipherBuf.size()) {
            Integer packetLen = nextPacketLen(offset, cipherBuf.size() - offset);
            if (packetLen == null) break;
            if (packetLen <= 0) {
                parts.add(toByteArray(cipherBuf, offset, cipherBuf.size() - offset));
                offset = cipherBuf.size();
                disabled = true;
                break;
            }
            parts.add(toByteArray(cipherBuf, offset, packetLen));
            offset += packetLen;
        }
        if (offset > 0) {
            removePrefix(cipherBuf, offset);
            removePrefix(plainBuf, offset);
        }
        return parts;
    }

    public List<byte[]> flush() {
        ArrayList<byte[]> parts = new ArrayList<>();
        if (!cipherBuf.isEmpty()) {
            parts.add(toByteArray(cipherBuf, 0, cipherBuf.size()));
            cipherBuf.clear();
            plainBuf.clear();
        }
        return parts;
    }

    private Integer nextPacketLen(int offset, int available) {
        if (available <= 0) return null;
        if (proto == MtProtoCrypto.PROTO_ABRIDGED_INT) {
            return nextAbridgedLen(offset, available);
        }
        if (proto == MtProtoCrypto.PROTO_INTERMEDIATE_INT
                || proto == MtProtoCrypto.PROTO_PADDED_INTERMEDIATE_INT) {
            return nextIntermediateLen(offset, available);
        }
        return 0;
    }

    private Integer nextAbridgedLen(int offset, int available) {
        int first = plainBuf.get(offset) & 0xFF;
        int payloadLen;
        int headerLen;
        if (first == 0x7F || first == 0xFF) {
            if (available < 4) return null;
            payloadLen = (plainBuf.get(offset + 1) & 0xFF)
                    | ((plainBuf.get(offset + 2) & 0xFF) << 8)
                    | ((plainBuf.get(offset + 3) & 0xFF) << 16);
            payloadLen *= 4;
            headerLen = 4;
        } else {
            payloadLen = (first & 0x7F) * 4;
            headerLen = 1;
        }
        if (payloadLen <= 0) return 0;
        int packetLen = headerLen + payloadLen;
        return available < packetLen ? null : packetLen;
    }

    private Integer nextIntermediateLen(int offset, int available) {
        if (available < 4) return null;
        byte[] lenBytes = toByteArray(plainBuf, offset, 4);
        int payloadLen = ByteBuffer.wrap(lenBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt() & 0x7FFFFFFF;
        if (payloadLen <= 0) return 0;
        int packetLen = 4 + payloadLen;
        return available < packetLen ? null : packetLen;
    }

    private static void append(ArrayList<Byte> target, byte[] source) {
        for (byte b : source) target.add(b);
    }

    private static byte[] toByteArray(ArrayList<Byte> source, int offset, int len) {
        byte[] result = new byte[len];
        for (int i = 0; i < len; i++) {
            result[i] = source.get(offset + i);
        }
        return result;
    }

    private static void removePrefix(ArrayList<Byte> list, int count) {
        list.subList(0, count).clear();
    }
}
