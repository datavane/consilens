package com.consilens.server.support.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretProtectorConfigTest {

    private final SecretProtectorConfig config = new SecretProtectorConfig();

    @Test
    void developmentKeyIsRejectedForProductionProfiles() {
        for (String profile : new String[]{"prod", "production", "staging", "preprod", "prod,metrics"}) {
            assertThrows(IllegalStateException.class,
                    () -> config.secretProtector(SecretProtectorConfig.DEV_KEY_BASE64, profile),
                    "profile " + profile + " must not accept the built-in development key");
        }
    }

    @Test
    void developmentKeyStaysUsableLocallyButIsLabelled() {
        SecretProtector protector = config.secretProtector(SecretProtectorConfig.DEV_KEY_BASE64, "mysql");
        // keyId 为 dev：页面上能看出"这是开发密钥加的密"
        assertEquals("dev", protector.keyId());
        assertEquals("secret", protector.reveal(protector.protect("secret")));
    }

    @Test
    void explicitKeyIsUsedAsIsAndMissingKeyFails() {
        SecretProtector custom = config.secretProtector(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "prod");
        assertEquals("k1", custom.keyId());

        SecretProtector missing = config.secretProtector("  ", "prod");
        assertEquals("missing", missing.keyId());
        assertThrows(IllegalStateException.class, () -> missing.protect("x"));
    }
}
