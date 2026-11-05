package com.consilens.server.domain.exception;

public class InvalidInputException extends RuntimeException {

    private final String errorCode;

    public InvalidInputException(String message) {
        this(message, "INVALID_INPUT");
    }

    public InvalidInputException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
