package com.consilens.core.diff;

import com.consilens.connector.api.model.TablePath;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Unified diff result model combining the best implementations from all modules.
 */
@Data
@Builder(toBuilder = true)
@Slf4j
public class DiffResult {

    /**
     * Additive result/artifact schema version. Bumped only for breaking changes
     * to field meaning or required fields.
     */
    public static final int RESULT_SCHEMA_VERSION = 1;

    /**
     * List of differences found.
     */
    private List<DiffRow> differences;

    /**
     * 容差内（精确不等、但按 tolerance/relativeTolerance 判等）的行样本。
     * 默认口径下这些行不计入 {@link #differences} 与 totalDifferences，只用于报告单列。
     */
    @Builder.Default
    private List<DiffRow> withinToleranceDifferences = Collections.emptyList();

    /**
     * Statistics about the diff operation.
     */
    private DiffStatistics statistics;

    /**
     * Per-column statistics accumulated from the complete run result.
     * Empty when the caller did not request column stats or the result was not post-processed.
     */
    @Builder.Default
    private List<ColumnStats> columnStats = Collections.emptyList();

    /**
     * Structured schema compatibility report produced by the validation phase.
     * Null for results created outside the standard TableDiffer flow.
     */
    private SchemaCompatibility schemaCompatibility;

    /**
     * Reproducibility fingerprint (safe configuration + engine version).
     */
    private RunFingerprint fingerprint;

    /**
     * Execution evidence derived from the existing InfoTree/metadata.
     */
    private DiffEvidence evidence;

    /**
     * Information tree tracking the diff process.
     */
    private Optional<InfoTree> infoTree;

    /**
     * When the diff was completed.
     */
    private Instant completedAt;

    /**
     * Additional metadata.
     */
    private Map<String, Object> metadata;

    /**
     * Source table path.
     */
    private TablePath sourceTablePath;

    /**
     * Target table path.
     */
    private TablePath targetTablePath;

    /**
     * Create empty diff result.
     */
    public static DiffResult empty(TablePath sourcePath, TablePath targetPath) {
        return builder()
                .differences(Collections.emptyList())
                .statistics(DiffStatistics.empty())
                .sourceTablePath(sourcePath)
                .targetTablePath(targetPath)
                .completedAt(Instant.now())
                .metadata(new HashMap<>())
                .build();
    }

    /**
     * Create diff result with differences.
     */
    public static DiffResult of(List<DiffRow> differences, TablePath sourcePath, TablePath targetPath) {
        DiffStatistics stats = calculateStatistics(differences);
        return builder()
                .differences(new ArrayList<>(differences))
                .statistics(stats)
                .sourceTablePath(sourcePath)
                .targetTablePath(targetPath)
                .completedAt(Instant.now())
                .metadata(new HashMap<>())
                .build();
    }

    /**
     * Check if there are any differences.
     */
    public boolean hasDifferences() {
        return differences != null && !differences.isEmpty();
    }

    /**
     * Get total number of differences.
     */
    public long getDifferenceCount() {
        return differences != null ? differences.size() : 0;
    }

    /**
     * Get differences by operation type.
     */
    public Map<DiffOperation, List<DiffRow>> getDifferencesByOperation() {
        if (differences == null || differences.isEmpty()) {
            return Map.of();
        }

        return differences.stream()
                .collect(Collectors.groupingBy(DiffRow::getOperation));
    }

    /**
     * Get added rows only.
     */
    public List<DiffRow> getAddedRows() {
        return getDifferencesByOperation().getOrDefault(DiffOperation.SOURCE_MISSING, Collections.emptyList());
    }

    /**
     * Get removed rows only.
     */
    public List<DiffRow> getRemovedRows() {
        return getDifferencesByOperation().getOrDefault(DiffOperation.TARGET_MISSING, Collections.emptyList());
    }

    /**
     * Get modified rows only.
     */
    public List<DiffRow> getModifiedRows() {
        return getDifferencesByOperation().getOrDefault(DiffOperation.MISMATCH, Collections.emptyList());
    }

    /**
     * Get summary string of the diff result.
     */
    public String getSummary() {
        if (statistics == null) {
            return "No statistics available";
        }

        String summary = String.format(
                "Source: %s (%d rows), Target: %s (%d rows), Differences: %d (source missing: %d, target missing: %d, mismatched: %d), %.2f%% difference",
                sourceTablePath.getTableName(),
                statistics.getSourceRowCount(),
                targetTablePath.getTableName(),
                statistics.getTargetRowCount(),
                statistics.getTotalDifferences(),
                statistics.getSourceMissingCount(),
                statistics.getTargetMissingCount(),
                statistics.getMismatchCount(),
                statistics.getDifferencePercentage()
        );
        if (statistics.getWithinToleranceCount() > 0) {
            summary += " (" + statistics.getWithinToleranceCount() + " within tolerance, not counted)";
        }
        return summary;
    }

