package com.dushnyj.tgproxy;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class MtProtoCryptoTest {
    private static final byte[] SECRET = hex("00112233445566778899aabbccddeeff");

    @Test
    public void parsesSecretBasedClientHandshake() throws Exception {
        byte[] init = clientInit(SECRET, (short) -4, MtProtoCrypto.PROTO_TAG_PADDED_INTERMEDIATE);

        MtProtoCrypto.ClientHandshake parsed = MtProtoCrypto.parseClientHandshake(init, SECRET);

        assertNotNull(parsed);
        assertEquals(-4, parsed.dcRaw);
        assertEquals(4, parsed.dc);
        assertEquals(true, parsed.media);
        assertArrayEquals(MtProtoCrypto.PROTO_TAG_PADDED_INTERMEDIATE, parsed.protoTag);
    }

    @Test
    public void parsesMediaDc203ClientHandshake() throws Exception {
        byte[] init = clientInit(SECRET, (short) -203, MtProtoCrypto.PROTO_TAG_PADDED_INTERMEDIATE);

        MtProtoCrypto.ClientHandshake parsed = MtProtoCrypto.parseClientHandshake(init, SECRET);

        assertNotNull(parsed);
        assertEquals(-203, parsed.dcRaw);
        assertEquals(203, parsed.dc);
        assertEquals(true, parsed.media);
    }

    @Test
    public void parsesFutureDcClientHandshakeWithoutWhitelist() throws Exception {
        byte[] init = clientInit(SECRET, (short) -204,
                MtProtoCrypto.PROTO_TAG_PADDED_INTERMEDIATE);

        MtProtoCrypto.ClientHandshake parsed = MtProtoCrypto.parseClientHandshake(init, SECRET);

        assertNotNull(parsed);
        assertEquals(-204, parsed.dcRaw);
        assertEquals(204, parsed.dc);
        assertEquals(true, parsed.media);
    }

    @Test
    public void generatedRelayInitIsPlainObfuscatedInitForTelegramDc() throws Exception {
        byte[] relayInit = MtProtoCrypto.generateRelayInit(
                MtProtoCrypto.PROTO_TAG_INTERMEDIATE, -2);

        int[] parsed = CryptoUtils.dcFromInit(relayInit);

        assertNotNull(parsed);
        assertEquals(2, parsed[0]);
        assertEquals(1, parsed[1]);
    }

    @Test
    public void cryptoContextReencryptsBothDirections() throws Exception {
        byte[] clientInit = clientInit(SECRET, (short) 4, MtProtoCrypto.PROTO_TAG_INTERMEDIATE);
        MtProtoCrypto.ClientHandshake parsed = MtProtoCrypto.parseClientHandshake(clientInit, SECRET);
        byte[] relayInit = MtProtoCrypto.generateRelayInit(parsed.protoTag, 4);
        MtProtoCrypto.CryptoContext ctx =
                MtProtoCrypto.buildCryptoContext(parsed.clientPrekeyIv, SECRET, relayInit);

        byte[] plainUp = "client message payload".getBytes("UTF-8");
        byte[] clientCipher = clientToProxyCipher(clientInit, SECRET, plainUp);
        byte[] expectedTelegramCipher = telegramOutboundCipher(relayInit, plainUp);

        assertArrayEquals(expectedTelegramCipher, ctx.clientToTelegram(clientCipher));

        byte[] plainDown = "telegram response payload".getBytes("UTF-8");
        byte[] telegramCipher = telegramInboundCipher(relayInit, plainDown);
        byte[] expectedClientCipher = proxyToClientCipher(clientInit, SECRET, plainDown);

        assertArrayEquals(expectedClientCipher, ctx.telegramToClient(telegramCipher));
    }

    private static byte[] clientInit(byte[] secret, short dc, byte[] protoTag) throws Exception {
        byte[] rnd = new byte[64];
        for (int i = 0; i < rnd.length; i++) {
            rnd[i] = (byte) (0x31 + i);
        }
        rnd[0] = 0x11;
        rnd[4] = 0x22;
        byte[] key = sha256(concat(Arrays.copyOfRange(rnd, 8, 40), secret));
        byte[] iv = Arrays.copyOfRange(rnd, 40, 56);
        byte[] encryptedFull = aesCtr(key, iv).update(rnd);

        byte[] tailPlain = new byte[8];
        System.arraycopy(protoTag, 0, tailPlain, 0, 4);
        ByteBuffer.wrap(tailPlain, 4, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(dc);
        tailPlain[6] = 0x55;
        tailPlain[7] = 0x66;

        byte[] init = Arrays.copyOf(rnd, rnd.length);
        for (int i = 0; i < 8; i++) {
            byte keystream = (byte) (encryptedFull[56 + i] ^ rnd[56 + i]);
            init[56 + i] = (byte) (tailPlain[i] ^ keystream);
        }
        return init;
    }

    private static byte[] clientToProxyCipher(byte[] clientInit, byte[] secret, byte[] plain) throws Exception {
        byte[] prekey = Arrays.copyOfRange(clientInit, 8, 40);
        byte[] iv = Arrays.copyOfRange(clientInit, 40, 56);
        Cipher cipher = aesCtr(sha256(concat(prekey, secret)), iv);
        cipher.update(new byte[64]);
        return cipher.update(plain);
    }

    private static byte[] proxyToClientCipher(byte[] clientInit, byte[] secret, byte[] plain) throws Exception {
        byte[] reversed = reverse(Arrays.copyOfRange(clientInit, 8, 56));
        byte[] prekey = Arrays.copyOfRange(reversed, 0, 32);
        byte[] iv = Arrays.copyOfRange(reversed, 32, 48);
        return aesCtr(sha256(concat(prekey, secret)), iv).update(plain);
    }

    private static byte[] telegramOutboundCipher(byte[] relayInit, byte[] plain) throws Exception {
        Cipher cipher = aesCtr(Arrays.copyOfRange(relayInit, 8, 40),
                Arrays.copyOfRange(relayInit, 40, 56));
        cipher.update(new byte[64]);
        return cipher.update(plain);
    }

    private static byte[] telegramInboundCipher(byte[] relayInit, byte[] plain) throws Exception {
        byte[] reversed = reverse(Arrays.copyOfRange(relayInit, 8, 56));
        return aesCtr(Arrays.copyOfRange(reversed, 0, 32),
                Arrays.copyOfRange(reversed, 32, 48)).update(plain);
    }

    private static Cipher aesCtr(byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static byte[] reverse(byte[] data) {
        byte[] result = Arrays.copyOf(data, data.length);
        for (int i = 0, j = result.length - 1; i < j; i++, j--) {
            byte tmp = result[i];
            result[i] = result[j];
            result[j] = tmp;
        }
        return result;
    }

    private static byte[] hex(String s) {
        byte[] result = new byte[s.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
