package com.consilens.server.application.capability.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComparisonConfig {

    /** Columns compared by the same name on both sides. */
    @Builder.Default
    private List<String> fields = new ArrayList<>();

    /** Columns excluded from comparison (by name, applied to both sides). */
    @Builder.Default
    private List<String> ignoreColumns = new ArrayList<>();

    /** One-to-one source→target column mappings (overrides fields when present). */
    @Builder.Default
    private List<FieldMapping> fieldMappings = new ArrayList<>();

    /** One-to-one source→target primary-key mappings (when key names differ). */
    @Builder.Default
    private List<FieldMapping> keyMappings = new ArrayList<>();

    /** Type-level normalization shared by both sides (CLI-compatible shape). */
    private NormalizationConfig normalization;
}
