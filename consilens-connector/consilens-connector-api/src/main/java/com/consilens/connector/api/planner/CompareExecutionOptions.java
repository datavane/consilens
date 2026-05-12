package com.consilens.connector.api.planner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompareExecutionOptions {

    private Integer bisectionFactor;

    private Long bisectionThreshold;

    private Boolean enableProfiling;

    private String checksumAlgorithm;

    private String localCompareMode;

    private Boolean validateUniqueKeys;

    private Long maxDifferences;

    private Map<String, Object> attributes;
}
