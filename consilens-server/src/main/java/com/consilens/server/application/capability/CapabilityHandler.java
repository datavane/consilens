package com.consilens.server.application.capability;

import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskExecutionResult;

public interface CapabilityHandler<I> {

    CapabilityType type();

    TaskExecutionResult handle(TaskExecutionContext context, I request);
}
