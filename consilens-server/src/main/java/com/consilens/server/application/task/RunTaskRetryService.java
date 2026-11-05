package com.consilens.server.application.task;

public interface RunTaskRetryService {

    void retry(String taskId, String traceId);
}
