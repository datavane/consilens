package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.RepairRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskExecutionResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RepairTaskHandler implements CapabilityHandler<RepairRequest> {

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public RepairTaskHandler(ArtifactService artifactService, ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.REPAIR;
    }

    @Override
    public TaskExecutionResult handle(TaskExecutionContext context, RepairRequest request) {
        ArtifactContentDto diagnosisArtifact = artifactService.getArtifactContent(request.getDiagnosisArtifactId());
        if (!ArtifactKind.DIAGNOSIS.name().equals(diagnosisArtifact.getArtifactType())) {
            throw new InvalidInputException("diagnosisArtifactId must reference a DIAGNOSIS artifact");
        }
        Map<String, Object> diagnosis = readMap(diagnosisArtifact.getContent());
        Map<String, Object> repair = repair(request, diagnosis);
        ArtifactRefDto artifact = artifactService.writeArtifact(context,
                ArtifactKind.REPAIR_CONFIG,
                "json",
                repair,
                Map.of("source", "repair", "diagnosisArtifactId", request.getDiagnosisArtifactId()));
        return CapabilityResultSupport.result(artifact);
    }

    private Map<String, Object> repair(RepairRequest request, Map<String, Object> diagnosis) {
        List<String> proposals = new ArrayList<>();
        if (diagnosis.get("errorCode") != null) {
            proposals.add("fix " + diagnosis.get("errorCode") + " before submitting run again");
        }
        if (Boolean.FALSE.equals(diagnosis.get("statisticsAvailable"))) {
            proposals.add("diagnosis has no comparison statistics; run a non-dry-run comparison before repairing config");
        }
        if (longValue(diagnosis.get("differenceCount")) > 0) {
            proposals.add("confirm comparison keys and add non-business columns to comparison.ignoreColumns when appropriate");
            proposals.add("tighten source/target filters to compare the same business time window");
        }
        if (proposals.isEmpty()) {
            proposals.add("no config change is required based on the current diagnosis");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diagnosisArtifactId", request.getDiagnosisArtifactId());
        result.put("requireApproval", Boolean.TRUE.equals(options(request).getRequireApproval()));
        result.put("status", "PROPOSED");
        result.put("proposals", proposals);
        result.put("diagnosisSummary", diagnosis);
        return result;
    }

    private Map<String, Object> readMap(String content) {
        try {
            return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            throw new InvalidInputException("DIAGNOSIS artifact content is not valid JSON");
        }
    }

    private long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException exception) {
                throw new InvalidInputException("DIAGNOSIS differenceCount must be numeric");
            }
        }
        return 0L;
    }

    private RepairRequest.Options options(RepairRequest request) {
        return request.getOptions() != null ? request.getOptions() : new RepairRequest.Options();
    }
}
