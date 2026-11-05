package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.DiagnoseRequest;
import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.api.dto.RepairRequest;
import com.consilens.server.api.dto.SyncArtifactResponse;
import com.consilens.server.api.dto.ValidateRequest;
import com.consilens.server.application.topology.ServerTopologyService;
import com.consilens.server.domain.model.TaskExecutionContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class SynchronousCapabilityServiceImpl implements SynchronousCapabilityService {

    private final ServerCapabilityFacade capabilityFacade;
    private final ServerTopologyService serverTopologyService;

    public SynchronousCapabilityServiceImpl(ServerCapabilityFacade capabilityFacade,
                                              ServerTopologyService serverTopologyService) {
        this.capabilityFacade = capabilityFacade;
        this.serverTopologyService = serverTopologyService;
    }

    @Override
    public SyncArtifactResponse plan(PlanRequest request, String traceId) {
        ArtifactRefDto artifact = capabilityFacade.plan(request, context(traceId));
        return response(artifact, List.of("validate", "run"));
    }

    @Override
    public SyncArtifactResponse validate(ValidateRequest request, String traceId) {
        ArtifactRefDto artifact = capabilityFacade.validate(request, context(traceId));
        return response(artifact, List.of("run", "repair"));
    }

    @Override
    public SyncArtifactResponse diagnose(DiagnoseRequest request, String traceId) {
        ArtifactRefDto artifact = capabilityFacade.diagnose(request, context(traceId));
        return response(artifact, List.of("repair"));
    }

    @Override
    public SyncArtifactResponse repair(RepairRequest request, String traceId) {
        ArtifactRefDto artifact = capabilityFacade.repair(request, context(traceId));
        return response(artifact, List.of("validate", "run"));
    }

    private TaskExecutionContext context(String traceId) {
        return TaskExecutionContext.builder()
                .traceId(traceId)
                .nodeKey(serverTopologyService.currentNodeKey())
                .startTime(Instant.now())
                .build();
    }

    private SyncArtifactResponse response(ArtifactRefDto artifact, List<String> nextActions) {
        return SyncArtifactResponse.builder()
                .artifact(artifact)
                .availableNextActions(nextActions)
                .build();
    }
}
