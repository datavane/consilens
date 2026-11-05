package com.consilens.cli.service;

import java.sql.SQLException;
import java.util.stream.Collectors;

/**
 * Normalizes DuckDB JDBC error messages for CLI output.
 */
public final class DuckDbErrors {

    private static final String PENDING_RESULT_NOISE =
            "Attempting to execute an unsuccessful or closed pending query result";

    private DuckDbErrors() {
    }

    /**
     * Removes the internal pending-result prefix DuckDB JDBC adds when a query
     * fails during execution. The underlying binder/parser message follows it
     * and is the part users can act on.
     */
    public static String message(SQLException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        String cleaned = message.lines()
                .filter(line -> !line.contains(PENDING_RESULT_NOISE))
                .collect(Collectors.joining(System.lineSeparator()))
                .trim();
        return cleaned.isEmpty() ? message.trim() : cleaned;
    }
}
