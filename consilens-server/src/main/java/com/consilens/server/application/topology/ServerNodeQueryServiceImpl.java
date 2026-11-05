package com.consilens.server.application.topology;

import com.consilens.server.api.dto.NodeSummaryDto;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.ServerNodeRecord;
import com.consilens.server.domain.repository.ServerNodeRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ServerNodeQueryServiceImpl implements ServerNodeQueryService {

    private final ServerNodeRepository serverNodeRepository;
    private final ConsilensServerProperties properties;

    public ServerNodeQueryServiceImpl(ServerNodeRepository serverNodeRepository,
                                         ConsilensServerProperties properties) {
        this.serverNodeRepository = serverNodeRepository;
        this.properties = properties;
    }

    @Override
    public List<NodeSummaryDto> listNodes() {
        return serverNodeRepository.findAll().stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @Override
    public NodeSummaryDto getNode(String nodeKey) {
        return serverNodeRepository.findByNodeKey(nodeKey)
                .map(this::toSummary)
                .orElseThrow(() -> new ResourceNotFoundException("Node not found: " + nodeKey));
    }

    @Override
    public List<ServerNodeRecord> listAliveNodes() {
        Instant cutoff = Instant.now().minusSeconds(properties.getNode().getExpireSeconds());
        return serverNodeRepository.findAliveSince(cutoff);
    }

    private NodeSummaryDto toSummary(ServerNodeRecord record) {
        return NodeSummaryDto.builder()
                .nodeKey(record.getNodeKey())
                .host(record.getHost())
                .port(record.getPort())
                .status(record.getStatus().name())
                .loadAverage(record.getLoadAverage())
                .availableMemoryMb(record.getAvailableMemoryMb())
                .build();
    }
}
