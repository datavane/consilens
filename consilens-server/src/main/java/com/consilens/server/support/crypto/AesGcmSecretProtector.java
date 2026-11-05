package com.consilens.server.support.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM envelope protector.
 *
 * <p>Envelope format: {@code v2:{keyId}:{nonceB64}:{ciphertextB64}:{tagB64}}.
 * The key must be a Base64-encoded 32-byte secret; shorter keys are rejected
 * at construction instead of silently weakening the cipher. {@code keyId}
 * allows future rotation: reveal requires the envelope to carry this keyId.
 */
public final class AesGcmSecretProtector implements SecretProtector {

    public static final String FORMAT = "v2";

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final String keyId;

    public AesGcmSecretProtector(String keyBase64, String keyId) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(keyBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("datasource encryption key must be Base64", e);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("datasource encryption key must decode to exactly 32 bytes");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("secret protector keyId is required");
        }
        this.key = new SecretKeySpec(decoded, "AES");
        this.keyId = keyId;
    }

    @Override
    public String protect(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] tag = java.util.Arrays.copyOfRange(ciphertext, ciphertext.length - 16, ciphertext.length);
            byte[] body = java.util.Arrays.copyOfRange(ciphertext, 0, ciphertext.length - 16);
            return FORMAT + ":" + keyId + ":"
                    + Base64.getEncoder().encodeToString(nonce) + ":"
                    + Base64.getEncoder().encodeToString(body) + ":"
                    + Base64.getEncoder().encodeToString(tag);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encrypt datasource password", e);
        }
    }

    @Override
    public String reveal(String envelope) {
        if (envelope == null || envelope.isEmpty()) {
            return envelope;
        }
        if (!envelope.startsWith(FORMAT + ":")) {
            // 兼容加密功能上线前落库的明文密码：原样返回，
            // 让“存储时加密”不成为历史数据连接/测试的阻碍。
            return envelope;
        }
        String[] parts = envelope.split(":", 5);
        if (parts.length != 5 || !FORMAT.equals(parts[0]) || !keyId.equals(parts[1])) {
            throw new IllegalArgumentException("unsupported secret envelope or keyId mismatch");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(parts[2]);
            byte[] body = Base64.getDecoder().decode(parts[3]);
            byte[] tag = Base64.getDecoder().decode(parts[4]);
            byte[] ciphertext = java.util.Arrays.copyOf(body, body.length + tag.length);
            System.arraycopy(tag, 0, ciphertext, body.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to decrypt datasource password (auth failure?)", e);
        }
    }

    @Override
    public String keyId() {
        return keyId;
    }

    /**
     * Decrypts the pre-v2 "enc:" format that shared the same base64 key.
     * Only the one-time migration may call this; runtime never reads legacy.
     */
    public String decryptLegacyEnc(String legacyEnvelope) {
        try {
            byte[] all = Base64.getDecoder().decode(legacyEnvelope.substring("enc:".length()));
            byte[] iv = java.util.Arrays.copyOfRange(all, 0, NONCE_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(all, NONCE_LENGTH, all.length - NONCE_LENGTH),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("legacy enc: envelope cannot be decrypted", e);
        }
    }
}
