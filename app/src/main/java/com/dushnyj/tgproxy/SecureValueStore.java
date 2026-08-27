package com.dushnyj.tgproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Calendar;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

/** Small Android-Keystore backed value cipher used for Relay and VPS owner secrets. */
final class SecureValueStore {
    private static final String PREFIX = "tgproxy-secure-v1:";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String AES_ALIAS = "tgproxy_secure_values_aes_v1";
    private static final String RSA_ALIAS = "tgproxy_secure_values_rsa_v1";
    private static final String WRAPPED_MASTER_KEY = "secure_values_wrapped_master.v1";
    private static final byte VERSION_CBC_HMAC = 1;
    private static final byte VERSION_GCM = 2;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object KEY_LOCK = new Object();

    private final Context context;
    private final SharedPreferences preferences;

    SecureValueStore(Context context, SharedPreferences preferences) {
        this.context = context == null ? null : context.getApplicationContext();
        this.preferences = preferences;
    }

    String get(String key, String fallback) {
        if (preferences == null) return fallback;
        String stored = preferences.getString(key, null);
        if (stored == null) return fallback;
        if (!stored.startsWith(PREFIX)) {
            // Migrate legacy app-private plaintext in place. A failed migration is reported and
            // never destroys the only available copy.
            if (!put(key, stored)) {
                DiagnosticsLog.record("secure preference migration failed key=" + safeKey(key));
            }
            return stored;
        }
        try {
            return decrypt(key, stored.substring(PREFIX.length()));
        } catch (Exception error) {
            DiagnosticsLog.record("secure preference decrypt failed key=" + safeKey(key)
                    + " " + error.getClass().getSimpleName());
            return fallback;
        }
    }

    boolean put(String key, String value) {
        if (preferences == null) return false;
        try {
            return preferences.edit().putString(key, encryptStored(key, value)).commit();
        } catch (Exception error) {
            DiagnosticsLog.record("secure preference write failed key=" + safeKey(key)
                    + " " + error.getClass().getSimpleName());
            return false;
        }
    }

    boolean remove(String key) {
        return preferences != null && preferences.edit().remove(key).commit();
    }

    String encryptStored(String key, String value) throws Exception {
        return PREFIX + encrypt(key, value == null ? "" : value);
    }

    SharedPreferences preferences() {
        return preferences;
    }

    private String encrypt(String key, String value) throws Exception {
        byte[] clear = value.getBytes(StandardCharsets.UTF_8);
        byte[] payload = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? encryptGcm(key, clear) : encryptCbcHmac(key, clear);
        return Base64.encodeToString(payload, Base64.NO_WRAP);
    }

