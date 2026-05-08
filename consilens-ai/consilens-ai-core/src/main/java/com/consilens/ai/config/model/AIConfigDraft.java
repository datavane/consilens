package com.consilens.ai.config.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured draft produced by AI or CLI hints before compiling to a Consilens config.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIConfigDraft {

    private DatasetDraft source;
    private DatasetDraft target;
    private MappingDraft mapping;
    private StrategyDraft strategy;
    private ResultDraft result;

    @Builder.Default
    private List<String> assumptions = new ArrayList<>();

    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
