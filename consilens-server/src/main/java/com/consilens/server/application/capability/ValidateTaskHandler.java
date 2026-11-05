package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.ValidateRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskExecutionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ValidateTaskHandler implements CapabilityHandler<ValidateRequest> {

    private final ArtifactService artifactService;
    private final ServerCompareConfigService configService;

    public ValidateTaskHandler(ArtifactService artifactService, ServerCompareConfigService configService) {
        this.artifactService = artifactService;
        this.configService = configService;
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.VALIDATE;
    }

    @Override
    public TaskExecutionResult handle(TaskExecutionContext context, ValidateRequest request) {
        ServerCompareConfig config = configService.fromValidateRequest(request);
        Map<String, Object> content = new LinkedHashMap<>(configService.validateContent(config));
        content.put("options", request.getOptions() != null ? request.getOptions() : Map.of());
        ArtifactRefDto artifact = artifactService.writeArtifact(context,
                ArtifactKind.VALIDATION_RESULT,
                "json",
                content,
                Map.of("source", "validate"));
        return CapabilityResultSupport.result(artifact);
    }
}
