package com.consilens.server.infrastructure.db.repository;

import com.consilens.server.domain.model.ServerNodeRecord;
import com.consilens.server.domain.repository.ServerNodeRepository;
import com.consilens.server.infrastructure.db.entity.ServerNodeEntity;
import com.consilens.server.infrastructure.db.service.ServerNodeService;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class ServerNodeRepositoryImpl implements ServerNodeRepository {

    private final ServerNodeService serverNodeService;

    public ServerNodeRepositoryImpl(ServerNodeService serverNodeService) {
        this.serverNodeService = serverNodeService;
    }

    @Override
    public List<ServerNodeRecord> findAll() {
        return serverNodeService.listAllOrdered().stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<ServerNodeRecord> findByNodeKey(String nodeKey) {
        return Optional.ofNullable(serverNodeService.getByNodeKey(nodeKey))
                .map(this::toRecord);
    }

    @Override
    public List<ServerNodeRecord> findAliveSince(Instant cutoff) {
        return serverNodeService.listAliveSince(DbTimeSupport.toLocalDateTime(cutoff)).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public int deleteStaleNodesBefore(Instant cutoff) {
        return serverNodeService.deleteStaleBefore(DbTimeSupport.toLocalDateTime(cutoff));
    }

    @Override
    public ServerNodeRecord save(ServerNodeRecord record) {
        ServerNodeEntity saved = serverNodeService.saveOrUpdateByNodeKey(toEntity(record));
        return toRecord(saved);
    }

    private ServerNodeEntity toEntity(ServerNodeRecord record) {
        ServerNodeEntity entity = new ServerNodeEntity();
        entity.setId(record.getId());
        entity.setNodeKey(record.getNodeKey());
        entity.setHost(record.getHost());
        entity.setPort(record.getPort());
        entity.setMachineCode(record.getMachineCode());
        entity.setStatus(record.getStatus());
        entity.setLoadAverage(record.getLoadAverage());
        entity.setAvailableMemoryMb(record.getAvailableMemoryMb());
        entity.setHeartbeatTime(DbTimeSupport.toLocalDateTime(record.getHeartbeatTime()));
        entity.setStatusUpdateTime(DbTimeSupport.toLocalDateTime(record.getStatusUpdateTime()));
        entity.setStatusUpdateBy(record.getStatusUpdateBy());
        entity.setCreatedAt(DbTimeSupport.toLocalDateTime(record.getCreatedAt()));
        entity.setUpdatedAt(DbTimeSupport.toLocalDateTime(record.getUpdatedAt()));
        return entity;
    }

    private ServerNodeRecord toRecord(ServerNodeEntity entity) {
        if (entity == null) {
            return null;
        }
        return ServerNodeRecord.builder()
                .id(entity.getId())
                .nodeKey(entity.getNodeKey())
                .host(entity.getHost())
                .port(entity.getPort() == null ? 0 : entity.getPort())
                .machineCode(entity.getMachineCode())
                .status(entity.getStatus())
                .loadAverage(entity.getLoadAverage() == null ? 0d : entity.getLoadAverage())
                .availableMemoryMb(entity.getAvailableMemoryMb() == null ? 0d : entity.getAvailableMemoryMb())
                .heartbeatTime(DbTimeSupport.toInstant(entity.getHeartbeatTime()))
                .statusUpdateTime(DbTimeSupport.toInstant(entity.getStatusUpdateTime()))
                .statusUpdateBy(entity.getStatusUpdateBy())
                .createdAt(DbTimeSupport.toInstant(entity.getCreatedAt()))
                .updatedAt(DbTimeSupport.toInstant(entity.getUpdatedAt()))
                .build();
    }
}
