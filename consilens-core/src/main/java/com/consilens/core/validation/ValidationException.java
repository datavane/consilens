package com.consilens.core.validation;

import java.util.List;
import java.util.ArrayList;

/**
 * Unified validation exception for the integrated system.
 * Combines validation error handling from all modules.
 */
public class ValidationException extends RuntimeException {

    private final List<ValidationError> errors;
    private final String validationType;

    public ValidationException(String validationType, String message) {
        super(message);
        this.validationType = validationType;
        this.errors = new ArrayList<>();
    }

    public ValidationException(String validationType, String message, List<ValidationError> errors) {
        super(message);
        this.validationType = validationType;
        this.errors = new ArrayList<>(errors);
    }

    public ValidationException(String validationType, String message, Throwable cause) {
        super(message, cause);
        this.validationType = validationType;
        this.errors = new ArrayList<>();
    }

    public ValidationException(String validationType, String message, List<ValidationError> errors, Throwable cause) {
        super(message, cause);
        this.validationType = validationType;
        this.errors = new ArrayList<>(errors);
    }

    public List<ValidationError> getErrors() {
        return new ArrayList<>(errors);
    }

    public String getValidationType() {
        return validationType;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public int getErrorCount() {
        return errors.size();
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder(super.getMessage());
        if (hasErrors()) {
            sb.append(" Errors: ");
            for (int i = 0; i < errors.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(errors.get(i));
            }
        }
        return sb.toString();
    }

    /**
     * Create a validation exception for schema validation.
     */
    public static ValidationException forSchema(String message, List<ValidationError> errors) {
        return new ValidationException("SCHEMA_VALIDATION", message, errors);
    }

    /**
     * Create a validation exception for data validation.
     */
    public static ValidationException forData(String message, List<ValidationError> errors) {
        return new ValidationException("DATA_VALIDATION", message, errors);
    }

    /**
     * Create a validation exception for configuration validation.
     */
    public static ValidationException forConfiguration(String message, List<ValidationError> errors) {
        return new ValidationException("CONFIGURATION_VALIDATION", message, errors);
    }

    /**
     * Create a validation exception for connection validation.
     */
    public static ValidationException forConnection(String message, List<ValidationError> errors) {
        return new ValidationException("CONNECTION_VALIDATION", message, errors);
    }

    /**
     * Create a simple validation exception.
     */
    public static ValidationException simple(String validationType, String message) {
        return new ValidationException(validationType, message);
    }

    /**
     * Inner class representing a single validation error.
     */
    public static class ValidationError {
        private final String field;
        private final String code;
        private final String message;
        private final Severity severity;
        private final Object invalidValue;

        public ValidationError(String field, String code, String message, Severity severity, Object invalidValue) {
            this.field = field;
            this.code = code;
            this.message = message;
            this.severity = severity;
            this.invalidValue = invalidValue;
        }

        public ValidationError(String field, String code, String message, Severity severity) {
            this(field, code, message, severity, null);
        }

        public ValidationError(String code, String message, Severity severity) {
            this(null, code, message, severity, null);
        }

        public String getField() {
            return field;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public Severity getSeverity() {
            return severity;
        }

        public Object getInvalidValue() {
            return invalidValue;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (field != null) sb.append(field).append(": ");
            sb.append("[").append(code).append("] ");
            sb.append(message);
            if (invalidValue != null) sb.append(" (value: ").append(invalidValue).append(")");
            sb.append(" [").append(severity).append("]");
            return sb.toString();
        }
    }

    /**
     * Error severity levels.
     */
    public enum Severity {
        ERROR("ERROR"),
        WARNING("WARNING"),
        INFO("INFO");

        private final String displayName;

        Severity(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}