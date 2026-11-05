package com.consilens.server.infrastructure.db.repository;

import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.infrastructure.db.entity.TaskCommandEntity;
import com.consilens.server.infrastructure.db.service.TaskCommandService;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class TaskCommandRepositoryImpl implements TaskCommandRepository {

    private final TaskCommandService taskCommandService;

    public TaskCommandRepositoryImpl(TaskCommandService taskCommandService) {
        this.taskCommandService = taskCommandService;
    }

    @Override
    public TaskCommandRecord save(TaskCommandRecord taskCommandRecord) {
        TaskCommandEntity entity = toEntity(taskCommandRecord);
        taskCommandService.save(entity);
        return toRecord(entity);
    }

    @Override
    public TaskCommandRecord getStartCommand(int totalSlot, int currentSlot, Instant now) {
        return toRecord(taskCommandService.getStartCommand(totalSlot, currentSlot, DbTimeSupport.toLocalDateTime(now)));
    }

    @Override
    public boolean claim(Long id, String executeNodeKey, Instant lockTime, Instant lockUntil) {
        return taskCommandService.claim(id,
                executeNodeKey,
                DbTimeSupport.toLocalDateTime(lockTime),
                DbTimeSupport.toLocalDateTime(lockUntil));
    }

    @Override
    public void markDone(Long id, Instant now) {
        taskCommandService.markDone(id, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean release(Long id, Instant now) {
        return taskCommandService.release(id, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean resetClaim(Long id, Instant now) {
        return taskCommandService.resetClaim(id, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean releaseClaimedByTaskId(Long taskId, Instant now) {
        return taskCommandService.releaseClaimedByTaskId(taskId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean releaseOpenByTaskId(Long taskId, Instant now) {
        return taskCommandService.releaseOpenByTaskId(taskId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public List<TaskCommandRecord> listExpiredClaims(Instant now, int limit) {
        return taskCommandService.listExpiredClaims(DbTimeSupport.toLocalDateTime(now), limit).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskCommandRecord> listClaimedByExecuteNode(String executeNodeKey, int limit) {
        return taskCommandService.listClaimedByExecuteNode(executeNodeKey, limit).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    private TaskCommandEntity toEntity(TaskCommandRecord record) {
        TaskCommandEntity entity = new TaskCommandEntity();
        entity.setId(record.getId());
        entity.setCommandKey(record.getCommandKey());
        entity.setTaskId(record.getTaskId());
        entity.setShardKey(record.getShardKey());
        entity.setShardSlot(record.getShardSlot());
        entity.setStatus(record.getStatus());
        entity.setExecuteNodeKey(record.getExecuteNodeKey());
        entity.setLockTime(DbTimeSupport.toLocalDateTime(record.getLockTime()));
        entity.setLockUntil(DbTimeSupport.toLocalDateTime(record.getLockUntil()));
        entity.setScheduleTime(DbTimeSupport.toLocalDateTime(record.getScheduleTime()));
        entity.setCreatedAt(DbTimeSupport.toLocalDateTime(record.getCreatedAt()));
        entity.setUpdatedAt(DbTimeSupport.toLocalDateTime(record.getUpdatedAt()));
        return entity;
    }

    private TaskCommandRecord toRecord(TaskCommandEntity entity) {
        if (entity == null) {
            return null;
        }
        return TaskCommandRecord.builder()
                .id(entity.getId())
                .commandKey(entity.getCommandKey())
                .taskId(entity.getTaskId())
                .shardKey(entity.getShardKey())
                .shardSlot(entity.getShardSlot())
                .status(entity.getStatus())
                .executeNodeKey(entity.getExecuteNodeKey())
                .lockTime(DbTimeSupport.toInstant(entity.getLockTime()))
                .lockUntil(DbTimeSupport.toInstant(entity.getLockUntil()))
                .scheduleTime(DbTimeSupport.toInstant(entity.getScheduleTime()))
                .createdAt(DbTimeSupport.toInstant(entity.getCreatedAt()))
                .updatedAt(DbTimeSupport.toInstant(entity.getUpdatedAt()))
                .build();
    }
}
