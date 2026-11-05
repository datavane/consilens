package com.consilens.server.application.task.impl;

import com.consilens.server.domain.enums.TaskCommandStatus;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class RunTaskCommandEnqueueService {

    private final TaskCommandRepository taskCommandRepository;

    public RunTaskCommandEnqueueService(TaskCommandRepository taskCommandRepository) {
        this.taskCommandRepository = taskCommandRepository;
    }

    public TaskCommandRecord enqueue(TaskInstanceRecord task, Instant scheduleTime) {
        TaskCommandRecord command = TaskCommandRecord.builder()
                .commandKey("cmd_" + UUID.randomUUID())
                .taskId(task.getId())
                .shardKey(task.getInstanceKey())
                .shardSlot(shardSlot(task.getInstanceKey()))
                .status(TaskCommandStatus.PENDING)
                .scheduleTime(scheduleTime)
                .createdAt(scheduleTime)
                .updatedAt(scheduleTime)
                .build();
        return taskCommandRepository.save(command);
    }

    private int shardSlot(String instanceKey) {
        return Math.floorMod(instanceKey.hashCode(), Integer.MAX_VALUE);
    }
}
