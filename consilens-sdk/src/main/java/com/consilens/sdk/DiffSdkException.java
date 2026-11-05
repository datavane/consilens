package com.consilens.sdk;

/**
 * RuntimeException thrown when the underlying compare runtime fails.
 * Keeps the checked {@code Exception} of {@code CompareRuntime.execute} out of
 * the SDK contract while preserving the cause.
 */
public class DiffSdkException extends RuntimeException {

    public DiffSdkException(String message, Throwable cause) {
        super(message, cause);
    }
}
