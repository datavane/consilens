package com.consilens.cli.config;

import java.util.Objects;

/**
 * Exception thrown when configuration loading or validation fails.
 *
 * <p>An optional {@link #getCode() code} identifies well-known failure classes
 * so callers do not have to match on the human-readable message.
 */
public class ConfigurationException extends Exception {

    /** Code for a missing required environment variable placeholder. */
    public static final String CODE_MISSING_ENV_VAR = "MISSING_ENV_VAR";

    private final String code;

    public ConfigurationException(String message) {
        this(message, null, null);
    }

    public ConfigurationException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public ConfigurationException(String message, String code) {
        this(message, code, null);
    }

    public ConfigurationException(String message, String code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * Machine-readable failure code, or {@code null} when unspecified.
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns whether this exception carries the given failure code.
     */
    public boolean hasCode(String expectedCode) {
        return code != null && Objects.equals(code, expectedCode);
    }
}
