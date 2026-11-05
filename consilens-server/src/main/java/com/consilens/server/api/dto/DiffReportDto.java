package com.consilens.server.api.dto;

import com.consilens.core.diff.SchemaCompatibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Aggregated diff report for a succeeded RUN task, derived from its latest RUN_RESULT artifact.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffReportDto {

    private String taskId;

    /** EXCELLENT | GOOD | POOR | NONE (NONE when no diff data is available). */
    private String status;

    /** 0..100 match score; null when no statistics are available. */
    private Double matchScore;

    /** Passthrough of the RUN_RESULT statistics map (may be null). */
    private Map<String, Object> statistics;

    /** Column-level change counts aggregated from the sampled diff rows. */
    private List<ColumnStat> columns;

    /** Structured schema compatibility report; null for old artifacts or dry runs. */
    private SchemaCompatibility schemaCompatibility;

    /** Result/artifact schema version; null for artifacts written before the field existed. */
    private Integer resultSchemaVersion;

    /** Sampled difference rows (bounded by the artifact sample size). */
    private List<SampleDiff> samples;

    /** Number of sampled rows persisted in the artifact (content.differenceSampleSize). */
    private Integer sampleSize;

    /** Total difference row count (statistics.totalDifferences). */
    private Long totalDifferenceCount;

    /** Whether the sample list was truncated (content.differenceSampleTruncated). */
    private Boolean sampleTruncated;

    /** Reserved extension point; always empty in this release. */
    private List<Object> timeline;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnStat {

        private String name;
        private String sourceName;
        private String targetName;
        private Long insertCount;
        private Long updateCount;
        private Long deleteCount;
        private Long differenceCount;

        /** Share of sampled MISMATCH rows touching this column (0..100, 1 decimal). */
        private Double differencePercentage;

        /** Difference count / total rows (0..100, 4 decimals); exact-mode supplementary rate. */
        private Double differenceRate;

        /** True when the counts are aggregated from a bounded sample. */
        private Boolean aggregatedFromSamples;

        /** Exact stats may cover only retained rows when maxDifferences truncates the run. */
        private Boolean truncated;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SampleDiff {

        /** MISMATCH | SOURCE_MISSING | TARGET_MISSING. */
        private String operation;

        private List<Object> primaryKey;

        /** Raw row metadata passthrough (may contain changedColumnIndices / other keys). */
        private Map<String, Object> metadata;

        /** Merged deduplicated changed column names (changedColumns1 union changedColumns2). */
        private List<String> changedColumns;

        /** 完整列名（与 sourceValues/targetValues 下标对齐；旧格式数据可能为 null）。 */
        private List<String> columnNames;

        /** 源表完整行值（MISMATCH / 删除行有值；新增行无）。 */
        private List<Object> sourceValues;

        /** 目标表完整行值（MISMATCH / 新增行有值；删除行无）。 */
        private List<Object> targetValues;
    }
}
