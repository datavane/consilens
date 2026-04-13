package com.consilens.performance;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of a performance test execution.
 */
@Data
@Builder
public class PerformanceTestResult {

    /** Test configuration */
    private PerformanceTestConfig config;

    /** Performance metrics */
    private PerformanceMetrics metrics;

    /** Individual test results */
    private List<PerformanceTestRunner.TestResult> testResults;

    /** Whether the test was successful */
    private boolean success;

    /** Error message if test failed */
    private String errorMessage;

    /**
     * Get a summary of the test result.
     */
    public String getSummary() {
        if (!success) {
            return String.format("Test '%s' FAILED: %s",
                    config.getTestName(), errorMessage);
        }

        return metrics != null ? metrics.getSummary() : "No metrics available";
    }
}
