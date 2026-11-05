package com.consilens.server.application.capability;

public class CapabilityExecutionException extends RuntimeException {

    private final String errorCode;
    private final String artifactId;
    private final boolean retryable;

    public CapabilityExecutionException(String errorCode,
                                        String message,
                                        String artifactId,
                                        boolean retryable,
                                        Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.artifactId = artifactId;
        this.retryable = retryable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
