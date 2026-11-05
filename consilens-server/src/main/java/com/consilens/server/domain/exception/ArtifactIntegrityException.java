package com.consilens.server.domain.exception;

public class ArtifactIntegrityException extends RuntimeException {

    public ArtifactIntegrityException(String message) {
        super(message);
    }

    public String getErrorCode() {
        return "ARTIFACT_INTEGRITY_ERROR";
    }
}
