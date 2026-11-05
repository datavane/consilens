package com.consilens.server.support.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmSecretProtectorTest {

    @Test
    void roundTripsPlaintextThroughVersionedEnvelope() {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        String envelope = protector.protect("s3cret");
        assertTrue(envelope.startsWith("v2:test:"));
        assertEquals("s3cret", protector.reveal(envelope));
    }

    @Test
    void everyEncryptionUsesFreshNonce() {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        assertNotEquals(protector.protect("same"), protector.protect("same"));
    }

    @Test
    void rejectsTamperedEnvelope() {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        String envelope = protector.protect("secret");
        String tampered = envelope.substring(0, envelope.length() - 2) + "AA";
        assertThrows(IllegalArgumentException.class, () -> protector.reveal(tampered));
    }

    @Test
    void rejectsKeyIdMismatch() {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        String envelope = protector.protect("secret");
        AesGcmSecretProtector rotated = new AesGcmSecretProtector(SecretProtectorTestKeys.TEST_KEY_B64, "k2");
        assertThrows(IllegalArgumentException.class, () -> rotated.reveal(envelope));
    }

    @Test
    void revealToleratesLegacyPlaintextSoConnectionsStayUsable() {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        // 加密功能上线前落库的明文密码：reveal 原样返回，不抛错
        assertEquals("123456@szh", protector.reveal("123456@szh"));
        assertEquals("", protector.reveal(""));
        assertEquals(null, protector.reveal(null));
    }

    @Test
    void rejectsShortKeysAtConstruction() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalArgumentException.class, () -> new AesGcmSecretProtector(shortKey, "k1"));
    }

    @Test
    void neverReturnsPlaintext() {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        String envelope = protector.protect("plain");
        assertTrue(!envelope.contains("plain"));
        assertTrue(envelope.startsWith("v2:"));
    }

    @Test
    void missingKeyProtectorFailsLoudly() {
        SecretProtector missing = new SecretProtectorConfig.MissingKeySecretProtector();
        assertThrows(IllegalStateException.class, () -> missing.protect("x"));
        assertThrows(IllegalStateException.class, () -> missing.reveal("v2:..."));
    }
}
