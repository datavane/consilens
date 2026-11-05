package com.consilens.server.infrastructure.db.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.TaskPage;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.db.entity.TaskInstanceEntity;
import com.consilens.server.infrastructure.db.service.TaskService;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class TaskRepositoryImpl implements TaskRepository {

    private final TaskService taskService;

    public TaskRepositoryImpl(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public Optional<TaskInstanceRecord> findById(Long id) {
        return Optional.ofNullable(taskService.getById(id)).map(this::toRecord);
    }

    @Override
    public Optional<TaskInstanceRecord> findByTaskKey(String instanceKey) {
        return Optional.ofNullable(taskService.getByTaskKey(instanceKey)).map(this::toRecord);
    }

    @Override
    public Optional<TaskInstanceRecord> findBySerialNo(String serialNo) {
        return Optional.ofNullable(taskService.getBySerialNo(serialNo)).map(this::toRecord);
    }

    @Override
    public Optional<TaskInstanceRecord> lockBySerialNo(String serialNo) {
        return Optional.ofNullable(taskService.lockBySerialNo(serialNo)).map(this::toRecord);
    }

    @Override
    public TaskInstanceRecord save(TaskInstanceRecord taskRecord) {
        TaskInstanceEntity entity = toEntity(taskRecord);
        taskService.save(entity);
        return toRecord(entity);
    }

    @Override
    public boolean updateClaimed(Long taskId, String executeNodeKey, Instant now) {
        return taskService.updateClaimed(taskId, executeNodeKey, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateRunning(Long taskId, String executeNodeKey, Instant now) {
        return taskService.updateRunning(taskId, executeNodeKey, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean renewRunning(Long taskId, Instant now) {
        return taskService.renewRunning(taskId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateSuccess(Long taskId, String artifactId, Instant now) {
        return taskService.updateSuccess(taskId, artifactId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateFailure(Long taskId, String errorCode, String errorMessage, Instant now) {
        return taskService.updateFailure(taskId,
                TaskStatus.FAILED,
                errorCode,
                errorMessage,
                DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateRetryable(Long taskId, String errorCode, String errorMessage, Instant now) {
        return taskService.updateFailure(taskId,
                TaskStatus.RETRYABLE,
                errorCode,
                errorMessage,
                DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateFailureFromStatuses(Long taskId,
                                             Collection<TaskStatus> statuses,
                                             String errorCode,
                                             String errorMessage,
                                             Instant now) {
        return taskService.updateFailureFromStatuses(taskId,
                statuses,
                TaskStatus.FAILED,
                errorCode,
                errorMessage,
                DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean updateRetryableFromStatuses(Long taskId,
                                               Collection<TaskStatus> statuses,
                                               String errorCode,
                                               String errorMessage,
                                               Instant now) {
        return taskService.updateFailureFromStatuses(taskId,
                statuses,
                TaskStatus.RETRYABLE,
                errorCode,
                errorMessage,
                DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean releaseClaimed(Long taskId, Instant now) {
        return taskService.releaseClaimed(taskId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean resetForRetry(Long taskId, String traceId, Instant now) {
        return taskService.resetForRetry(taskId, traceId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean cancel(Long taskId, String traceId, Instant now) {
        return taskService.cancel(taskId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean requestCancellation(Long taskId, String traceId, Instant now) {
        return taskService.requestCancellation(taskId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public boolean confirmCancellation(Long taskId, Instant now) {
        return taskService.confirmCancellation(taskId, DbTimeSupport.toLocalDateTime(now));
    }

    @Override
    public List<TaskInstanceRecord> listByExecuteNodeAndStatuses(String executeNodeKey,
                                                         Collection<TaskStatus> statuses,
                                                         int limit) {
        return taskService.listByExecuteNodeAndStatuses(executeNodeKey, statuses, limit).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskInstanceRecord> listByStatusesExcludingExecuteNodes(Collection<TaskStatus> statuses,
                                                                Set<String> executeNodeKeys,
                                                                int limit) {
        return taskService.listByStatusesExcludingExecuteNodes(statuses, executeNodeKeys, limit).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskInstanceRecord> listStaleRunning(Instant before, int limit) {
        return taskService.listStaleRunning(DbTimeSupport.toLocalDateTime(before), limit).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskInstanceRecord> listStaleByStatus(TaskStatus status, Instant before, int limit) {
        return taskService.listStaleByStatus(status, DbTimeSupport.toLocalDateTime(before), limit).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public TaskPage listTaskPage(int page,
                                 int pageSize,
                                 Collection<TaskStatus> statuses,
                                 String serialNoLike,
                                 String executeNodeKey,
                                 Instant startTime,
                                 Instant endTime,
                                 Long definitionId) {
        LambdaQueryWrapper<TaskInstanceEntity> wrapper = new QueryWrapper<TaskInstanceEntity>().lambda()
                .orderByDesc(TaskInstanceEntity::getSubmitTime)
                .orderByDesc(TaskInstanceEntity::getId);
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in(TaskInstanceEntity::getStatus, statuses);
        }
        if (serialNoLike != null && !serialNoLike.isBlank()) {
            // keyword 同时匹配序号与实例 ID：列表展示/复制的是 taskId（instanceKey），
            // 只查 serial_no 会让用户拿页面上的 ID 搜索永远无结果。
            String keyword = serialNoLike.trim();
            wrapper.and(condition -> condition
                    .like(TaskInstanceEntity::getSerialNo, keyword)
                    .or()
                    .like(TaskInstanceEntity::getInstanceKey, keyword));
        }
        if (executeNodeKey != null && !executeNodeKey.isBlank()) {
            wrapper.eq(TaskInstanceEntity::getExecuteNodeKey, executeNodeKey);
        }
        if (definitionId != null) {
            wrapper.eq(TaskInstanceEntity::getDefinitionId, definitionId);
        }
        if (startTime != null) {
            wrapper.ge(TaskInstanceEntity::getSubmitTime, DbTimeSupport.toLocalDateTime(startTime));
        }
        if (endTime != null) {
            wrapper.le(TaskInstanceEntity::getSubmitTime, DbTimeSupport.toLocalDateTime(endTime));
        }
        Page<TaskInstanceEntity> result = taskService.page(new Page<>(page, pageSize), wrapper);
        return new TaskPage(result.getTotal(),
                result.getRecords().stream()
                        .map(this::toRecord)
                        .collect(Collectors.toList()));
    }

    @Override
    public long countByStatuses(Collection<TaskStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return taskService.count();
        }
        return taskService.count(new QueryWrapper<TaskInstanceEntity>().lambda()
                .in(TaskInstanceEntity::getStatus, statuses));
    }

    @Override
    public long countSubmittedSince(Instant start, Instant end) {
        return taskService.count(new QueryWrapper<TaskInstanceEntity>().lambda()
                .ge(TaskInstanceEntity::getSubmitTime, DbTimeSupport.toLocalDateTime(start))
                .lt(TaskInstanceEntity::getSubmitTime, DbTimeSupport.toLocalDateTime(end)));
    }

    @Override
    public List<TaskInstanceRecord> listSubmittedSince(Instant start) {
        return taskService.list(new QueryWrapper<TaskInstanceEntity>().lambda()
                        .select(TaskInstanceEntity::getSubmitTime)
                        .ge(TaskInstanceEntity::getSubmitTime, DbTimeSupport.toLocalDateTime(start)))
                .stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskInstanceRecord> listByDefinitionId(Long definitionId, int limit) {
        return taskService.page(new Page<>(1, Math.max(1, limit), false),
                        new QueryWrapper<TaskInstanceEntity>().lambda()
                                .eq(TaskInstanceEntity::getDefinitionId, definitionId)
                                .orderByDesc(TaskInstanceEntity::getSubmitTime))
                .getRecords().stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    private TaskInstanceEntity toEntity(TaskInstanceRecord record) {
        TaskInstanceEntity entity = new TaskInstanceEntity();
        entity.setId(record.getId());
        entity.setInstanceKey(record.getInstanceKey());
        entity.setDefinitionId(record.getDefinitionId());
        entity.setSerialNo(record.getSerialNo());
        entity.setTenantId(record.getTenantId());
        entity.setTraceId(record.getTraceId());
        entity.setCorrelationId(record.getCorrelationId());
        entity.setRequestPayload(record.getRequestPayload());
        entity.setRequestHash(record.getRequestHash());
        entity.setStatus(record.getStatus());
        entity.setPriority(record.getPriority());
        entity.setScheduleTime(DbTimeSupport.toLocalDateTime(record.getScheduleTime()));
        entity.setSubmitTime(DbTimeSupport.toLocalDateTime(record.getSubmitTime()));
        entity.setStartTime(DbTimeSupport.toLocalDateTime(record.getStartTime()));
        entity.setEndTime(DbTimeSupport.toLocalDateTime(record.getEndTime()));
        entity.setExecuteNodeKey(record.getExecuteNodeKey());
        entity.setResultArtifactId(record.getResultArtifactId());
        entity.setErrorCode(record.getErrorCode());
        entity.setErrorMessage(record.getErrorMessage());
        entity.setRetryCount(record.getRetryCount());
        entity.setMaxRetryCount(record.getMaxRetryCount());
        entity.setCreatedAt(DbTimeSupport.toLocalDateTime(record.getCreatedAt()));
        entity.setUpdatedAt(DbTimeSupport.toLocalDateTime(record.getUpdatedAt()));
        return entity;
    }

    private TaskInstanceRecord toRecord(TaskInstanceEntity entity) {
        if (entity == null) {
            return null;
        }
        return TaskInstanceRecord.builder()
                .id(entity.getId())
                .instanceKey(entity.getInstanceKey())
                .definitionId(entity.getDefinitionId())
                .serialNo(entity.getSerialNo())
                .tenantId(entity.getTenantId())
                .traceId(entity.getTraceId())
                .correlationId(entity.getCorrelationId())
                .requestPayload(entity.getRequestPayload())
                .requestHash(entity.getRequestHash())
                .status(entity.getStatus())
                .priority(entity.getPriority())
                .scheduleTime(DbTimeSupport.toInstant(entity.getScheduleTime()))
                .submitTime(DbTimeSupport.toInstant(entity.getSubmitTime()))
                .startTime(DbTimeSupport.toInstant(entity.getStartTime()))
                .endTime(DbTimeSupport.toInstant(entity.getEndTime()))
                .executeNodeKey(entity.getExecuteNodeKey())
                .resultArtifactId(entity.getResultArtifactId())
                .errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage())
                .retryCount(entity.getRetryCount())
                .maxRetryCount(entity.getMaxRetryCount())
                .createdAt(DbTimeSupport.toInstant(entity.getCreatedAt()))
                .updatedAt(DbTimeSupport.toInstant(entity.getUpdatedAt()))
                .build();
    }
}
