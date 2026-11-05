package com.consilens.server.support.crypto;

import java.util.Base64;

/**
 * Shared 32-byte test key for service tests that now require the versioned
 * SecretProtector (plaintext mode no longer exists).
 */
public final class SecretProtectorTestKeys {

    public static final String TEST_KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);

    private SecretProtectorTestKeys() {
    }

    public static AesGcmSecretProtector protector() {
        return new AesGcmSecretProtector(TEST_KEY_B64, "test");
    }
}
