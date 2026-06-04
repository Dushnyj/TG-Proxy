package com.dushnyj.tgproxy;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

public final class MtProtoCrypto {
    public static final byte[] PROTO_TAG_ABRIDGED = {(byte) 0xef, (byte) 0xef, (byte) 0xef, (byte) 0xef};
    public static final byte[] PROTO_TAG_INTERMEDIATE = {(byte) 0xee, (byte) 0xee, (byte) 0xee, (byte) 0xee};
    public static final byte[] PROTO_TAG_PADDED_INTERMEDIATE = {(byte) 0xdd, (byte) 0xdd, (byte) 0xdd, (byte) 0xdd};

    public static final int PROTO_ABRIDGED_INT = 0xEFEFEFEF;
    public static final int PROTO_INTERMEDIATE_INT = 0xEEEEEEEE;
    public static final int PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDD;

    private static final int HANDSHAKE_LEN = 64;
    private static final int SKIP_LEN = 8;
    private static final int PREKEY_LEN = 32;
    private static final int IV_LEN = 16;
    private static final int PROTO_TAG_POS = 56;
    private static final int DC_IDX_POS = 60;
    private static final byte[] ZERO_64 = new byte[64];
    private static final SecureRandom RNG = new SecureRandom();

    public static ClientHandshake parseClientHandshake(byte[] handshake, byte[] secret) {
        if (handshake == null || handshake.length < HANDSHAKE_LEN || secret == null || secret.length != 16) {
            return null;
        }
        try {
            byte[] clientPrekeyIv = Arrays.copyOfRange(handshake, SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN);
            byte[] prekey = Arrays.copyOfRange(clientPrekeyIv, 0, PREKEY_LEN);
            byte[] iv = Arrays.copyOfRange(clientPrekeyIv, PREKEY_LEN, PREKEY_LEN + IV_LEN);
            byte[] key = sha256(concat(prekey, secret));
            byte[] decrypted = aesCtrUpdate(key, iv, handshake);

            byte[] protoTag = Arrays.copyOfRange(decrypted, PROTO_TAG_POS, PROTO_TAG_POS + 4);
            if (!isKnownProtoTag(protoTag)) return null;

            short dcRaw = ByteBuffer.wrap(decrypted, DC_IDX_POS, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getShort();
            int dc = Math.abs((int) dcRaw);
            if (dc < 1 || dc > 5) return null;
            return new ClientHandshake(dc, dcRaw < 0, protoTag, clientPrekeyIv);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static byte[] generateRelayInit(byte[] protoTag, int dcIdx) {
        byte[] rnd = new byte[HANDSHAKE_LEN];
        do {
            RNG.nextBytes(rnd);
        } while (isReservedInit(rnd));

        byte[] key = Arrays.copyOfRange(rnd, SKIP_LEN, SKIP_LEN + PREKEY_LEN);
        byte[] iv = Arrays.copyOfRange(rnd, SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN);
        byte[] encryptedFull = aesCtrUpdate(key, iv, rnd);

        byte[] tailPlain = new byte[8];
        System.arraycopy(protoTag, 0, tailPlain, 0, 4);
        ByteBuffer.wrap(tailPlain, 4, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) dcIdx);
        byte[] tailRandom = new byte[2];
        RNG.nextBytes(tailRandom);
        tailPlain[6] = tailRandom[0];
        tailPlain[7] = tailRandom[1];

        byte[] result = Arrays.copyOf(rnd, rnd.length);
        for (int i = 0; i < 8; i++) {
            byte keystream = (byte) (encryptedFull[PROTO_TAG_POS + i] ^ rnd[PROTO_TAG_POS + i]);
            result[PROTO_TAG_POS + i] = (byte) (tailPlain[i] ^ keystream);
        }
        return result;
    }

    public static CryptoContext buildCryptoContext(byte[] clientPrekeyIv, byte[] secret, byte[] relayInit) {
        byte[] cltDecPrekey = Arrays.copyOfRange(clientPrekeyIv, 0, PREKEY_LEN);
        byte[] cltDecIv = Arrays.copyOfRange(clientPrekeyIv, PREKEY_LEN, PREKEY_LEN + IV_LEN);
        byte[] cltDecKey = sha256(concat(cltDecPrekey, secret));

        byte[] cltEncPrekeyIv = reverse(clientPrekeyIv);
        byte[] cltEncPrekey = Arrays.copyOfRange(cltEncPrekeyIv, 0, PREKEY_LEN);
        byte[] cltEncIv = Arrays.copyOfRange(cltEncPrekeyIv, PREKEY_LEN, PREKEY_LEN + IV_LEN);
        byte[] cltEncKey = sha256(concat(cltEncPrekey, secret));

        AesCtrStream clientDecryptor = new AesCtrStream(cltDecKey, cltDecIv);
        AesCtrStream clientEncryptor = new AesCtrStream(cltEncKey, cltEncIv);
        clientDecryptor.update(ZERO_64);

        byte[] tgEncKey = Arrays.copyOfRange(relayInit, SKIP_LEN, SKIP_LEN + PREKEY_LEN);
        byte[] tgEncIv = Arrays.copyOfRange(relayInit, SKIP_LEN + PREKEY_LEN,
                SKIP_LEN + PREKEY_LEN + IV_LEN);
        byte[] relayDecPrekeyIv = reverse(Arrays.copyOfRange(relayInit, SKIP_LEN,
                SKIP_LEN + PREKEY_LEN + IV_LEN));
        byte[] tgDecKey = Arrays.copyOfRange(relayDecPrekeyIv, 0, PREKEY_LEN);
        byte[] tgDecIv = Arrays.copyOfRange(relayDecPrekeyIv, PREKEY_LEN, PREKEY_LEN + IV_LEN);

        AesCtrStream telegramEncryptor = new AesCtrStream(tgEncKey, tgEncIv);
        AesCtrStream telegramDecryptor = new AesCtrStream(tgDecKey, tgDecIv);
        telegramEncryptor.update(ZERO_64);

        return new CryptoContext(clientDecryptor, clientEncryptor,
                telegramEncryptor, telegramDecryptor);
    }

    public static int protoInt(byte[] protoTag) {
        if (Arrays.equals(protoTag, PROTO_TAG_ABRIDGED)) return PROTO_ABRIDGED_INT;
        if (Arrays.equals(protoTag, PROTO_TAG_INTERMEDIATE)) return PROTO_INTERMEDIATE_INT;
        return PROTO_PADDED_INTERMEDIATE_INT;
    }

    private static boolean isKnownProtoTag(byte[] protoTag) {
        return Arrays.equals(protoTag, PROTO_TAG_ABRIDGED)
                || Arrays.equals(protoTag, PROTO_TAG_INTERMEDIATE)
                || Arrays.equals(protoTag, PROTO_TAG_PADDED_INTERMEDIATE);
    }

    private static boolean isReservedInit(byte[] rnd) {
        if ((rnd[0] & 0xFF) == 0xEF) return true;
        int first4 = ByteBuffer.wrap(rnd, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (first4 == 0x48454144 || first4 == 0x504F5354 || first4 == 0x47455420
                || first4 == 0xEEEEEEEE || first4 == 0xDDDDDDDD || first4 == 0x16030102) {
            return true;
        }
        return rnd[4] == 0 && rnd[5] == 0 && rnd[6] == 0 && rnd[7] == 0;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private static byte[] aesCtrUpdate(byte[] key, byte[] iv, byte[] data) {
        return new AesCtrStream(key, iv).update(data);
    }

    public static final class ClientHandshake {
        public final int dc;
        public final boolean media;
        public final byte[] protoTag;
        public final byte[] clientPrekeyIv;

        private ClientHandshake(int dc, boolean media, byte[] protoTag, byte[] clientPrekeyIv) {
            this.dc = dc;
            this.media = media;
            this.protoTag = Arrays.copyOf(protoTag, protoTag.length);
            this.clientPrekeyIv = Arrays.copyOf(clientPrekeyIv, clientPrekeyIv.length);
        }
    }

    public static final class CryptoContext {
        private final AesCtrStream clientDecryptor;
        private final AesCtrStream clientEncryptor;
        private final AesCtrStream telegramEncryptor;
        private final AesCtrStream telegramDecryptor;

        private CryptoContext(AesCtrStream clientDecryptor, AesCtrStream clientEncryptor,
                              AesCtrStream telegramEncryptor, AesCtrStream telegramDecryptor) {
            this.clientDecryptor = clientDecryptor;
            this.clientEncryptor = clientEncryptor;
            this.telegramEncryptor = telegramEncryptor;
            this.telegramDecryptor = telegramDecryptor;
        }

        public synchronized byte[] clientToTelegram(byte[] clientCipher) {
            return telegramEncryptor.update(clientDecryptor.update(clientCipher));
        }

        public synchronized byte[] telegramToClient(byte[] telegramCipher) {
            return clientEncryptor.update(telegramDecryptor.update(telegramCipher));
        }
    }

    private static final class AesCtrStream {
        private final SICBlockCipher cipher;

        private AesCtrStream(byte[] key, byte[] iv) {
            cipher = new SICBlockCipher(new AESEngine());
            cipher.init(true, new ParametersWithIV(new KeyParameter(key), iv));
        }

        private byte[] update(byte[] data) {
            byte[] out = new byte[data.length];
            cipher.processBytes(data, 0, data.length, out, 0);
            return out;
        }
    }

    private MtProtoCrypto() {
    }
}
