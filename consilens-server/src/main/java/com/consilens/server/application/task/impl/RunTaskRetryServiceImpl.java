package com.consilens.server.application.task.impl;

import com.consilens.server.application.task.RunTaskRetryService;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.ConflictException;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RunTaskRetryServiceImpl implements RunTaskRetryService {

    private final TaskRepository taskRepository;
    private final RunTaskCommandEnqueueService runTaskCommandEnqueueService;

    public RunTaskRetryServiceImpl(TaskRepository taskRepository,
                                   RunTaskCommandEnqueueService runTaskCommandEnqueueService) {
        this.taskRepository = taskRepository;
        this.runTaskCommandEnqueueService = runTaskCommandEnqueueService;
    }

    @Override
    @Transactional
    public void retry(String taskId, String traceId) {
        TaskInstanceRecord task = taskRepository.resolveByRef(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        if (task.getStatus() != TaskStatus.FAILED
                && task.getStatus() != TaskStatus.RETRYABLE
                && task.getStatus() != TaskStatus.CANCELLED) {
            throw new ConflictException("Task is not retryable in status " + task.getStatus());
        }
        Instant now = Instant.now();
        if (!taskRepository.resetForRetry(task.getId(), traceId, now)) {
            throw new ConflictException("Task is not retryable in status " + task.getStatus());
        }
        runTaskCommandEnqueueService.enqueue(task, now);
    }
}
