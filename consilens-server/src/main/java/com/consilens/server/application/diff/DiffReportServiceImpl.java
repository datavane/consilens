package com.consilens.server.application.diff;

import com.consilens.server.api.dto.DiffReportDto;
import com.consilens.core.diff.SchemaCompatibility;
import com.consilens.server.application.artifact.ArtifactQueryService;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default implementation reading the latest RUN_RESULT artifact of a succeeded task.
 */
@Service
public class DiffReportServiceImpl implements DiffReportService {

    private static final String OPERATION_MISMATCH = "MISMATCH";

    /** 报告内展示的差异样本条数上限；超过该值以 sampleTruncated 标记截断。 */
    private static final int SAMPLE_LIMIT = 100;

    private final TaskRepository taskRepository;
    private final ArtifactRepository artifactRepository;
    private final ArtifactContentStore artifactContentStore;
    private final ArtifactQueryService artifactQueryService;
    private final ObjectMapper objectMapper;

    public DiffReportServiceImpl(TaskRepository taskRepository,
                                 ArtifactRepository artifactRepository,
                                 ArtifactContentStore artifactContentStore,
                                 ArtifactQueryService artifactQueryService,
                                 ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.artifactRepository = artifactRepository;
        this.artifactContentStore = artifactContentStore;
        this.artifactQueryService = artifactQueryService;
        this.objectMapper = objectMapper;
    }

