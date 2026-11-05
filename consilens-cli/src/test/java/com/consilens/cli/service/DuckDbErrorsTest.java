package com.consilens.cli.service;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDbErrorsTest {

    @Test
    void shouldStripPendingResultNoise() {
        SQLException error = new SQLException("Invalid Input Error: Attempting to execute an unsuccessful"
                + " or closed pending query result\nError: Binder Error: Referenced column \"status\""
                + " not found in FROM clause!");

        String message = DuckDbErrors.message(error);

        assertThat(message)
                .contains("Binder Error: Referenced column \"status\" not found")
                .doesNotContain("Attempting to execute an unsuccessful or closed pending query result");
    }

    @Test
    void shouldKeepMessageWithoutNoise() {
        SQLException error = new SQLException("Parser Error: syntax error at or near \"FROM\"");

        assertThat(DuckDbErrors.message(error))
                .isEqualTo("Parser Error: syntax error at or near \"FROM\"");
    }

    @Test
    void shouldFallBackToExceptionTypeForBlankMessage() {
        SQLException error = new SQLException("   ");

        assertThat(DuckDbErrors.message(error)).isEqualTo("SQLException");
    }
}
