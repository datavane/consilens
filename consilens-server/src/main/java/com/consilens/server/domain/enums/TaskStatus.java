package com.consilens.server.domain.enums;

public enum TaskStatus {
    PENDING,
    CLAIMED,
    RUNNING,
    CANCEL_REQUESTED,
    SUCCEEDED,
    FAILED,
    RETRYABLE,
    CANCELLED
}
