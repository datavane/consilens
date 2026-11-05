package com.consilens.core.diff;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured schema compatibility report for one comparison run.
 *
 * <p>This is an additive view over the existing validation performed by
 * {@code TableDiffer.validateAndAdjustColumns}. It does not change comparison
 * behavior; it only exposes the findings to SDK/server/UI consumers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaCompatibility {

    public enum Status {
        COMPATIBLE,
        COMPATIBLE_WITH_NORMALIZATION,
        INCOMPATIBLE
    }

    public enum Action {
        DIRECT,
        NORMALIZE,
        INCOMPATIBLE,
        MISSING_SOURCE,
        MISSING_TARGET
    }

    private Status status;

    @Builder.Default
    private List<ColumnCompatibility> columns = Collections.emptyList();

    @Builder.Default
    private List<String> warnings = Collections.emptyList();

    public static SchemaCompatibility compatible() {
        return SchemaCompatibility.builder()
                .status(Status.COMPATIBLE)
                .columns(new ArrayList<>())
                .warnings(new ArrayList<>())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnCompatibility {
        private String sourceColumn;
        private String targetColumn;
        private String sourceType;
        private String targetType;
        private Action action;
        private String reason;
    }
}
