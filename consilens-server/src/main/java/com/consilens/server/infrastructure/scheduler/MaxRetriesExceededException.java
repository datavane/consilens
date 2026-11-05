package com.consilens.server.infrastructure.scheduler;

public class MaxRetriesExceededException extends Exception {

    public MaxRetriesExceededException(String message) {
        super(message);
    }
}
