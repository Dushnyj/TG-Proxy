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
import static org.junit.Assert.fail;

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
    public void flushRejectsPartialTailInsteadOfSendingMalformedFrame() throws Exception {
        byte[] relayInit = MtProtoCrypto.generateRelayInit(MtProtoCrypto.PROTO_TAG_INTERMEDIATE, 4);
        byte[] packet = encryptedIntermediatePacket(relayInit, "partial".getBytes("UTF-8"));
        MtProtoPacketSplitter splitter =
                new MtProtoPacketSplitter(relayInit, MtProtoCrypto.PROTO_INTERMEDIATE_INT);

        assertTrue(splitter.split(slice(packet, 0, 5)).isEmpty());

        try {
            splitter.flush();
            fail("truncated packet must not be sent");
        } catch (MtProtoPacketSplitter.PacketException expected) {
            assertTrue(expected.getMessage().contains("truncated"));
        }
    }

    @Test
    public void oversizedDeclaredPacketIsRejectedAfterFourByteHeader() throws Exception {
        byte[] relayInit = MtProtoCrypto.generateRelayInit(MtProtoCrypto.PROTO_TAG_INTERMEDIATE, 4);
        byte[] header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MtProtoPacketSplitter.MAX_PACKET_LEN).array();
        byte[] encrypted = encrypted(relayInit, header);
        MtProtoPacketSplitter splitter =
                new MtProtoPacketSplitter(relayInit, MtProtoCrypto.PROTO_INTERMEDIATE_INT);

        try {
            splitter.split(encrypted);
            fail("oversized packet must fail from its header");
        } catch (MtProtoPacketSplitter.PacketException expected) {
            assertTrue(expected.getMessage().contains("exceeds"));
            assertTrue(splitter.bufferedBytes() <= 4);
        }
    }

    private static byte[] encryptedIntermediatePacket(byte[] relayInit, byte[] payload) throws Exception {
        byte[] plain = new byte[4 + payload.length];
        ByteBuffer.wrap(plain, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length);
        System.arraycopy(payload, 0, plain, 4, payload.length);

        return encrypted(relayInit, plain);
    }

    @Test
    public void intermediateQuickAckFlagIsNotCountedAsPacketLength() throws Exception {
        byte[] relayInit = MtProtoCrypto.generateRelayInit(MtProtoCrypto.PROTO_TAG_INTERMEDIATE, 4);
        byte[] payload = "quick-ack".getBytes("UTF-8");
        byte[] plain = new byte[4 + payload.length];
        ByteBuffer.wrap(plain, 0, 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(payload.length | 0x80000000);
        System.arraycopy(payload, 0, plain, 4, payload.length);
        byte[] packet = encrypted(relayInit, plain);
        MtProtoPacketSplitter splitter =
                new MtProtoPacketSplitter(relayInit, MtProtoCrypto.PROTO_INTERMEDIATE_INT);

        List<byte[]> frames = splitter.split(packet);

        assertEquals(1, frames.size());
        assertEquals(packet.length, frames.get(0).length);
    }

    private static byte[] encrypted(byte[] relayInit, byte[] plain) throws Exception {
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
