package com.consilens.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceReportGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateParentDirectoriesAndWriteReports() throws Exception {
        PerformanceTestConfig config = PerformanceTestConfig.builder()
                .testName("report-smoke")
                .warmupIterations(0)
                .testIterations(1)
                .build();
        PerformanceMetrics metrics = PerformanceMetrics.builder()
                .testName("report-smoke")
                .startTime(Instant.now())
                .endTime(Instant.now())
                .totalDurationMs(100)
                .totalRowsProcessed(1000)
                .totalBytesProcessed(1024 * 1024)
                .differencesFound(2)
                .build();
        metrics.getLatency().addLatency(10);
        metrics.getLatency().addLatency(20);
        metrics.getLatency().calculatePercentiles();
        PerformanceTestResult result = PerformanceTestResult.builder()
                .config(config)
                .metrics(metrics)
                .success(true)
                .build();
        PerformanceReportGenerator generator = new PerformanceReportGenerator();
        Path markdown = tempDir.resolve("nested/reports/performance.md");
        Path html = tempDir.resolve("nested/reports/performance.html");

        generator.generateMarkdownReport(result, markdown.toString());
        generator.generateHtmlReport(result, html.toString());

        String markdownContent = Files.readString(markdown);
        String htmlContent = Files.readString(html);
        assertTrue(markdownContent.contains("report-smoke"));
        assertTrue(markdownContent.contains("1,000"));
        assertTrue(htmlContent.contains("report-smoke"));
        assertTrue(htmlContent.contains("<html"));
    }
}
