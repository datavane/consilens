package com.consilens.benchmark.report;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 单次端到端测量样本，保留原始指标以便审查聚合结果。 */
@Data
public class BenchmarkSample {

    private int iteration;
    private long durationMs;
    private Map<String, Double> metrics = new LinkedHashMap<>();
}
