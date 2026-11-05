package com.consilens.server.infrastructure.db.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.consilens.server.domain.model.DataSourcePage;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.infrastructure.db.entity.DataSourceEntity;
import com.consilens.server.infrastructure.db.service.DataSourcePersistenceService;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class DataSourceRepositoryImpl implements DataSourceRepository {

    private final DataSourcePersistenceService persistenceService;

    public DataSourceRepositoryImpl(DataSourcePersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @Override
    public DataSourceRecord save(DataSourceRecord record) {
        DataSourceEntity entity = toEntity(record);
        if (record.getId() == null) {
            persistenceService.save(entity);
        } else {
            persistenceService.updateById(entity);
        }
        return toRecord(entity);
    }

    @Override
    public Optional<DataSourceRecord> findById(Long id) {
        return Optional.ofNullable(persistenceService.getById(id)).map(this::toRecord);
    }

    @Override
    public Optional<DataSourceRecord> findByName(String name) {
        return persistenceService.lambdaQuery()
                .eq(DataSourceEntity::getName, name)
                .last("LIMIT 1")
                .oneOpt()
                .map(this::toRecord);
    }

    @Override
    public List<DataSourceRecord> listAll() {
        return persistenceService.lambdaQuery()
                .orderByAsc(DataSourceEntity::getName)
                .list()
                .stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public DataSourcePage listPage(int page, int pageSize) {
        Page<DataSourceEntity> result = persistenceService.page(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<DataSourceEntity>().orderByAsc(DataSourceEntity::getName));
        return new DataSourcePage(result.getTotal(),
                result.getRecords().stream().map(this::toRecord).collect(Collectors.toList()));
    }

    @Override
    public void deleteById(Long id) {
        persistenceService.removeById(id);
    }

    private DataSourceEntity toEntity(DataSourceRecord record) {
        DataSourceEntity entity = new DataSourceEntity();
        entity.setId(record.getId());
        entity.setName(record.getName());
        entity.setType(record.getType());
        entity.setParam(record.getParamJson());
        if (record.getCreatedAt() != null) {
            entity.setCreatedAt(DbTimeSupport.toLocalDateTime(record.getCreatedAt()));
        } else {
            entity.setCreatedAt(DbTimeSupport.toLocalDateTime(Instant.now()));
        }
        entity.setUpdatedAt(DbTimeSupport.toLocalDateTime(Instant.now()));
        return entity;
    }

    private DataSourceRecord toRecord(DataSourceEntity entity) {
        return DataSourceRecord.builder()
                .id(entity.getId())
                .name(entity.getName())
                .type(entity.getType())
                .paramJson(entity.getParam())
                .createdAt(DbTimeSupport.toInstant(entity.getCreatedAt()))
                .updatedAt(DbTimeSupport.toInstant(entity.getUpdatedAt()))
                .build();
    }
}
