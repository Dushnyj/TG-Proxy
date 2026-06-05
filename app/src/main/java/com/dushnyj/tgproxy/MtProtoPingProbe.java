package com.dushnyj.tgproxy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;

final class MtProtoPingProbe {
    private static final int REQ_PQ_MULTI = 0xbe7e8ef1;
    private static final int RES_PQ = 0x05162463;
    private static final SecureRandom RNG = new SecureRandom();

    private MtProtoPingProbe() {}

    static byte[] randomNonce() {
        byte[] nonce = new byte[16];
        RNG.nextBytes(nonce);
        return nonce;
    }

    static long messageId(long nowMs) {
        long seconds = nowMs / 1000L;
        long fraction = ((nowMs % 1000L) << 32) / 1000L;
        return ((seconds << 32) | fraction) & ~3L;
    }

    static byte[] encryptedReqPqMulti(MtProtoCrypto.TelegramTransport transport,
                                      byte[] nonce, long messageId) {
        byte[] packet = intermediatePacket(reqPqMultiMessage(nonce, messageId));
        return transport.encrypt(packet);
    }

    static boolean isValidResPq(byte[] decryptedIntermediatePacket, byte[] expectedNonce) {
        if (decryptedIntermediatePacket == null || expectedNonce == null
                || expectedNonce.length != 16 || decryptedIntermediatePacket.length < 52) {
            return false;
        }
        ByteBuffer packet = ByteBuffer.wrap(decryptedIntermediatePacket)
                .order(ByteOrder.LITTLE_ENDIAN);
        int packetLen = packet.getInt();
        if (packetLen <= 0 || packetLen > decryptedIntermediatePacket.length - 4) return false;
        int messageOffset = 4;
        if (!allZero(decryptedIntermediatePacket, messageOffset, 8)) return false;
        int bodyLenOffset = messageOffset + 16;
        int bodyLen = ByteBuffer.wrap(decryptedIntermediatePacket, bodyLenOffset, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
        int bodyOffset = messageOffset + 20;
        if (bodyLen < 20 || bodyOffset + bodyLen > decryptedIntermediatePacket.length) return false;
        int constructor = ByteBuffer.wrap(decryptedIntermediatePacket, bodyOffset, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
        if (constructor != RES_PQ) return false;
        byte[] actualNonce = Arrays.copyOfRange(decryptedIntermediatePacket,
                bodyOffset + 4, bodyOffset + 20);
        return Arrays.equals(expectedNonce, actualNonce);
    }

    private static byte[] reqPqMultiMessage(byte[] nonce, long messageId) {
        byte[] safeNonce = nonce == null || nonce.length != 16 ? randomNonce() : nonce;
        byte[] body = new byte[20];
        ByteBuffer.wrap(body, 0, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(REQ_PQ_MULTI);
        System.arraycopy(safeNonce, 0, body, 4, 16);

        byte[] message = new byte[8 + 8 + 4 + body.length];
        ByteBuffer.wrap(message, 8, 8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(messageId);
        ByteBuffer.wrap(message, 16, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(body.length);
        System.arraycopy(body, 0, message, 20, body.length);
        return message;
    }

    private static byte[] intermediatePacket(byte[] message) {
        byte[] packet = new byte[4 + message.length];
        ByteBuffer.wrap(packet, 0, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(message.length);
        System.arraycopy(message, 0, packet, 4, message.length);
        return packet;
    }

    private static boolean allZero(byte[] data, int offset, int len) {
        if (data == null || offset < 0 || len < 0 || offset + len > data.length) return false;
        for (int i = 0; i < len; i++) {
            if (data[offset + i] != 0) return false;
        }
        return true;
    }
}
