package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.DiagnoseRequest;
import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.api.dto.RepairRequest;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.ValidateRequest;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskExecutionResult;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class ServerCapabilityFacadeImpl implements ServerCapabilityFacade {

    private final Map<CapabilityType, CapabilityHandler<?>> handlers = new EnumMap<>(CapabilityType.class);

    public ServerCapabilityFacadeImpl(List<CapabilityHandler<?>> handlerList) {
        handlerList.forEach(handler -> handlers.put(handler.type(), handler));
    }

    @Override
    public ArtifactRefDto plan(PlanRequest request, TaskExecutionContext context) {
        return toArtifactRef(invoke(CapabilityType.PLAN, request, context));
    }

    @Override
    public ArtifactRefDto validate(ValidateRequest request, TaskExecutionContext context) {
        return toArtifactRef(invoke(CapabilityType.VALIDATE, request, context));
    }

    @Override
    public ArtifactRefDto run(RunRequest request, TaskExecutionContext context) {
        return toArtifactRef(invoke(CapabilityType.RUN, request, context));
    }

    @Override
    public ArtifactRefDto diagnose(DiagnoseRequest request, TaskExecutionContext context) {
        return toArtifactRef(invoke(CapabilityType.DIAGNOSE, request, context));
    }

    @Override
    public ArtifactRefDto repair(RepairRequest request, TaskExecutionContext context) {
        return toArtifactRef(invoke(CapabilityType.REPAIR, request, context));
    }

    @SuppressWarnings("unchecked")
    private <I> TaskExecutionResult invoke(CapabilityType type, I request, TaskExecutionContext context) {
        CapabilityHandler<I> handler = (CapabilityHandler<I>) handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("No handler registered for capability type " + type);
        }
        return handler.handle(context, request);
    }

    private ArtifactRefDto toArtifactRef(TaskExecutionResult result) {
        return ArtifactRefDto.builder()
                .id(result.getArtifactId())
                .type(result.getArtifactType())
                .format(result.getArtifactFormat())
                .metadata(result.getMetadata())
                .build();
    }
}
