package com.consilens.benchmark.report;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单个基准场景的执行结果，供 micro/e2e/runner 聚合与报告复用。
 *
 * 字段命名与 runner 调用方对齐：scenarioId、durationMs、status(String)。
 */
@Data
public class BenchmarkResult {

    /** 场景编号，如 M01.100000.noDiff、E02。 */
    private String scenarioId;

    /** 场景类型（"micro" / "e2e"），用于阈值等按类型区分的策略。 */
    private String kind;

    /** 主指标值，如 ops/s 或端到端耗时(ms)。 */
    private double score;

    /** 主指标单位，如 "ops/s"、"ms"。 */
    private String unit;

    /** 附加子指标，如 p99、sampleCount。 */
    private Map<String, Double> subMetrics = new LinkedHashMap<>();

    /** 原始测量样本；micro 结果由 JMH 自身保存统计，因此可为空。 */
    private List<BenchmarkSample> samples = new ArrayList<>();

    /** 无法通用采集的指标及原因，避免把缺失值误报为 0。 */
    private Map<String, String> unavailableMetrics = new LinkedHashMap<>();

    /** 场景维度，如策略、键分布和差异类型。 */
    private Map<String, String> dimensions = new LinkedHashMap<>();

    /** 执行状态字符串："RUN"、"SKIPPED"、"FAILED"。 */
    private String status = "RUN";

    /** 执行耗时(ms)，0 表示未采集。 */
    private long durationMs;

    /** ISO 时间戳。 */
    private String timestamp;

    /** 附加消息（如 SKIPPED/FAILED 原因）。 */
    private String message;
}
