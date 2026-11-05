package com.consilens.server.infrastructure.db.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.model.ArtifactPage;
import com.consilens.server.domain.model.ArtifactRecord;

import java.time.Instant;
import java.util.Collection;

import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.infrastructure.db.entity.ArtifactEntity;
import com.consilens.server.infrastructure.db.service.ArtifactPersistenceService;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class ArtifactRepositoryImpl implements ArtifactRepository {

    private final ArtifactPersistenceService artifactPersistenceService;

    public ArtifactRepositoryImpl(ArtifactPersistenceService artifactPersistenceService) {
        this.artifactPersistenceService = artifactPersistenceService;
    }

    @Override
    public Optional<ArtifactRecord> findById(String artifactId) {
        return Optional.ofNullable(artifactPersistenceService.getById(artifactId))
                .map(this::toRecord);
    }

    @Override
    public List<ArtifactRecord> listByTaskId(Long taskId) {
        return artifactPersistenceService.listByTaskId(taskId).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public ArtifactRecord save(ArtifactRecord artifactRecord) {
        ArtifactEntity entity = toEntity(artifactRecord);
        artifactPersistenceService.save(entity);
        return toRecord(entity);
    }

    @Override
    public ArtifactRecord update(ArtifactRecord artifactRecord) {
        ArtifactEntity entity = toEntity(artifactRecord);
        artifactPersistenceService.updateById(entity);
        return toRecord(entity);
    }

    @Override
    public ArtifactPage listArtifactPage(int page,
                                         int pageSize,
                                         Collection<ArtifactKind> kinds,
                                         String artifactIdLike,
                                         Instant startTime,
                                         Instant endTime) {
        LambdaQueryWrapper<ArtifactEntity> wrapper = new QueryWrapper<ArtifactEntity>().lambda()
                .orderByDesc(ArtifactEntity::getCreatedAt)
                .orderByDesc(ArtifactEntity::getId);
        if (kinds != null && !kinds.isEmpty()) {
            wrapper.in(ArtifactEntity::getArtifactType, kinds);
        }
        if (artifactIdLike != null && !artifactIdLike.isBlank()) {
            wrapper.like(ArtifactEntity::getId, artifactIdLike.trim());
        }
        if (startTime != null) {
            wrapper.ge(ArtifactEntity::getCreatedAt, DbTimeSupport.toLocalDateTime(startTime));
        }
        if (endTime != null) {
            wrapper.le(ArtifactEntity::getCreatedAt, DbTimeSupport.toLocalDateTime(endTime));
        }
        Page<ArtifactEntity> result = artifactPersistenceService.page(new Page<>(page, pageSize), wrapper);
        return new ArtifactPage(result.getTotal(),
                result.getRecords().stream()
                        .map(this::toRecord)
                        .collect(Collectors.toList()));
    }

    @Override
    public List<ArtifactRecord> listCreatedSince(Instant start) {
        return artifactPersistenceService.list(new QueryWrapper<ArtifactEntity>().lambda()
                        .select(ArtifactEntity::getId,
                                ArtifactEntity::getArtifactType,
                                ArtifactEntity::getStorageUri,
                                ArtifactEntity::getMetadataJson,
                                ArtifactEntity::getDifferenceCount,
                                ArtifactEntity::getCreatedAt)
                        .ge(ArtifactEntity::getCreatedAt, DbTimeSupport.toLocalDateTime(start)))
                .stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    private ArtifactEntity toEntity(ArtifactRecord record) {
        ArtifactEntity entity = new ArtifactEntity();
        entity.setId(record.getId());
        entity.setTaskId(record.getTaskId());
        entity.setTraceId(record.getTraceId());
        entity.setArtifactType(record.getArtifactType());
        entity.setArtifactFormat(record.getArtifactFormat());
        entity.setStorageType(record.getStorageType());
        entity.setStorageUri(record.getStorageUri());
        entity.setSha256(record.getSha256());
        entity.setMetadataJson(record.getMetadataJson());
        entity.setStatisticsJson(record.getStatisticsJson());
        entity.setDifferenceCount(record.getDifferenceCount());
        entity.setDifferenceTruncated(record.getDifferenceTruncated());
        entity.setDifferenceRows(record.getDifferenceRows());
        entity.setDifferencesUri(record.getDifferencesUri());
        entity.setCreatedAt(DbTimeSupport.toLocalDateTime(record.getCreatedAt()));
        return entity;
    }

    private ArtifactRecord toRecord(ArtifactEntity entity) {
        if (entity == null) {
            return null;
        }
        return ArtifactRecord.builder()
                .id(entity.getId())
                .taskId(entity.getTaskId())
                .traceId(entity.getTraceId())
                .artifactType(entity.getArtifactType())
                .artifactFormat(entity.getArtifactFormat())
                .storageType(entity.getStorageType())
                .storageUri(entity.getStorageUri())
                .sha256(entity.getSha256())
                .metadataJson(entity.getMetadataJson())
                .statisticsJson(entity.getStatisticsJson())
                .differenceCount(entity.getDifferenceCount())
                .differenceTruncated(entity.getDifferenceTruncated())
                .differenceRows(entity.getDifferenceRows())
                .differencesUri(entity.getDifferencesUri())
                .createdAt(DbTimeSupport.toInstant(entity.getCreatedAt()))
                .build();
    }
}
