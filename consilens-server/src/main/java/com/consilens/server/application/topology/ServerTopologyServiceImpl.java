package com.consilens.server.application.topology;

import com.consilens.server.domain.model.ServerNodeRecord;
import com.consilens.server.domain.model.ServerTopologySnapshot;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ServerTopologyServiceImpl implements ServerTopologyService {

    private final ServerNodeQueryService serverNodeQueryService;
    private final LocalServerNodeLifecycle localServerNodeLifecycle;

    public ServerTopologyServiceImpl(ServerNodeQueryService serverNodeQueryService,
                                        LocalServerNodeLifecycle localServerNodeLifecycle) {
        this.serverNodeQueryService = serverNodeQueryService;
        this.localServerNodeLifecycle = localServerNodeLifecycle;
    }

    @Override
    public ServerTopologySnapshot snapshot() {
        List<ServerNodeRecord> liveNodes = serverNodeQueryService.listAliveNodes().stream()
                .sorted(Comparator.comparing(ServerNodeRecord::getNodeKey))
                .collect(Collectors.toList());
        int totalSlots = Math.max(liveNodes.size(), 1);
        String currentNodeKey = currentNodeKey();
        int currentSlot = 0;
        boolean currentNodeAlive = false;
        for (int i = 0; i < liveNodes.size(); i++) {
            if (liveNodes.get(i).getNodeKey().equals(currentNodeKey)) {
                currentSlot = i;
                currentNodeAlive = true;
                break;
            }
        }
        return ServerTopologySnapshot.builder()
                .liveNodes(liveNodes)
                .currentSlot(currentSlot)
                .totalSlot(totalSlots)
                .dispatchable(currentNodeAlive)
                .refreshedAt(Instant.now())
                .build();
    }

    @Override
    public String currentNodeKey() {
        return localServerNodeLifecycle.currentNodeKey();
    }
}
