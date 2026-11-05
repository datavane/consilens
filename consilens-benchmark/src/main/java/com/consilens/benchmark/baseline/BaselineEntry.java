package com.consilens.benchmark.baseline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 基线中单个场景的指标条目。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaselineEntry {

    /** 场景编号，与 BenchmarkResult.scenario 对齐。 */
    private String key;

    /** 基线主指标值。 */
    private double score;

    /** 主指标单位。 */
    private String unit;

    /** 相对漂移阈值，区间 (0,1]，如 0.15 表示 ±15%。 */
    private double threshold;
}
