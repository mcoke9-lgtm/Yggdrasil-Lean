package org.yggdrasil.scheduler.builder;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts scheduled task prompts before they enter WorkManager's persistent input database. */
public final class BuilderOvernightPayloadCipher {
    private static final String ALIAS = "yggdrasil_builder_overnight_payload_v1";
    private BuilderOvernightPayloadCipher() {}

    public static final class Payload {
        public final String ciphertext;
        public final String iv;
        Payload(String ciphertext, String iv) { this.ciphertext = ciphertext; this.iv = iv; }
    }

    public static Payload encrypt(String plaintext) throws Exception {
        if (plaintext == null || plaintext.trim().isEmpty())
            throw new IllegalArgumentException("Overnight task prompt is required");
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        try {
            return new Payload(Base64.encodeToString(encrypted, Base64.NO_WRAP),
                    Base64.encodeToString(iv, Base64.NO_WRAP));
        } finally {
            java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    public static String decrypt(String ciphertext, String encodedIv) throws Exception {
        if (ciphertext == null || encodedIv == null)
            throw new IllegalArgumentException("Encrypted overnight prompt is incomplete");
        byte[] encrypted = Base64.decode(ciphertext, Base64.NO_WRAP);
        byte[] iv = Base64.decode(encodedIv, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] plaintext = cipher.doFinal(encrypted);
        try { return new String(plaintext, StandardCharsets.UTF_8); }
        finally {
            java.util.Arrays.fill(encrypted, (byte) 0);
            java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry)
                store.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}