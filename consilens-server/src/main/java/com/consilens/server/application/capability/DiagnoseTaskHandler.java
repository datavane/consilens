package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.DiagnoseRequest;
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
public class DiagnoseTaskHandler implements CapabilityHandler<DiagnoseRequest> {

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public DiagnoseTaskHandler(ArtifactService artifactService, ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.DIAGNOSE;
    }

    @Override
    public TaskExecutionResult handle(TaskExecutionContext context, DiagnoseRequest request) {
        ArtifactContentDto runArtifact = artifactService.getArtifactContent(request.getRunArtifactId());
        if (!ArtifactKind.RUN_RESULT.name().equals(runArtifact.getArtifactType())) {
            throw new InvalidInputException("runArtifactId must reference a RUN_RESULT artifact");
        }
        Map<String, Object> runResult = readMap(runArtifact.getContent());
        Map<String, Object> diagnosis = diagnose(request, runResult);
        ArtifactRefDto artifact = artifactService.writeArtifact(context,
                ArtifactKind.DIAGNOSIS,
                "json",
                diagnosis,
                Map.of("source", "diagnose", "runArtifactId", request.getRunArtifactId()));
        return CapabilityResultSupport.result(artifact);
    }

    private Map<String, Object> diagnose(DiagnoseRequest request, Map<String, Object> runResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean success = Boolean.TRUE.equals(runResult.get("success"));
        boolean dryRun = Boolean.TRUE.equals(runResult.get("dryRun"));
        boolean statisticsUnavailable = Boolean.TRUE.equals(runResult.get("statisticsUnavailable"));
        long differenceCount = longValue(runResult.get("differenceCount"));
        List<String> findings = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        if (!success) {
            findings.add("run failed with " + string(runResult.get("errorCode")));
            recommendations.add("repair config inputs and re-run validation before retrying run");
        } else if (dryRun) {
            // dryRun 只校验配置和连通性，没有执行比对；0 差异不代表数据一致
            findings.add("dry run completed; no comparison was performed");
            recommendations.add("run without dryRun to obtain actual differences");
        } else if (statisticsUnavailable) {
            findings.add("run succeeded but comparison statistics are unavailable");
            recommendations.add("inspect the cluster application logs/diagnostics before drawing conclusions");
        } else if (differenceCount > 0) {
            findings.add("run completed with " + differenceCount + " differences");
            recommendations.add("review comparison keys, ignored columns, filters, and source/target data freshness");
        } else {
            findings.add("run completed without detected differences");
        }
        result.put("runArtifactId", request.getRunArtifactId());
        result.put("runSucceeded", success);
        result.put("dryRun", dryRun);
        result.put("differenceCount", differenceCount);
        result.put("statisticsAvailable", !dryRun && !statisticsUnavailable);
        result.put("statistics", runResult.getOrDefault("statistics", Map.of()));
        result.put("errorCode", runResult.get("errorCode"));
        result.put("errorMessage", runResult.get("errorMessage"));
        result.put("findings", findings);
        result.put("recommendations", recommendations);
        result.put("options", request.getOptions());
        return result;
    }

    private Map<String, Object> readMap(String content) {
        try {
            return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            throw new InvalidInputException("RUN_RESULT artifact content is not valid JSON");
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
                throw new InvalidInputException("RUN_RESULT differenceCount must be numeric");
            }
        }
        return 0L;
    }

    private String string(Object value) {
        return value == null ? "UNKNOWN" : String.valueOf(value);
    }
}
