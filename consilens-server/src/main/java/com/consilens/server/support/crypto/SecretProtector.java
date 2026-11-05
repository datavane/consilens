package com.consilens.server.support.crypto;

/**
 * Versioned envelope protector for datasource credentials. User input is
 * always encrypted before persistence; only trusted envelopes read back from
 * the persistence layer are decrypted. There is no plaintext mode.
 */
public interface SecretProtector {

    /**
     * Encrypts a plaintext value into a versioned envelope. Never returns the
     * input unchanged.
     */
    String protect(String plaintext);

    /**
     * Decrypts a trusted envelope. Throws on unknown format, key mismatch or
     * authentication failure; callers must treat failures as loud errors, not
     * silently empty credentials.
     */
    String reveal(String envelope);

    String keyId();
}
