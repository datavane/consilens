package com.consilens.server.application.diff;

import com.consilens.server.api.dto.DiffReportDto;

/**
 * Builds the aggregated diff report for a task from its RUN_RESULT artifact.
 */
public interface DiffReportService {

    /**
     * Returns the diff report for a task.
     *
     * <p>Non-succeeded tasks or tasks without a RUN_RESULT artifact return a report with
     * status NONE and empty data (HTTP 200); unknown task ids raise ResourceNotFoundException.
     */
    DiffReportDto getDiffReport(String taskId);
}
