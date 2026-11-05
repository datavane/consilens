package com.consilens.connector.api.planner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompareExecutionOptions {

    private Integer bisectionFactor;

    private Long bisectionThreshold;

    private String checksumAlgorithm;

    private String localCompareMode;

    private Boolean validateUniqueKeys;

    /**
     * 是否允许可空键列。默认 false：键列在元数据中可空时直接拒绝比对，避免 NULL 键行
     * 因 SQL 三值逻辑落在所有分段之外而静默漏报。显式设为 true 时保留可空键列，
     * NULL 键按引擎的 IS NULL/归一化语义参与分段与比较。
     */
    private Boolean allowNullableKeys;

    private Long maxDifferences;

    /** 墙钟预算（毫秒）；null/非正数表示不限。分段边界与行批次检查。 */
    private Long maxDurationMs;

    /** 累计扫描行数预算；null/非正数表示不限。在已知取数点累计。 */
    private Long maxRowsScanned;

    /** 累计取回字节预算；null/非正数表示不限。在已知取数点累计（流式路径不含字节）。 */
    private Long maxBytesFetched;

    /**
     * 容差内行的处置口径：{@code ignore}（默认，判等、不计差异、报告单列）或
     * {@code report}（仍算差异）。未配置时按 ignore。
     */
    private String withinTolerance;

    /** 容差内行随结果保留的样本上限；null 表示用引擎默认值。0 表示只计数、不保留行。 */
    private Integer withinToleranceSamples;

    private Map<String, Object> attributes;

    /**
     * Optional progress callback. Runtime-only: never serialized into cluster
     * tasks or config artifacts.
     */
    @JsonIgnore
    private transient ProgressListener progressListener;
}
