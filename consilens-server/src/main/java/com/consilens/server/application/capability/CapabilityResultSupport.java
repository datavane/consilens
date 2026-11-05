package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.domain.model.TaskExecutionResult;

final class CapabilityResultSupport {

    private CapabilityResultSupport() {
    }

    static TaskExecutionResult result(ArtifactRefDto artifact) {
        return TaskExecutionResult.builder()
                .artifactId(artifact.getId())
                .artifactType(artifact.getType())
                .artifactFormat(artifact.getFormat())
                .metadata(artifact.getMetadata())
                .build();
    }
}
