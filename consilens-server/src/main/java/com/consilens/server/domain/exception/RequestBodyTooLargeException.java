package com.consilens.server.domain.exception;

public class RequestBodyTooLargeException extends RuntimeException {

    public RequestBodyTooLargeException(long maxBytes) {
        super("Request body exceeds max size: " + maxBytes + " bytes");
    }
}
