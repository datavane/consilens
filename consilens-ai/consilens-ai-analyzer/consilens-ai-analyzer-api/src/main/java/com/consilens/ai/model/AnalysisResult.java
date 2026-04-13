package com.consilens.ai.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.List;
import java.util.Map;

/**
 * Result of AI-powered diff analysis.
 */
@Data
@Builder
public class AnalysisResult {

    /** Detected patterns in the diff. */
    @Singular
    private List<PatternMatch> patterns;

    /** Human-readable summary of the analysis. */
    private String summary;

    /** Overall confidence score (0.0 - 1.0). */
    private double confidence;

    /** Suggestions for repairing data inconsistencies. */
    @Singular
    private List<String> repairHints;

    /** Additional metadata. */
    @Singular("metadata")
    private Map<String, Object> metadata;
}
