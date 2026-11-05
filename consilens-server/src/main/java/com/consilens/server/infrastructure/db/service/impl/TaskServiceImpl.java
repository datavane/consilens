package com.consilens.server.infrastructure.db.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.infrastructure.db.entity.TaskInstanceEntity;
import com.consilens.server.infrastructure.db.mapper.TaskInstanceMapper;
import com.consilens.server.infrastructure.db.service.TaskService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Service
public class TaskServiceImpl extends ServiceImpl<TaskInstanceMapper, TaskInstanceEntity> implements TaskService {

    @Override
    public TaskInstanceEntity getByTaskKey(String instanceKey) {
        return getOne(new QueryWrapper<TaskInstanceEntity>().lambda()
                .eq(TaskInstanceEntity::getInstanceKey, instanceKey), false);
    }

    @Override
    public TaskInstanceEntity getBySerialNo(String serialNo) {
        return getOne(new QueryWrapper<TaskInstanceEntity>().lambda()
                .eq(TaskInstanceEntity::getSerialNo, serialNo), false);
    }

    @Override
    public TaskInstanceEntity lockBySerialNo(String serialNo) {
        return baseMapper.lockBySerialNo(serialNo);
    }

    @Override
    public boolean updateStatus(Long taskId, TaskStatus status, LocalDateTime now) {
        return update(new UpdateWrapper<TaskInstanceEntity>().lambda()
                .set(TaskInstanceEntity::getStatus, status)
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId));
    }

    @Override
    public boolean updateClaimed(Long taskId, String executeNodeKey, LocalDateTime now) {
        return update(new UpdateWrapper<TaskInstanceEntity>().lambda()
                .set(TaskInstanceEntity::getStatus, TaskStatus.CLAIMED)
                .set(TaskInstanceEntity::getExecuteNodeKey, executeNodeKey)
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId)
                .eq(TaskInstanceEntity::getStatus, TaskStatus.PENDING));
    }

    @Override
    public boolean updateRunning(Long taskId, String executeNodeKey, LocalDateTime now) {
        return update(new UpdateWrapper<TaskInstanceEntity>().lambda()
                .set(TaskInstanceEntity::getStatus, TaskStatus.RUNNING)
                .set(TaskInstanceEntity::getExecuteNodeKey, executeNodeKey)
                .set(TaskInstanceEntity::getStartTime, now)
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId)
                .eq(TaskInstanceEntity::getStatus, TaskStatus.CLAIMED));
    }

    @Override
    public boolean renewRunning(Long taskId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskInstanceEntity>().lambda()
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId)
                .eq(TaskInstanceEntity::getStatus, TaskStatus.RUNNING));
    }

    @Override
    public boolean updateSuccess(Long taskId, String artifactId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskInstanceEntity>().lambda()
                .set(TaskInstanceEntity::getStatus, TaskStatus.SUCCEEDED)
                .set(TaskInstanceEntity::getResultArtifactId, artifactId)
                .set(TaskInstanceEntity::getEndTime, now)
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId)
                .eq(TaskInstanceEntity::getStatus, TaskStatus.RUNNING));
    }

    @Override
    public boolean updateFailure(Long taskId,
                                 TaskStatus status,
                                 String errorCode,
                                 String errorMessage,
                                 LocalDateTime now) {
        UpdateWrapper<TaskInstanceEntity> wrapper = new UpdateWrapper<>();
        wrapper.lambda()
                .set(TaskInstanceEntity::getStatus, status)
                .set(TaskInstanceEntity::getErrorCode, errorCode)
                .set(TaskInstanceEntity::getErrorMessage, errorMessage)
                .set(TaskInstanceEntity::getEndTime, now)
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId)
                .eq(TaskInstanceEntity::getStatus, TaskStatus.RUNNING);
        if (TaskStatus.RETRYABLE.equals(status)) {
            wrapper.setSql("retry_count = retry_count + 1");
        }
        return update(wrapper);
    }

    @Override
    public boolean updateFailureFromStatuses(Long taskId,
                                             Collection<TaskStatus> statuses,
                                             TaskStatus status,
                                             String errorCode,
                                             String errorMessage,
                                             LocalDateTime now) {
        if (statuses == null || statuses.isEmpty()) {
            return false;
        }
        UpdateWrapper<TaskInstanceEntity> wrapper = new UpdateWrapper<>();
        wrapper.lambda()
                .set(TaskInstanceEntity::getStatus, status)
                .set(TaskInstanceEntity::getErrorCode, errorCode)
                .set(TaskInstanceEntity::getErrorMessage, errorMessage)
                .set(TaskInstanceEntity::getEndTime, now)
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId)
                .in(TaskInstanceEntity::getStatus, statuses);
        if (TaskStatus.RETRYABLE.equals(status)) {
            wrapper.setSql("retry_count = retry_count + 1");
        }
        return update(wrapper);
    }

    @Override
    public boolean releaseClaimed(Long taskId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskInstanceEntity>().lambda()
                .set(TaskInstanceEntity::getStatus, TaskStatus.PENDING)
                .set(TaskInstanceEntity::getExecuteNodeKey, null)
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId)
                .eq(TaskInstanceEntity::getStatus, TaskStatus.CLAIMED));
    }

    @Override
    public boolean resetForRetry(Long taskId, String traceId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskInstanceEntity>().lambda()
                .set(TaskInstanceEntity::getStatus, TaskStatus.PENDING)
                .set(TaskInstanceEntity::getTraceId, traceId)
                .set(TaskInstanceEntity::getStartTime, null)
                .set(TaskInstanceEntity::getEndTime, null)
                .set(TaskInstanceEntity::getExecuteNodeKey, null)
                .set(TaskInstanceEntity::getErrorCode, null)
                .set(TaskInstanceEntity::getErrorMessage, null)
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId)
                .in(TaskInstanceEntity::getStatus, TaskStatus.FAILED, TaskStatus.RETRYABLE, TaskStatus.CANCELLED));
    }

    @Override
    public boolean cancel(Long taskId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskInstanceEntity>().lambda()
                .set(TaskInstanceEntity::getStatus, TaskStatus.CANCELLED)
                .set(TaskInstanceEntity::getErrorCode, "CANCELLED")
                .set(TaskInstanceEntity::getErrorMessage, "Cancelled by API request")
                .set(TaskInstanceEntity::getEndTime, now)
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId)
                .in(TaskInstanceEntity::getStatus, TaskStatus.PENDING, TaskStatus.CLAIMED));
    }

    @Override
    public boolean requestCancellation(Long taskId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskInstanceEntity>().lambda()
                .set(TaskInstanceEntity::getStatus, TaskStatus.CANCEL_REQUESTED)
                .set(TaskInstanceEntity::getErrorCode, "CANCEL_REQUESTED")
                .set(TaskInstanceEntity::getErrorMessage, "Cancellation requested by API request")
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId)
                .eq(TaskInstanceEntity::getStatus, TaskStatus.RUNNING));
    }

    @Override
    public boolean confirmCancellation(Long taskId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskInstanceEntity>().lambda()
                .set(TaskInstanceEntity::getStatus, TaskStatus.CANCELLED)
                .set(TaskInstanceEntity::getErrorCode, "CANCELLED")
                .set(TaskInstanceEntity::getErrorMessage, "Cancelled after execution stopped")
                .set(TaskInstanceEntity::getEndTime, now)
                .set(TaskInstanceEntity::getUpdatedAt, now)
                .eq(TaskInstanceEntity::getId, taskId)
                .eq(TaskInstanceEntity::getStatus, TaskStatus.CANCEL_REQUESTED));
    }

    @Override
    public List<TaskInstanceEntity> listByExecuteNodeAndStatuses(String executeNodeKey,
                                                         Collection<TaskStatus> statuses,
                                                         int limit) {
        if (limit <= 0 || statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return list(new QueryWrapper<TaskInstanceEntity>().lambda()
                .eq(TaskInstanceEntity::getExecuteNodeKey, executeNodeKey)
                .in(TaskInstanceEntity::getStatus, statuses)
                .orderByAsc(TaskInstanceEntity::getUpdatedAt)
                .last("LIMIT " + limit));
    }

    @Override
    public List<TaskInstanceEntity> listByStatusesExcludingExecuteNodes(Collection<TaskStatus> statuses,
                                                               Set<String> executeNodeKeys,
                                                               int limit) {
        if (limit <= 0 || statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        QueryWrapper<TaskInstanceEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .in(TaskInstanceEntity::getStatus, statuses)
                .isNotNull(TaskInstanceEntity::getExecuteNodeKey)
                .orderByAsc(TaskInstanceEntity::getUpdatedAt)
                .last("LIMIT " + limit);
        if (executeNodeKeys != null && !executeNodeKeys.isEmpty()) {
            wrapper.lambda().notIn(TaskInstanceEntity::getExecuteNodeKey, executeNodeKeys);
        }
        return list(wrapper);
    }

    @Override
    public List<TaskInstanceEntity> listStaleRunning(LocalDateTime before, int limit) {
        return listStaleByStatus(TaskStatus.RUNNING, before, limit);
    }

    @Override
    public List<TaskInstanceEntity> listStaleByStatus(TaskStatus status, LocalDateTime before, int limit) {
        if (limit <= 0 || status == null) {
            return List.of();
        }
        return list(new QueryWrapper<TaskInstanceEntity>().lambda()
                .eq(TaskInstanceEntity::getStatus, status)
                .lt(TaskInstanceEntity::getUpdatedAt, before)
                .orderByAsc(TaskInstanceEntity::getUpdatedAt)
                .last("LIMIT " + limit));
    }
}
