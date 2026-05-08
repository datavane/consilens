package com.consilens.ai.config.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Strategy draft for AI-assisted config generation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyDraft {

    private String mode;
    private String algorithm;
    private Integer bisectionFactor;
    private Long bisectionThreshold;
    private Integer batchSize;
    private Long maxDifferences;
}
