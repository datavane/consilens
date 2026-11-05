package com.consilens.server.domain.exception;

public class ConflictException extends RuntimeException {

    private final String errorCode;

    public ConflictException(String message) {
        this(message, "CONFLICT");
    }

    public ConflictException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
