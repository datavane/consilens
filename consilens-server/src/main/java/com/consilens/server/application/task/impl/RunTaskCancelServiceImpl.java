package com.consilens.server.application.task.impl;

import com.consilens.server.application.task.RunTaskCancelService;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.ConflictException;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.domain.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RunTaskCancelServiceImpl implements RunTaskCancelService {

    private final TaskRepository taskRepository;
    private final TaskCommandRepository taskCommandRepository;

    public RunTaskCancelServiceImpl(TaskRepository taskRepository,
                                    TaskCommandRepository taskCommandRepository) {
        this.taskRepository = taskRepository;
        this.taskCommandRepository = taskCommandRepository;
    }

    @Override
    @Transactional
    public TaskStatus cancel(String taskId, String traceId) {
        TaskInstanceRecord task = taskRepository.resolveByRef(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        Instant now = Instant.now();
        if (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.CLAIMED) {
            if (!taskRepository.cancel(task.getId(), traceId, now)) {
                throw new ConflictException("Task can only be cancelled from PENDING or CLAIMED");
            }
            taskCommandRepository.releaseOpenByTaskId(task.getId(), now);
            return TaskStatus.CANCELLED;
        }
        if (task.getStatus() == TaskStatus.RUNNING) {
            if (!taskRepository.requestCancellation(task.getId(), traceId, now)) {
                throw new ConflictException("Task cancellation request was not accepted");
            }
            return TaskStatus.CANCEL_REQUESTED;
        }
        throw new ConflictException("Task can only be cancelled from PENDING, CLAIMED, or RUNNING");
    }
}
