package com.consilens.ai.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.List;
import java.util.Map;

/**
 * Represents a detected pattern within a diff result.
 */
@Data
@Builder
public class PatternMatch {

    /** Identifier of the matched pattern. */
    private String patternName;

    /** Category of pattern (e.g. TIME_DRIFT, PRECISION_LOSS). */
    private String patternType;

    /** Human-readable description of this match. */
    private String description;

    /** Number of rows affected by this pattern. */
    private int affectedRows;

    /** Confidence score for this match (0.0 - 1.0). */
    private double confidence;

    /** Column names involved in this pattern. */
    @Singular
    private List<String> affectedColumns;

    /** Additional details about the pattern match. */
    @Singular
    private Map<String, Object> details;

    /** Suggested repair action for this pattern. */
    private String repairHint;
}
