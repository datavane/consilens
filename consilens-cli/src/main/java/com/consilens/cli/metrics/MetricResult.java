package com.consilens.cli.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One metric comparison result. Values are strings so JSON output can keep the
 * exact decimal representation and use {@code null} for "no value".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricResult {

    private String metric;
    private String group;
    private String sourceValue;
    private String targetValue;
    private String delta;
    private String relativePercent;
    private boolean passed;
}
