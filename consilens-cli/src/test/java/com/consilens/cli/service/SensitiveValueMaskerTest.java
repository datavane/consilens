package com.consilens.cli.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SensitiveValueMaskerTest {

    @Test
    void shouldMaskCredentialsInJdbcUrl() {
        String masked = SensitiveValueMasker.maskJdbcUrl(
                "jdbc:mysql://user:secret@localhost:3306/app?password=secret&useSSL=false&access_token=abc");

        assertEquals("jdbc:mysql://***@localhost:3306/app?password=***&useSSL=false&access_token=***", masked);
    }

    @Test
    void shouldMaskSemicolonSeparatedJdbcProperties() {
        String masked = SensitiveValueMasker.maskJdbcUrl(
                "jdbc:sqlserver://localhost:1433;databaseName=app;user=sa;password=secret;encrypt=true");

        assertEquals("jdbc:sqlserver://localhost:1433;databaseName=app;user=***;password=***;encrypt=true", masked);
    }

    @Test
    void shouldMaskUsername() {
        assertEquals("a***e", SensitiveValueMasker.maskUsername("alice"));
        assertEquals("***", SensitiveValueMasker.maskUsername("ab"));
        assertEquals("(not set)", SensitiveValueMasker.maskUsername(null));
    }
}
