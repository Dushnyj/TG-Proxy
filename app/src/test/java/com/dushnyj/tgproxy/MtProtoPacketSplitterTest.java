package com.dushnyj.tgproxy;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MtProtoPacketSplitterTest {
    @Test
    public void waitsForFullIntermediatePacketBeforeReturningFrame() throws Exception {
        byte[] relayInit = MtProtoCrypto.generateRelayInit(MtProtoCrypto.PROTO_TAG_INTERMEDIATE, 4);
        byte[] packet = encryptedIntermediatePacket(relayInit, "hello".getBytes("UTF-8"));

        MtProtoPacketSplitter splitter =
                new MtProtoPacketSplitter(relayInit, MtProtoCrypto.PROTO_INTERMEDIATE_INT);

        assertTrue(splitter.split(slice(packet, 0, 3)).isEmpty());
        assertTrue(splitter.split(slice(packet, 3, 4)).isEmpty());

        List<byte[]> frames = splitter.split(slice(packet, 7, packet.length - 7));

        assertEquals(1, frames.size());
        assertEquals(packet.length, frames.get(0).length);
    }

    @Test
    public void flushReturnsPartialTail() throws Exception {
        byte[] relayInit = MtProtoCrypto.generateRelayInit(MtProtoCrypto.PROTO_TAG_INTERMEDIATE, 4);
        byte[] packet = encryptedIntermediatePacket(relayInit, "partial".getBytes("UTF-8"));
        MtProtoPacketSplitter splitter =
                new MtProtoPacketSplitter(relayInit, MtProtoCrypto.PROTO_INTERMEDIATE_INT);

        assertTrue(splitter.split(slice(packet, 0, 5)).isEmpty());

        List<byte[]> tail = splitter.flush();

        assertEquals(1, tail.size());
        assertEquals(5, tail.get(0).length);
    }

    private static byte[] encryptedIntermediatePacket(byte[] relayInit, byte[] payload) throws Exception {
        byte[] plain = new byte[4 + payload.length];
        ByteBuffer.wrap(plain, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length);
        System.arraycopy(payload, 0, plain, 4, payload.length);

        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(slice(relayInit, 8, 32), "AES"),
                new IvParameterSpec(slice(relayInit, 40, 16)));
        cipher.update(new byte[64]);
        return cipher.update(plain);
    }

    private static byte[] slice(byte[] data, int offset, int len) {
        byte[] result = new byte[len];
        System.arraycopy(data, offset, result, 0, len);
        return result;
    }
}
