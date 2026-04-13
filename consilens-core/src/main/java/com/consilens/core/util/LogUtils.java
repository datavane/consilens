package com.consilens.core.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Unified logging utility.
 * Provides standardized log formatting and methods.
 */
@Slf4j
public final class LogUtils {

    private LogUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Log the start of an operation.
     * @param operation operation name
     * @param params operation parameters
     */
    public static void logOperationStart(String operation, Object... params) {
        if (params.length == 0) {
            log.info("Starting {}", operation);
        } else {
            log.info("Starting {} with parameters: {}", operation, formatParams(params));
        }
    }

    /**
     * Log successful operation completion.
     * @param operation operation name
     */
    public static void logOperationSuccess(String operation) {
        log.info("{} completed successfully", operation);
    }

    /**
     * Log operation failure.
     * @param operation operation name
     * @param error the error
     */
    public static void logOperationFailure(String operation, Throwable error) {
        log.error("{} failed: {}", operation, error.getMessage(), error);
    }

    /**
     * Log configuration loading.
     * @param configType configuration type
     * @param source configuration source
     */
    public static void logConfigurationLoad(String configType, String source) {
        log.info("Loading {} configuration from: {}", configType, source);
    }

    /**
     * Log successful configuration load.
     * @param configType configuration type
     * @param source configuration source
     */
    public static void logConfigurationLoadSuccess(String configType, String source) {
        log.info("Successfully loaded {} configuration from: {}", configType, source);
    }

    /**
     * Log configuration validation failure.
     * @param configType configuration type
     * @param errors validation errors
     */
    public static void logConfigurationValidationFailure(String configType, String errors) {
        log.error("{} configuration validation failed: {}", configType, errors);
    }


    /**
     * Log a warning message.
     * @param message warning message
     * @param params parameters
     */
    public static void logWarning(String message, Object... params) {
        log.warn(message, params);
    }

    /**
     * Log a debug message.
     * @param message debug message
     * @param params parameters
     */
    public static void logDebug(String message, Object... params) {
        log.debug(message, params);
    }

    /**
     * Format a parameter array into a string.
     * @param params parameter array
     * @return formatted string
     */
    private static String formatParams(Object[] params) {
        if (params == null || params.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i]);
        }
        return sb.toString();
    }
}