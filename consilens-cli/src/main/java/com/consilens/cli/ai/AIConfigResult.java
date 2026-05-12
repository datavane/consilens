package com.consilens.cli.ai;

import com.consilens.ai.config.model.AIConfigDraft;
import com.consilens.ai.config.model.AIConfigIssue;
import com.consilens.cli.model.CliConfiguration;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Result of AI-assisted configuration generation.
 */
@Value
@Builder
public class AIConfigResult {

    AIConfigDraft draft;
    CliConfiguration configuration;
    String yaml;
    List<AIConfigIssue> issues;
    boolean valid;
    boolean dryRunPassed;
}
