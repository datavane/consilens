package com.consilens.ai.config.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured issue found while validating an AI-generated config draft.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIConfigIssue {

    private Severity severity;
    private String path;
    private String code;
    private String message;

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }
}
