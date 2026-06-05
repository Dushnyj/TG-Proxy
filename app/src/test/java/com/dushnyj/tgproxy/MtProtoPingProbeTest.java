package com.dushnyj.tgproxy;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MtProtoPingProbeTest {
    @Test
    public void reqPqProbeIsIntermediatePacketEncryptedForTelegramTransport() throws Exception {
        byte[] relayInit = MtProtoCrypto.generateRelayInit(MtProtoCrypto.PROTO_TAG_INTERMEDIATE, 2);
        MtProtoCrypto.TelegramTransport transport = MtProtoCrypto.telegramTransport(relayInit);
        byte[] nonce = fixedNonce();

        byte[] encrypted = MtProtoPingProbe.encryptedReqPqMulti(transport, nonce, 0x1122334455667788L);
        byte[] decrypted = decryptTelegramOutbound(relayInit, encrypted);

        int packetLen = ByteBuffer.wrap(decrypted, 0, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
        byte[] message = Arrays.copyOfRange(decrypted, 4, 4 + packetLen);

        assertTrue(packetLen >= 40);
        assertArrayEquals(new byte[8], Arrays.copyOfRange(message, 0, 8));
        assertArrayEquals(nonce, Arrays.copyOfRange(message, 24, 40));
    }

    @Test
    public void responseValidationRequiresMatchingNonce() {
        byte[] nonce = fixedNonce();
        byte[] response = fakeResPqResponse(nonce);

        assertTrue(MtProtoPingProbe.isValidResPq(response, nonce));
        assertFalse(MtProtoPingProbe.isValidResPq(response, new byte[16]));
    }

    @Test
    public void partialResponseIsRejectedUntilFullPacketArrives() {
        byte[] nonce = fixedNonce();
        byte[] response = fakeResPqResponse(nonce);

        assertFalse(MtProtoPingProbe.isValidResPq(
                Arrays.copyOfRange(response, 0, response.length - 3), nonce));
        assertTrue(MtProtoPingProbe.isValidResPq(response, nonce));
    }

    private static byte[] fixedNonce() {
        byte[] nonce = new byte[16];
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (0x31 + i);
        return nonce;
    }

    private static byte[] fakeResPqResponse(byte[] nonce) {
        byte[] body = new byte[4 + 16 + 16 + 4 + 8 + 4 + 8];
        ByteBuffer.wrap(body, 0, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x05162463);
        System.arraycopy(nonce, 0, body, 4, nonce.length);
        body[36] = 1;
        body[37] = 1;
        body[38] = 17;
        body[39] = 0;
        ByteBuffer.wrap(body, 48, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x1cb5c415);
        ByteBuffer.wrap(body, 52, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1);

        byte[] message = new byte[8 + 8 + 4 + body.length];
        ByteBuffer.wrap(message, 16, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(body.length);
        System.arraycopy(body, 0, message, 20, body.length);

        byte[] packet = new byte[4 + message.length];
        ByteBuffer.wrap(packet, 0, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(message.length);
        System.arraycopy(message, 0, packet, 4, message.length);
        return packet;
    }

    private static byte[] decryptTelegramOutbound(byte[] relayInit, byte[] encrypted) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(Arrays.copyOfRange(relayInit, 8, 40), "AES"),
                new IvParameterSpec(Arrays.copyOfRange(relayInit, 40, 56)));
        cipher.update(new byte[64]);
        return cipher.update(encrypted);
    }
}