    /**
     * Get detailed statistics as a map.
     */
    public Map<String, Object> getStatisticsMap() {
        Map<String, Object> statsMap = new HashMap<>();

        if (statistics != null) {
            statsMap.put("sourceRowCount", statistics.getSourceRowCount());
            statsMap.put("targetRowCount", statistics.getTargetRowCount());
            statsMap.put("sourceMissingCount", statistics.getSourceMissingCount());
            statsMap.put("targetMissingCount", statistics.getTargetMissingCount());
            statsMap.put("mismatchCount", statistics.getMismatchCount());
            statsMap.put("unchangedCount", statistics.getUnchangedCount());
            statsMap.put("totalDifferences", statistics.getTotalDifferences());
            statsMap.put("differencePercentage", statistics.getDifferencePercentage());
            statsMap.put("processingTimeMs", statistics.getProcessingTimeMs());
        }

        statsMap.put("withinToleranceCount", statistics == null ? 0L : statistics.getWithinToleranceCount());
        statsMap.put("differenceCount", getDifferenceCount());
        statsMap.put("hasDifferences", hasDifferences());
        statsMap.put("completedAt", completedAt == null ? null : completedAt.toString());

        return statsMap;
    }

    /**
     * Create a copy with additional metadata.
     */
    public DiffResult withMetadata(String key, Object value) {
        Map<String, Object> newMetadata = new HashMap<>(metadata == null ? Map.of() : metadata);
        newMetadata.put(key, value);

        return this.toBuilder()
                .metadata(newMetadata)
                .build();
    }

    /**
     * Merge with another diff result.
     */
    public DiffResult merge(DiffResult other) {
        if (other == null) {
            return this;
        }

        // builder 构造的对象这几个字段可以是 null，合并前先归一化
        List<DiffRow> mergedDifferences = new ArrayList<>(
                this.differences == null ? List.of() : this.differences);
        if (other.differences != null) {
            mergedDifferences.addAll(other.differences);
        }

        List<DiffRow> mergedWithinTolerance = new ArrayList<>(
                this.withinToleranceDifferences == null ? List.of() : this.withinToleranceDifferences);
        if (other.withinToleranceDifferences != null) {
            mergedWithinTolerance.addAll(other.withinToleranceDifferences);
        }

        DiffStatistics mergedStats = statistics == null ? other.statistics : statistics.merge(other.statistics);

        Map<String, Object> mergedMetadata = new HashMap<>(
                this.metadata == null ? Map.of() : this.metadata);
        if (other.metadata != null) {
            mergedMetadata.putAll(other.metadata);
        }

        Instant mergedCompletedAt = completedAt == null ? other.completedAt
                : other.completedAt == null || completedAt.isAfter(other.completedAt) ? completedAt
                : other.completedAt;

        return builder()
                .differences(mergedDifferences)
                .withinToleranceDifferences(mergedWithinTolerance)
                .statistics(mergedStats)
                .infoTree(Optional.empty()) // Would need to merge info trees
                .completedAt(mergedCompletedAt)
                .metadata(mergedMetadata)
                .sourceTablePath(sourceTablePath)
                .targetTablePath(targetTablePath)
                .build();
    }

    /**
     * Calculate statistics from differences.
     */
    private static DiffStatistics calculateStatistics(List<DiffRow> differences) {
        long sourceMissingCount = 0;
        long targetMissingCount = 0;
        long mismatchCount = 0;

        if (differences != null) {
            for (DiffRow diff : differences) {
                switch (diff.getOperation()) {
                    case SOURCE_MISSING:
                        sourceMissingCount++;
                        break;
                    case TARGET_MISSING:
                        targetMissingCount++;
                        break;
                    case MISMATCH:
                        mismatchCount++;
                        break;
                }
            }
        }

        return DiffStatistics.builder()
                .sourceMissingCount(sourceMissingCount)
                .targetMissingCount(targetMissingCount)
                .mismatchCount(mismatchCount)
                .totalDifferences(sourceMissingCount + targetMissingCount + mismatchCount)
                .build();
    }

