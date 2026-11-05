package com.consilens.server.application.capability;

public class TaskCancellationException extends RuntimeException {

    public TaskCancellationException(String instanceKey) {
        super("Task cancellation requested: " + instanceKey);
    }
}
