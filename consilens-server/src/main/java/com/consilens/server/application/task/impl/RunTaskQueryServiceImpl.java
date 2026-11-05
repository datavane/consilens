package com.consilens.server.application.task.impl;

import com.consilens.server.api.dto.ArtifactListDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.TaskDiffSummaryDto;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.api.dto.TaskSummaryDto;
import com.consilens.server.application.connection.JdbcConnectionSupport;
import com.consilens.server.application.diff.DiffReportSupport;
import com.consilens.server.application.task.RunTaskQueryService;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.model.TaskPage;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.domain.model.TaskDefinitionRecord;
import com.consilens.server.domain.repository.TaskDefinitionRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RunTaskQueryServiceImpl implements RunTaskQueryService {

    /**
     * 列表差异摘要在该大小以下才读取 content 解析（避免对大 RUN_RESULT 全量 IO）。
     */
    private static final long MAX_SUMMARY_CONTENT_BYTES = 2L * 1024 * 1024;

    private final TaskRepository taskRepository;
    private final ArtifactRepository artifactRepository;
    private final ArtifactContentStore artifactContentStore;
    private final TaskDefinitionRepository definitionRepository;
    private final ObjectMapper objectMapper;

    public RunTaskQueryServiceImpl(TaskRepository taskRepository,
                                   ArtifactRepository artifactRepository,
                                   ArtifactContentStore artifactContentStore,
                                   TaskDefinitionRepository definitionRepository,
                                   ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.artifactRepository = artifactRepository;
        this.artifactContentStore = artifactContentStore;
        this.definitionRepository = definitionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskQueryResponse getTask(String taskId, String traceId) {
        TaskInstanceRecord task = taskRepository.resolveByRef(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        String definitionName = task.getDefinitionId() != null
                ? definitionRepository.findById(task.getDefinitionId()).map(TaskDefinitionRecord::getName).orElse(null)
                : null;
        return TaskQueryResponse.builder()
                .taskId(task.getInstanceKey())
                .serialNo(task.getSerialNo())
                .definitionId(task.getDefinitionId() != null ? String.valueOf(task.getDefinitionId()) : null)
                .definitionName(definitionName)
                .taskType("RUN")
                .status(task.getStatus().name())
                .traceId(task.getTraceId())
                .executeNodeKey(task.getExecuteNodeKey())
                .retryCount(task.getRetryCount())
                .errorCode(task.getErrorCode())
                .errorMessage(maskedErrorMessage(task))
                .submitTime(task.getSubmitTime())
                .startTime(task.getStartTime())
                .endTime(task.getEndTime())
                .artifacts(artifactRepository.listByTaskId(task.getId()).stream()
                        .map(this::toArtifactRef)
                        .collect(Collectors.toList()))
                .availableNextActions(nextActions(task))
                .build();
    }

    @Override
    public PageResponse<TaskSummaryDto> listTasks(int page,
                                                  int pageSize,
                                                  Collection<TaskStatus> statuses,
                                                  String keyword,
                                                  String executeNodeKey,
                                                  Instant startTime,
                                                  Instant endTime,
                                                  boolean includeDiffSummary,
                                                  Long definitionId,
                                                  String traceId) {
        TaskPage taskPage = taskRepository.listTaskPage(page,
                pageSize,
                statuses,
                keyword,
                executeNodeKey,
                startTime,
                endTime,
                definitionId);
        List<TaskInstanceRecord> items = taskPage.getItems();
        Map<Long, String> definitionNames = definitionRepository.findNamesByIds(items.stream()
                .map(TaskInstanceRecord::getDefinitionId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));
        List<TaskSummaryDto> dtoItems = items.stream()
                .map(task -> toSummary(task, includeDiffSummary, definitionNames))
                .collect(Collectors.toList());
        return PageResponse.<TaskSummaryDto>builder()
                .total(taskPage.getTotal())
                .page(page)
                .pageSize(pageSize)
                .items(dtoItems)
                .build();
    }

    private TaskSummaryDto toSummary(TaskInstanceRecord task, boolean includeDiffSummary,
                                     Map<Long, String> definitionNames) {
        TaskDiffSummaryDto diffSummary = null;
        if (includeDiffSummary && task.getStatus() == TaskStatus.SUCCEEDED) {
            diffSummary = readDiffSummary(task);
        }
        return TaskSummaryDto.builder()
                .taskId(task.getInstanceKey())
                .id(String.valueOf(task.getId()))
                .serialNo(task.getSerialNo())
                .taskType("RUN")
                .status(task.getStatus().name())
                .traceId(task.getTraceId())
                .executeNodeKey(task.getExecuteNodeKey())
                .retryCount(task.getRetryCount())
                .errorCode(task.getErrorCode())
                .errorMessage(maskedErrorMessage(task, 500))
                .submitTime(task.getSubmitTime())
                .startTime(task.getStartTime())
                .endTime(task.getEndTime())
                .availableNextActions(nextActions(task))
                .diffSummary(diffSummary)
                .definitionId(task.getDefinitionId() != null ? String.valueOf(task.getDefinitionId()) : null)
                .definitionName(task.getDefinitionId() != null
                        ? definitionNames.get(task.getDefinitionId()) : null)
                .build();
    }

    /**
     * 历史失败记录可能写入未脱敏的驱动异常；对外暴露时再兜底脱敏一次，
     * 避免为补失败原因顺带把凭据带到 API 响应里。
     */
    private static String maskedErrorMessage(TaskInstanceRecord task) {
        return maskedErrorMessage(task, 4000);
    }

    private static String maskedErrorMessage(TaskInstanceRecord task, int max) {
        String masked = JdbcConnectionSupport.maskMessage(task.getErrorMessage());
        if (masked == null || masked.length() <= max) {
            return masked;
        }
        return masked.substring(0, max) + "...";
    }

    private TaskDiffSummaryDto readDiffSummary(TaskInstanceRecord task) {
        try {
            ArtifactRecord runResult = latestRunResult(task.getId());
            if (runResult == null) {
                return null;
            }
            if (runResult.getDifferenceCount() == null && runResult.getStorageUri() == null) {
                return null;
            }
            // 统计优先读 DB statistics_json（免文件 IO）；旧格式回退读 content
            JsonNode statistics = statisticsNode(runResult);
            long totalDifferenceCount;
            if (statistics != null && statistics.isObject()
                    && statistics.path("totalDifferences").isNumber()) {
                totalDifferenceCount = statistics.path("totalDifferences").asLong();
            } else if (runResult.getDifferenceCount() != null) {
                totalDifferenceCount = runResult.getDifferenceCount();
            } else {
                return null;
            }
            Double differencePercentage = statistics != null
                    && statistics.path("differencePercentage").isNumber()
                    ? statistics.path("differencePercentage").asDouble()
                    : null;
            // 注意：与 diff-report 链路不同，列表摘要不做 percentageFromCounts 回退——
            // 无 percentage 且差异>0 时 status 为 NONE（保持列表轻量），属有意设计
            Double matchScore = DiffReportSupport.matchScoreOf(differencePercentage, totalDifferenceCount);
            return TaskDiffSummaryDto.builder()
                    .differenceCount(totalDifferenceCount)
                    .differencePercentage(differencePercentage)
                    .status(DiffReportSupport.statusOf(matchScore, totalDifferenceCount))
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode statisticsNode(ArtifactRecord record) {
        if (record.getStatisticsJson() != null && !record.getStatisticsJson().isBlank()) {
            try {
                return objectMapper.readTree(record.getStatisticsJson());
            } catch (Exception ignored) {
                // fall through to content
            }
        }
        if (record.getStorageUri() == null) {
            return null;
        }
        try {
            // 列表页只做摘要：旧格式没有 statistics_json 时，大 RUN_RESULT 不读 content。
            if (artifactContentStore.size(record.getStorageUri()) > MAX_SUMMARY_CONTENT_BYTES) {
                return null;
            }
            return objectMapper.readTree(artifactContentStore.read(record.getStorageUri())).get("statistics");
        } catch (Exception ignored) {
            return null;
        }
    }

    private ArtifactRecord latestRunResult(Long taskId) {
        return artifactRepository.listByTaskId(taskId).stream()
                .filter(r -> r.getArtifactType() == ArtifactKind.RUN_RESULT)
                .max(Comparator.comparing(ArtifactRecord::getCreatedAt))
                .orElse(null);
    }

    private ArtifactListDto toArtifactRef(ArtifactRecord record) {
        return ArtifactListDto.builder()
                .artifactId(record.getId())
                .artifactType(record.getArtifactType().name())
                .format(record.getArtifactFormat())
                .sizeBytes(null)
                .createdAt(record.getCreatedAt())
                .differenceCount(record.getDifferenceCount())
                .differenceRows(record.getDifferenceRows())
                .differenceTruncated(record.getDifferenceTruncated())
                // 存储 URI 是服务端本地路径，不能回传给客户端；只暴露可用性
                .differenceDetailsAvailable(record.getDifferencesUri() != null
                        && !record.getDifferencesUri().isBlank())
                .statistics(parseStatistics(record.getStatisticsJson()))
                .build();
    }

    private Map<String, Object> parseStatistics(String statisticsJson) {
        if (statisticsJson == null || statisticsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(statisticsJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            return null;
        }
    }

    private List<String> nextActions(TaskInstanceRecord task) {
        if (task.getStatus() == TaskStatus.SUCCEEDED) {
            return List.of("diagnose", "repair");
        }
        if (task.getStatus() == TaskStatus.FAILED
                || task.getStatus() == TaskStatus.RETRYABLE
                || task.getStatus() == TaskStatus.CANCELLED) {
            return List.of("retry");
        }
        if (task.getStatus() == TaskStatus.PENDING
                || task.getStatus() == TaskStatus.CLAIMED
                || task.getStatus() == TaskStatus.RUNNING) {
            return List.of("cancel");
        }
        return List.of();
    }
}