    @Override
    public DiffReportDto getDiffReport(String taskId) {
        TaskInstanceRecord task = taskRepository.resolveByRef(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        if (task.getStatus() != TaskStatus.SUCCEEDED) {
            return emptyReport(taskId);
        }
        ArtifactRecord runResult = latestRunResult(task.getId());
        if (runResult == null) {
            return emptyReport(taskId);
        }
        // 统计优先读 DB（statistics_json）；旧格式回退读 content
        JsonNode statistics = statisticsNode(runResult);

        boolean statisticsAvailable = statistics != null && statistics.isObject() && statistics.size() > 0;
        // 只有没有 statistics 时才需要读 content 旧格式（避免每份报告都多读一次大文件）
        JsonNode contentRoot = statisticsAvailable ? null : contentRootNode(runResult);
        // 必须是数值才算有效计数：null/字符串同样表示“未知”，不能用 asLong(0) 兜成 0
        boolean legacyCountAvailable = contentRoot != null && contentRoot.path("differenceCount").isNumber();
        if (!statisticsAvailable && !legacyCountAvailable) {
            // dryRun / 集群未回传统计：没有可比对的计数，不能推断成 100 分
            return emptyReport(taskId);
        }
        JsonNode differences = differencesNode(runResult);
        JsonNode columnStats = columnStatsNode(runResult);
        SchemaCompatibility schemaCompatibility = schemaCompatibilityNode(runResult);

        long totalDifferenceCount;
        Double differencePercentage;
        if (statisticsAvailable) {
            totalDifferenceCount = longValue(statistics, "totalDifferences", -1L);
            if (totalDifferenceCount < 0 && runResult.getDifferenceCount() != null) {
                totalDifferenceCount = runResult.getDifferenceCount();
            }
            if (totalDifferenceCount < 0) {
                // 统计对象存在但缺少 totalDifferences（部分旧格式/集群回传）：
                // 不能把“未知”当成 0，否则报告会错误显示 100 分。
                JsonNode fallbackContent = contentRootNode(runResult);
                if (fallbackContent != null && fallbackContent.path("differenceCount").isNumber()) {
                    totalDifferenceCount = fallbackContent.path("differenceCount").asLong();
                }
            }
            if (totalDifferenceCount < 0) {
                return emptyReport(taskId);
            }
            differencePercentage = doubleValue(statistics, "differencePercentage");
            if (differencePercentage == null) {
                differencePercentage = DiffReportSupport.percentageFromCounts(
                        totalDifferenceCount,
                        statistics.path("sourceRowCount").asLong(0),
                        statistics.path("targetRowCount").asLong(0));
            }
        } else {
            // 旧格式/无 statistics_json：回退 content 顶层计数
            totalDifferenceCount = contentRoot.path("differenceCount").asLong(0L);
            differencePercentage = contentRoot.path("differencePercentage").isNumber()
                    ? contentRoot.path("differencePercentage").asDouble() : null;
        }
        Double matchScore = DiffReportSupport.matchScoreOf(differencePercentage, totalDifferenceCount);

        return DiffReportDto.builder()
                .taskId(taskId)
                .status(DiffReportSupport.statusOf(matchScore, totalDifferenceCount))
                .matchScore(matchScore)
                .statistics(toMap(statistics))
                .columns(hasExactColumnStats(columnStats)
                        ? buildExactColumns(columnStats)
                        : buildColumns(differences))
                .schemaCompatibility(schemaCompatibility)
                .resultSchemaVersion(resultSchemaVersion(runResult))
                .samples(buildSamples(differences))
                .sampleSize((int) Math.min(SAMPLE_LIMIT, totalDifferenceCount))
                .totalDifferenceCount(totalDifferenceCount)
                .sampleTruncated(Boolean.TRUE.equals(runResult.getDifferenceTruncated())
                        || totalDifferenceCount > SAMPLE_LIMIT)
                .timeline(List.of())
                .build();
    }

    private DiffReportDto emptyReport(String taskId) {
        return DiffReportDto.builder()
                .taskId(taskId)
                .status(DiffReportSupport.STATUS_NONE)
                .matchScore(null)
                .statistics(null)
                .columns(List.of())
                .samples(List.of())
                .sampleSize(null)
                .totalDifferenceCount(null)
                .sampleTruncated(false)
                .timeline(List.of())
                .build();
    }

    /**
     * Read the additive result schema version from artifact metadata. Old
     * artifacts have no version and return null; readers must treat that as the
     * pre-version schema rather than failing.
     */
    private Integer resultSchemaVersion(ArtifactRecord record) {
        if (record == null || record.getMetadataJson() == null
                || record.getMetadataJson().isBlank()) {
            return null;
        }
        try {
            JsonNode version = objectMapper.readTree(record.getMetadataJson())
                    .path("resultSchemaVersion");
            return version.isInt() ? version.asInt() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 统计来源：新格式优先 DB statistics_json；旧格式从 content 解析。
     */
    private JsonNode contentRootNode(ArtifactRecord record) {
        try {
            return objectMapper.readTree(artifactContentStore.read(record.getStorageUri()));
        } catch (Exception ignored) {
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
        try {
            return objectMapper.readTree(artifactContentStore.read(record.getStorageUri())).get("statistics");
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 差异样本：新格式从差异文件分页读第一页；旧格式从 content.differences。
     */
    private JsonNode differencesNode(ArtifactRecord record) {
        if (record.getDifferencesUri() != null && !record.getDifferencesUri().isBlank()) {
            try {
                return objectMapper.valueToTree(artifactQueryService
                        .listDifferences(record.getId(), 0, SAMPLE_LIMIT, null, "diff-report").getItems());
            } catch (Exception ignored) {
                return null;
            }
        }
        try {
            return objectMapper.readTree(artifactContentStore.read(record.getStorageUri())).get("differences");
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

    private boolean hasExactColumnStats(JsonNode columnStats) {
        return columnStats != null && columnStats.isArray() && columnStats.size() > 0;
    }

    /**
     * Exact column stats are written into the RUN_RESULT content by RunTaskHandler.
     * They are optional so old artifacts keep falling back to sample aggregation.
     */
    private JsonNode columnStatsNode(ArtifactRecord record) {
        JsonNode root = contentRootNode(record);
        return root == null ? null : root.get("columnStats");
    }

    /**
     * Schema compatibility is an optional additive field written by the local run
     * path. Old artifacts and cluster runs fall back to null.
     */
    private SchemaCompatibility schemaCompatibilityNode(ArtifactRecord record) {
        JsonNode root = contentRootNode(record);
        JsonNode node = root == null ? null : root.get("schemaCompatibility");
        if (node == null || !node.isObject()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, SchemaCompatibility.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<DiffReportDto.ColumnStat> buildExactColumns(JsonNode columnStats) {
        List<DiffReportDto.ColumnStat> result = new ArrayList<>(columnStats.size());
        for (JsonNode node : columnStats) {
            if (node == null || !node.isObject()) {
                continue;
            }
            String name = textOrNull(node, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            result.add(DiffReportDto.ColumnStat.builder()
                    .name(name)
                    .sourceName(textOrNull(node, "sourceName"))
                    .targetName(textOrNull(node, "targetName"))
                    .insertCount(node.path("insertCount").asLong(0L))
                    .updateCount(node.path("updateCount").asLong(0L))
                    .deleteCount(node.path("deleteCount").asLong(0L))
                    .differenceCount(node.path("differenceCount").asLong(0L))
                    .differencePercentage(doubleValue(node, "differencePercentage"))
                    .differenceRate(doubleValue(node, "differenceRate"))
                    .aggregatedFromSamples(node.path("aggregatedFromSamples").asBoolean(false))
                    .truncated(node.path("truncated").asBoolean(false))
                    .build());
        }
        return result;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private List<DiffReportDto.ColumnStat> buildColumns(JsonNode differences) {        if (differences == null || !differences.isArray()) {
            return List.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        long mismatchRows = 0;
        for (JsonNode node : differences) {
            // differences jsonl 里 operation 为小写（mismatch），忽略大小写比较
            if (!OPERATION_MISMATCH.equalsIgnoreCase(node.path("operation").asText())) {
                continue;
            }
            mismatchRows++;
            JsonNode metadata = node.get("metadata");
            if (metadata == null || !metadata.isObject()) {
                continue;
            }
            Set<String> columns = new LinkedHashSet<>();
            collectStrings(metadata.get("changedColumns1"), columns);
            collectStrings(metadata.get("changedColumns2"), columns);
            for (String column : columns) {
                counts.merge(column, 1L, Long::sum);
            }
        }
        if (mismatchRows == 0) {
            return List.of();
        }
        final long mismatchRowCount = mismatchRows;
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> DiffReportDto.ColumnStat.builder()
                        .name(entry.getKey())
                        .insertCount(0L)
                        .updateCount(entry.getValue())
                        .deleteCount(0L)
                        .differenceCount(entry.getValue())
                        .differencePercentage(DiffReportSupport.round1(
                                entry.getValue() * 100.0 / mismatchRowCount))
                        .aggregatedFromSamples(true)
                        .build())
                .collect(Collectors.toList());
    }

    private List<DiffReportDto.SampleDiff> buildSamples(JsonNode differences) {
        if (differences == null || !differences.isArray()) {
            return List.of();
        }
        List<DiffReportDto.SampleDiff> samples = new ArrayList<>();
        for (JsonNode node : differences) {
            JsonNode metadata = node.get("metadata");
            Set<String> changedColumns = new LinkedHashSet<>();
            if (metadata != null && metadata.isObject()) {
                collectStrings(metadata.get("changedColumns1"), changedColumns);
                collectStrings(metadata.get("changedColumns2"), changedColumns);
            }
            samples.add(DiffReportDto.SampleDiff.builder()
                    .operation(node.path("operation").asText(null))
                    .primaryKey(primaryKeyOf(node.get("primaryKey")))
                    .metadata(toMap(metadata))
                    .changedColumns(new ArrayList<>(changedColumns))
                    .columnNames(textListOf(node.get("columnNames")))
                    .sourceValues(valueListOf(node.get("sourceValues")))
                    .targetValues(valueListOf(node.get("targetValues")))
                    .build());
        }
        return samples;
    }

    private void collectStrings(JsonNode node, Set<String> target) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (element.isTextual()) {
                    target.add(element.asText());
                } else if (element.isNumber() || element.isBoolean()) {
                    target.add(element.asText());
                }
            }
        } else if (node.isTextual()) {
            target.add(node.asText());
        }
    }

    private List<Object> primaryKeyOf(JsonNode primaryKey) {
        if (primaryKey == null || !primaryKey.isArray()) {
            return null;
        }
        List<Object> values = new ArrayList<>();
        for (JsonNode element : primaryKey) {
            if (element.isNull() || element.isMissingNode()) {
                values.add(null);
            } else if (element.isNumber()) {
                values.add(element.numberValue());
            } else if (element.isBoolean()) {
                values.add(element.asBoolean());
            } else {
                values.add(element.asText());
            }
        }
        return values;
    }

    private List<String> textListOf(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            values.add(element.isTextual() ? element.asText() : element.asText(null));
        }
        return values;
    }

    private List<Object> valueListOf(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<Object> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (element.isNull() || element.isMissingNode()) {
                values.add(null);
            } else if (element.isNumber()) {
                values.add(element.numberValue());
            } else if (element.isBoolean()) {
                values.add(element.asBoolean());
            } else {
                values.add(element.asText());
            }
        }
        return values;
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    private long longValue(JsonNode node, String field, long fallback) {
        if (node == null || !node.isObject()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asLong() : fallback;
    }

    private Double doubleValue(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

}
