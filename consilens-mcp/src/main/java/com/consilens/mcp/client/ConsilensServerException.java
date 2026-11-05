package com.consilens.mcp.client;

public class ConsilensServerException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;
    private final String traceId;

    public ConsilensServerException(int statusCode, String errorCode, String traceId, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.traceId = traceId;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getTraceId() {
        return traceId;
    }
}