    private String decrypt(String key, String encoded) throws Exception {
        byte[] payload = Base64.decode(encoded, Base64.DEFAULT);
        if (payload.length < 2) throw new IllegalArgumentException("empty secure payload");
        byte[] clear;
        if (payload[0] == VERSION_GCM) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                throw new IllegalArgumentException("GCM secure payload requires Android 6+");
            }
            clear = decryptGcm(key, payload);
        }
        else if (payload[0] == VERSION_CBC_HMAC) clear = decryptCbcHmac(key, payload);
        else throw new IllegalArgumentException("unsupported secure payload");
        return new String(clear, StandardCharsets.UTF_8);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.M)
    private byte[] encryptGcm(String key, byte[] clear) throws Exception {
        SecretKey secret = getOrCreateAesKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret);
        cipher.updateAAD(aad(key));
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(clear);
        ByteBuffer out = ByteBuffer.allocate(2 + iv.length + encrypted.length);
        out.put(VERSION_GCM).put((byte) iv.length).put(iv).put(encrypted);
        return out.array();
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.M)
    private byte[] decryptGcm(String key, byte[] payload) throws Exception {
        int ivLength = payload[1] & 0xff;
        if (ivLength < 12 || ivLength > 32 || payload.length < 2 + ivLength + 16) {
            throw new IllegalArgumentException("invalid GCM payload");
        }
        byte[] iv = new byte[ivLength];
        System.arraycopy(payload, 2, iv, 0, ivLength);
        byte[] encrypted = new byte[payload.length - 2 - ivLength];
        System.arraycopy(payload, 2 + ivLength, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad(key));
        return cipher.doFinal(encrypted);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.M)
    private SecretKey getOrCreateAesKey() throws Exception {
        synchronized (KEY_LOCK) {
            KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
            store.load(null);
            KeyStore.Entry entry = store.getEntry(AES_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
            generator.init(new KeyGenParameterSpec.Builder(AES_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        }
    }

    private byte[] encryptCbcHmac(String key, byte[] clear) throws Exception {
        byte[] master = legacyMasterKey();
        byte[] encryptionKey = new byte[32];
        byte[] authenticationKey = new byte[32];
        System.arraycopy(master, 0, encryptionKey, 0, 32);
        System.arraycopy(master, 32, authenticationKey, 0, 32);
        byte[] iv = new byte[16];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(clear);
        byte[] signed = ByteBuffer.allocate(1 + iv.length + encrypted.length)
                .put(VERSION_CBC_HMAC).put(iv).put(encrypted).array();
        byte[] mac = hmac(authenticationKey, key, signed);
        return ByteBuffer.allocate(signed.length + mac.length).put(signed).put(mac).array();
    }

    private byte[] decryptCbcHmac(String key, byte[] payload) throws Exception {
        if (payload.length < 1 + 16 + 16 + 32) throw new IllegalArgumentException("invalid CBC payload");
        byte[] master = legacyMasterKey();
        byte[] encryptionKey = new byte[32];
        byte[] authenticationKey = new byte[32];
        System.arraycopy(master, 0, encryptionKey, 0, 32);
        System.arraycopy(master, 32, authenticationKey, 0, 32);
        int signedLength = payload.length - 32;
        byte[] signed = new byte[signedLength];
        byte[] suppliedMac = new byte[32];
        System.arraycopy(payload, 0, signed, 0, signedLength);
        System.arraycopy(payload, signedLength, suppliedMac, 0, 32);
        if (!MessageDigest.isEqual(suppliedMac, hmac(authenticationKey, key, signed))) {
            throw new SecurityException("secure value authentication failed");
        }
        byte[] iv = new byte[16];
        System.arraycopy(payload, 1, iv, 0, 16);
        byte[] encrypted = new byte[signedLength - 17];
        System.arraycopy(payload, 17, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                new IvParameterSpec(iv));
        return cipher.doFinal(encrypted);
    }

    private byte[] hmac(byte[] key, String preferenceKey, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(aad(preferenceKey));
        mac.update((byte) 0);
        return mac.doFinal(payload);
    }

    private byte[] legacyMasterKey() throws Exception {
        if (context == null || preferences == null) throw new IllegalStateException("context unavailable");
        synchronized (KEY_LOCK) {
            KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
            store.load(null);
            if (!store.containsAlias(RSA_ALIAS)) createLegacyRsaKey();
            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) store.getEntry(RSA_ALIAS, null);
            String wrapped = preferences.getString(WRAPPED_MASTER_KEY, "");
            if (wrapped == null || wrapped.isEmpty()) {
                byte[] master = new byte[64];
                RANDOM.nextBytes(master);
                Cipher wrap = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                wrap.init(Cipher.ENCRYPT_MODE, entry.getCertificate().getPublicKey());
                String encoded = Base64.encodeToString(wrap.doFinal(master), Base64.NO_WRAP);
                if (!preferences.edit().putString(WRAPPED_MASTER_KEY, encoded).commit()) {
                    throw new IllegalStateException("could not persist wrapped master key");
                }
                return master;
            }
            Cipher unwrap = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            unwrap.init(Cipher.DECRYPT_MODE, (PrivateKey) entry.getPrivateKey());
            byte[] master = unwrap.doFinal(Base64.decode(wrapped, Base64.DEFAULT));
            if (master.length != 64) throw new SecurityException("invalid legacy master key");
            return master;
        }
    }

    private void createLegacyRsaKey() throws Exception {
        Calendar start = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 30);
        KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(context)
                .setAlias(RSA_ALIAS)
                .setSubject(new X500Principal("CN=TG Proxy secure values"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.getTime())
                .setEndDate(end.getTime())
                .build();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", ANDROID_KEY_STORE);
        generator.initialize(spec);
        generator.generateKeyPair();
    }

    private static byte[] aad(String key) {
        return ("com.dushnyj.tgproxy:" + safeKey(key)).getBytes(StandardCharsets.UTF_8);
    }

    private static String safeKey(String key) {
        return key == null ? "" : key.trim();
    }
}
