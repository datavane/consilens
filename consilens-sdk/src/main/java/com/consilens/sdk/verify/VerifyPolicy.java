package com.consilens.sdk.verify;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * CI gate policy evaluated against a completed diff result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyPolicy {

    @Builder.Default
    private FailOn failOn = FailOn.ANY;

    /** Mismatch rows / total rows (0..1). Null disables the check. */
    private Double maxMismatchRate;

    /** |sourceRows - targetRows| / max(sourceRows, targetRows) * 100. Null disables the check. */
    private Double maxRowCountDeltaPct;

    /** Requires a baseline; null means 0 when failOn=NEW. */
    private Long maxNewDifferences;

    /** JSONL diff-record file used as baseline; required when failOn=NEW. */
    private Path baselineFile;

    /** Columns that must participate in the comparison. */
    @Builder.Default
    private java.util.List<String> requiredColumns = java.util.Collections.emptyList();
}
