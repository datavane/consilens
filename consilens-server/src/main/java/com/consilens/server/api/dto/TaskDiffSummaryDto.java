package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-task diff summary shown in the task list (optional, enabled via includeDiffSummary).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDiffSummaryDto {

    /** Total number of difference rows (statistics.totalDifferences). */
    private Long differenceCount;

    /** Difference ratio: totalDifferences / max(sourceRowCount, targetRowCount). */
    private Double differencePercentage;

    /** EXCELLENT | GOOD | POOR | NONE. */
    private String status;
}
