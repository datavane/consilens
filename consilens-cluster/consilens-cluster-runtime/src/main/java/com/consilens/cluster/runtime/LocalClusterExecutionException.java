package com.consilens.cluster.runtime;

/**
 * Raised when a simulated submission cannot complete all splits within its attempt limit.
 */
public class LocalClusterExecutionException extends Exception {

    public LocalClusterExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
