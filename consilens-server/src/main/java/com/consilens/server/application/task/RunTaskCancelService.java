package com.consilens.server.application.task;

import com.consilens.server.domain.enums.TaskStatus;

public interface RunTaskCancelService {

    TaskStatus cancel(String taskId, String traceId);
}
