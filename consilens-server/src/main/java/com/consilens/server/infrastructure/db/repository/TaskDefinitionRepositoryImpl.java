package com.consilens.server.infrastructure.db.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.consilens.server.domain.model.TaskDefinitionPage;
import com.consilens.server.domain.model.TaskDefinitionRecord;
import com.consilens.server.domain.repository.TaskDefinitionRepository;
import com.consilens.server.infrastructure.db.entity.TaskDefinitionEntity;
import com.consilens.server.infrastructure.db.service.TaskDefinitionPersistenceService;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class TaskDefinitionRepositoryImpl implements TaskDefinitionRepository {

    private final TaskDefinitionPersistenceService persistenceService;

    public TaskDefinitionRepositoryImpl(TaskDefinitionPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @Override
    public TaskDefinitionRecord save(TaskDefinitionRecord record) {
        TaskDefinitionEntity entity = toEntity(record);
        if (record.getId() == null) {
            persistenceService.save(entity);
        } else {
            persistenceService.updateById(entity);
        }
        return toRecord(entity);
    }

    @Override
    public Optional<TaskDefinitionRecord> findById(Long id) {
        return Optional.ofNullable(persistenceService.getById(id)).map(this::toRecord);
    }

    @Override
    public Optional<TaskDefinitionRecord> findByName(String name) {
        return persistenceService.lambdaQuery()
                .eq(TaskDefinitionEntity::getName, name)
                .last("LIMIT 1")
                .oneOpt()
                .map(this::toRecord);
    }

    @Override
    public TaskDefinitionPage listPage(int page, int pageSize, String keyword, Boolean enabled) {
        LambdaQueryWrapper<TaskDefinitionEntity> wrapper = new LambdaQueryWrapper<TaskDefinitionEntity>()
                .orderByDesc(TaskDefinitionEntity::getUpdatedAt);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(TaskDefinitionEntity::getName, keyword.trim());
        }
        if (enabled != null) {
            wrapper.eq(TaskDefinitionEntity::getEnabled, enabled);
        }
        Page<TaskDefinitionEntity> result = persistenceService.page(
                new Page<>(page, pageSize), wrapper);
        return new TaskDefinitionPage(result.getTotal(),
                result.getRecords().stream().map(this::toRecord).collect(Collectors.toList()));
    }

    @Override
    public void deleteById(Long id) {
        persistenceService.removeById(id);
    }

    @Override
    public void updateLastRunAt(Long id, Instant lastRunAt) {
        persistenceService.lambdaUpdate()
                .eq(TaskDefinitionEntity::getId, id)
                .set(TaskDefinitionEntity::getLastRunAt, DbTimeSupport.toLocalDateTime(lastRunAt))
                .update();
    }

    @Override
    public Map<Long, String> findNamesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return persistenceService.listByIds(ids).stream()
                .collect(Collectors.toMap(TaskDefinitionEntity::getId, TaskDefinitionEntity::getName));
    }

    private TaskDefinitionEntity toEntity(TaskDefinitionRecord record) {
        TaskDefinitionEntity entity = new TaskDefinitionEntity();
        entity.setId(record.getId());
        entity.setDefinitionKey(record.getDefinitionKey());
        entity.setName(record.getName());
        entity.setDescription(record.getDescription());
        entity.setTaskType(record.getTaskType());
        entity.setConfig(record.getConfig());
        entity.setEnabled(record.getEnabled());
        entity.setScheduleType(record.getScheduleType());
        entity.setCronExpr(record.getCronExpr());
        entity.setLastRunAt(DbTimeSupport.toLocalDateTime(record.getLastRunAt()));
        if (record.getCreatedAt() != null) {
            entity.setCreatedAt(DbTimeSupport.toLocalDateTime(record.getCreatedAt()));
        } else {
            entity.setCreatedAt(DbTimeSupport.toLocalDateTime(Instant.now()));
        }
        entity.setUpdatedAt(DbTimeSupport.toLocalDateTime(Instant.now()));
        return entity;
    }

    private TaskDefinitionRecord toRecord(TaskDefinitionEntity entity) {
        if (entity == null) {
            return null;
        }
        return TaskDefinitionRecord.builder()
                .id(entity.getId())
                .definitionKey(entity.getDefinitionKey())
                .name(entity.getName())
                .description(entity.getDescription())
                .taskType(entity.getTaskType())
                .config(entity.getConfig())
                .enabled(entity.getEnabled())
                .scheduleType(entity.getScheduleType())
                .cronExpr(entity.getCronExpr())
                .lastRunAt(DbTimeSupport.toInstant(entity.getLastRunAt()))
                .createdAt(DbTimeSupport.toInstant(entity.getCreatedAt()))
                .updatedAt(DbTimeSupport.toInstant(entity.getUpdatedAt()))
                .build();
    }
}