    /**
     * Statistics about the diff operation.
     */
    @Data
    @Builder
    public static class DiffStatistics {
        private final long sourceRowCount;
        private final long targetRowCount;
        private final long sourceMissingCount;
        private final long targetMissingCount;
        private final long mismatchCount;
        private final long totalDifferences;
        private final long processingTimeMs;
        private final long unchangedCount;
        /** 容差内行数（默认口径下不计入 totalDifferences，仅报告展示）。 */
        @Builder.Default
        private final long withinToleranceCount = 0L;

        public static DiffStatistics empty() {
            return builder()
                    .sourceRowCount(0)
                    .targetRowCount(0)
                    .sourceMissingCount(0)
                    .targetMissingCount(0)
                    .mismatchCount(0)
                    .totalDifferences(0)
                    .processingTimeMs(0)
                    .unchangedCount(0)
                    .withinToleranceCount(0)
                    .build();
        }

        public long getTotalDifferences() {
            return sourceMissingCount + targetMissingCount + mismatchCount;
        }

        /**
         * 差异占比，0..100：全站（前端进度条/matchScore 阈值、DiffReportSupport、
         * percentageFromCounts）统一按百分数消费。
         */
        public double getDifferencePercentage() {
            // 分母取两侧行数之和：仅取 max 时，若一侧有 2N 行、另一侧 0 行，
            // SM+TM 会达到 200% 以上，matchScore 变负、进度条/阈值全部失真。
            long totalRows = sourceRowCount + targetRowCount;
            if (totalRows <= 0) {
                return 0.0;
            }
            double percentage = (double) getTotalDifferences() / totalRows * 100.0;
            return Math.max(0.0, Math.min(100.0, percentage));
        }

        public long getUnchangedCount() {
            return Math.max(0, Math.min(sourceRowCount, targetRowCount) - mismatchCount);
        }

        public DiffStatistics merge(DiffStatistics other) {
            if (other == null) {
                return this;
            }

            return builder()
                    .sourceRowCount(Math.max(sourceRowCount, other.sourceRowCount))
                    .targetRowCount(Math.max(targetRowCount, other.targetRowCount))
                    .sourceMissingCount(sourceMissingCount + other.sourceMissingCount)
                    .targetMissingCount(targetMissingCount + other.targetMissingCount)
                    .mismatchCount(mismatchCount + other.mismatchCount)
                    .processingTimeMs(Math.max(processingTimeMs, other.processingTimeMs))
                    .unchangedCount(unchangedCount + other.unchangedCount)
                    .withinToleranceCount(withinToleranceCount + other.withinToleranceCount)
                    .build();
        }
    }

    /**
     * Information tree for tracking diff progress.
     */
    @Data
    @Builder
    public static class InfoTree {
        private final String rootNodeId;
        private final int totalNodes;
        private final long maxDepth;
        private final long startTime;
        private final long endTime;
        private final long durationMs;
        private final int totalSegments;
        private final int processedSegments;
        private final int skippedSegments;
        private final int localCompareSegments;
        private final int bisectSegments;
        private final int adaptiveSegments;
        private final long totalRowsScanned;
        private final long totalDifferences;
        private final Map<String, Long> stageTimeMs;
        private final Map<String, Object> metrics;
        private final List<InfoTreeNode> nodes;

        public static InfoTree empty() {
            return builder()
                    .rootNodeId("")
                    .totalNodes(0)
                    .maxDepth(0)
                    .startTime(0)
                    .endTime(0)
                    .durationMs(0)
                    .totalSegments(0)
                    .processedSegments(0)
                    .skippedSegments(0)
                    .localCompareSegments(0)
                    .bisectSegments(0)
                    .adaptiveSegments(0)
                    .totalRowsScanned(0)
                    .totalDifferences(0)
                    .stageTimeMs(new HashMap<>())
                    .metrics(new HashMap<>())
                    .nodes(new ArrayList<>())
                    .build();
        }
    }

    @Data
    @Builder
    public static class InfoTreeNode {
        private final String nodeId;
        private final String parentNodeId;
        private final String name;
        private final String phase;
        private final String segmentId;
        private final long depth;
        private final String minKey;
        private final String maxKey;
        private final String whereClause;
        private final long sourceCount;
        private final long targetCount;
        private final long maxCount;
        private final String splitType;
        private final int splitFactor;
        private final int splitTimes;
        private final long queryCount;
        private final long rowsFetched;
        private final long bytesFetched;
        private final long diffCount;
        private final long sourceMissing;
        private final long targetMissing;
        private final long mismatch;
        private final String decisionReason;
        private final String error;
        private final long startedAt;
        private final long endedAt;
        private final long durationMs;
        private final Map<String, Object> metrics;
    }
}
