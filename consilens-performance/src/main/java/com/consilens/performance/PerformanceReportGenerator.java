package com.consilens.performance;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Performance report generator that creates Markdown and HTML reports.
 */
@Slf4j
public class PerformanceReportGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generate a Markdown report.
     */
    public void generateMarkdownReport(PerformanceTestResult result, String outputPath) {
        try {
            String markdown = buildMarkdownReport(result);
            Path path = Paths.get(outputPath);
            createParentDirectories(path);
            Files.writeString(path, markdown);
            log.info("Markdown report generated: {}", outputPath);
        } catch (IOException e) {
            log.error("Failed to generate Markdown report", e);
            throw new RuntimeException("Failed to generate Markdown report", e);
        }
    }

    /**
     * Generate an HTML report.
     */
    public void generateHtmlReport(PerformanceTestResult result, String outputPath) {
        try {
            String html = buildHtmlReport(result);
            Path path = Paths.get(outputPath);
            createParentDirectories(path);
            Files.writeString(path, html);
            log.info("HTML report generated: {}", outputPath);
        } catch (IOException e) {
            log.error("Failed to generate HTML report", e);
            throw new RuntimeException("Failed to generate HTML report", e);
        }
    }

    /**
     * Build Markdown report content.
     */
    private String buildMarkdownReport(PerformanceTestResult result) {
        validateReportInput(result);
        StringBuilder md = new StringBuilder();

        PerformanceMetrics metrics = result.getMetrics();
        PerformanceTestConfig config = result.getConfig();

        // Header
        md.append("# 性能测试报告\n\n");
        md.append(String.format("**测试名称**: %s\n\n", config.getTestName()));
        md.append(String.format("**生成时间**: %s\n\n",
                java.time.LocalDateTime.now().format(DATE_FORMATTER)));

        // Executive Summary
        md.append("## 📊 执行摘要\n\n");

        if (!result.isSuccess()) {
            md.append("> ❌ **测试失败**: ").append(result.getErrorMessage()).append("\n\n");
            return md.toString();
        }

        md.append(String.format("- **测试状态**: ✅ 成功\n"));
        md.append(String.format("- **测试时长**: %,d ms (%.2f 秒)\n",
                metrics.getTotalDurationMs(), metrics.getTotalDurationMs() / 1000.0));
        md.append(String.format("- **数据处理量**: %,d 行, %.2f MB\n",
                metrics.getTotalRowsProcessed(),
                metrics.getTotalBytesProcessed() / (1024.0 * 1024.0)));
        md.append(String.format("- **差异数量**: %,d\n", metrics.getDifferencesFound()));
        md.append(String.format("- **错误数**: %,d\n\n", metrics.getErrorCount()));

        // Key Performance Indicators
        md.append("## 🎯 关键性能指标\n\n");
        md.append("### 吞吐量\n\n");
        md.append(String.format("- **行吞吐量**: %.2f 行/秒\n", metrics.getThroughputRowsPerSecond()));
        md.append(String.format("- **数据吞吐量**: %.2f MB/秒\n\n", metrics.getThroughputMBPerSecond()));

        // Latency Distribution
        md.append("### 延迟分布\n\n");
        PerformanceMetrics.LatencyDistribution latency = metrics.getLatency();
        md.append("| 指标 | 值 (ms) |\n");
        md.append("|------|--------|\n");
        md.append(String.format("| 最小值 | %,d |\n", latency.getMinMs()));
        md.append(String.format("| 平均值 | %.2f |\n", latency.getAvgMs()));
        md.append(String.format("| P50 (中位数) | %,d |\n", latency.getP50Ms()));
        md.append(String.format("| P95 | %,d |\n", latency.getP95Ms()));
        md.append(String.format("| P99 | %,d |\n", latency.getP99Ms()));
        md.append(String.format("| P99.9 | %,d |\n", latency.getP999Ms()));
        md.append(String.format("| 最大值 | %,d |\n", latency.getMaxMs()));
        md.append(String.format("| 标准差 | %.2f |\n\n", latency.getStdDevMs()));

        // Resource Usage
        md.append("## 💻 资源使用情况\n\n");
        PerformanceMetrics.ResourceMetrics resources = metrics.getResources();

        md.append("### 内存使用\n\n");
        md.append("| 指标 | 值 (MB) |\n");
        md.append("|------|--------|\n");
        md.append(String.format("| 初始堆内存 | %,d |\n", resources.getInitialHeapMB()));
        md.append(String.format("| 平均堆内存 | %,d |\n", resources.getAvgHeapMB()));
        md.append(String.format("| 峰值堆内存 | %,d |\n", resources.getPeakHeapMB()));
        md.append(String.format("| 最终堆内存 | %,d |\n", resources.getFinalHeapMB()));
        md.append(String.format("| 峰值非堆内存 | %,d |\n\n", resources.getPeakNonHeapMB()));

        md.append("### GC 统计\n\n");
        md.append(String.format("- **GC 次数**: %,d\n", resources.getGcCount()));
        md.append(String.format("- **GC 总时间**: %,d ms\n", resources.getGcTimeMs()));
        md.append(String.format("- **平均 GC 时间**: %.2f ms\n", resources.getAvgGcTimeMs()));
        md.append(String.format("- **GC 时间占比**: %.2f%%\n\n",
                (resources.getGcTimeMs() * 100.0) / metrics.getTotalDurationMs()));

        md.append("### CPU 使用\n\n");
        md.append(String.format("- **平均 CPU 使用率**: %.2f%%\n", resources.getAvgCpuUsagePercent()));
        md.append(String.format("- **峰值 CPU 使用率**: %.2f%%\n", resources.getPeakCpuUsagePercent()));
        md.append(String.format("- **平均系统负载**: %.2f\n\n", resources.getAvgSystemLoadAverage()));

        md.append("### 磁盘 I/O\n\n");
        md.append(String.format("- **磁盘读取**: %,d bytes (%.2f MB)\n",
                resources.getDiskReadBytes(), resources.getDiskReadBytes() / (1024.0 * 1024.0)));
        md.append(String.format("- **磁盘写入**: %,d bytes (%.2f MB)\n",
                resources.getDiskWriteBytes(), resources.getDiskWriteBytes() / (1024.0 * 1024.0)));
        md.append(String.format("- **读操作数**: %,d\n", resources.getDiskReadOps()));
        md.append(String.format("- **写操作数**: %,d\n\n", resources.getDiskWriteOps()));

        md.append("### 线程使用\n\n");
        md.append(String.format("- **峰值线程数**: %d\n", resources.getPeakThreadCount()));
        md.append(String.format("- **平均线程数**: %d\n\n", resources.getAvgThreadCount()));

        // Database Metrics
        md.append("## 🗄️ 数据库指标\n\n");
        PerformanceMetrics.DatabaseMetrics database = metrics.getDatabase();

        md.append("### 查询统计\n\n");
        md.append(String.format("- **总查询数**: %,d\n", database.getTotalQueries()));
        md.append(String.format("- **成功查询**: %,d\n", database.getSuccessfulQueries()));
        md.append(String.format("- **失败查询**: %,d\n", database.getFailedQueries()));

        if (database.getTotalQueries() > 0) {
            md.append(String.format("- **成功率**: %.2f%%\n",
                    (database.getSuccessfulQueries() * 100.0) / database.getTotalQueries()));
        }
        md.append("\n");

        md.append("### 查询性能\n\n");
        md.append("| 指标 | 值 (ms) |\n");
        md.append("|------|--------|\n");
        md.append(String.format("| 最小查询时间 | %,d |\n", database.getMinQueryTimeMs()));
        md.append(String.format("| 平均查询时间 | %.2f |\n", database.getAvgQueryTimeMs()));
        md.append(String.format("| 最大查询时间 | %,d |\n\n", database.getMaxQueryTimeMs()));

        md.append("### 数据传输\n\n");
        md.append(String.format("- **获取行数**: %,d\n", database.getTotalRowsFetched()));
        md.append(String.format("- **传输数据量**: %,d bytes (%.2f MB)\n\n",
                database.getTotalBytesTransferred(),
                database.getTotalBytesTransferred() / (1024.0 * 1024.0)));

        // Concurrency Metrics
        md.append("## ⚡ 并发指标\n\n");
        PerformanceMetrics.ConcurrencyMetrics concurrency = metrics.getConcurrency();

        md.append("### 线程池配置\n\n");
        md.append(String.format("- **核心线程数**: %d\n", concurrency.getCorePoolSize()));
        md.append(String.format("- **最大线程数**: %d\n", concurrency.getMaxPoolSize()));
        md.append(String.format("- **平均活跃线程**: %d\n", concurrency.getAvgActiveThreads()));
        md.append(String.format("- **峰值活跃线程**: %d\n\n", concurrency.getPeakActiveThreads()));

        md.append("### 任务统计\n\n");
        md.append(String.format("- **提交任务数**: %,d\n", concurrency.getTotalTasksSubmitted()));
        md.append(String.format("- **完成任务数**: %,d\n", concurrency.getTotalTasksCompleted()));
        md.append(String.format("- **拒绝任务数**: %,d\n", concurrency.getTotalTasksRejected()));
        md.append(String.format("- **失败任务数**: %,d\n\n", concurrency.getTotalTasksFailed()));

        md.append("### 任务性能\n\n");
        md.append(String.format("- **平均任务执行时间**: %.2f ms\n", concurrency.getAvgTaskExecutionTimeMs()));
        md.append(String.format("- **最大任务执行时间**: %,d ms\n", concurrency.getMaxTaskExecutionTimeMs()));
        md.append(String.format("- **平均任务等待时间**: %.2f ms\n", concurrency.getAvgTaskWaitTimeMs()));
        md.append(String.format("- **最大任务等待时间**: %,d ms\n\n", concurrency.getMaxTaskWaitTimeMs()));

        // Test Configuration
        md.append("## ⚙️ 测试配置\n\n");
        md.append(String.format("- **预热迭代**: %d\n", config.getWarmupIterations()));
        md.append(String.format("- **测试迭代**: %d\n", config.getTestIterations()));
        md.append(String.format("- **并发级别**: %d\n", config.getConcurrencyLevel()));
        md.append(String.format("- **负载模式**: %s\n", config.getLoadPattern()));
        md.append(String.format("- **监控间隔**: %d ms\n\n", config.getMonitoringIntervalMs()));

        // Performance Analysis
        md.append("## 📈 性能分析\n\n");
        md.append(generatePerformanceAnalysis(metrics));

        // Recommendations
        md.append("## 💡 优化建议\n\n");
        md.append(generateRecommendations(metrics));

        return md.toString();
    }

    private void createParentDirectories(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private void validateReportInput(PerformanceTestResult result) {
        if (result == null) {
            throw new IllegalArgumentException("Performance test result cannot be null");
        }
        if (result.getConfig() == null) {
            throw new IllegalArgumentException("Performance test result config cannot be null");
        }
        if (result.isSuccess() && result.getMetrics() == null) {
            throw new IllegalArgumentException("Successful performance test result must include metrics");
        }
    }

    /**
     * Generate performance analysis.
     */
    private String generatePerformanceAnalysis(PerformanceMetrics metrics) {
        StringBuilder analysis = new StringBuilder();

        PerformanceMetrics.ResourceMetrics resources = metrics.getResources();
        PerformanceMetrics.LatencyDistribution latency = metrics.getLatency();

        // Throughput analysis
        analysis.append("### 吞吐量分析\n\n");
        double throughput = metrics.getThroughputRowsPerSecond();
        if (throughput > 10000) {
            analysis.append("✅ **优秀**: 吞吐量超过 10,000 行/秒,系统性能表现优异。\n\n");
        } else if (throughput > 1000) {
            analysis.append("✓ **良好**: 吞吐量在 1,000-10,000 行/秒之间,性能表现良好。\n\n");
        } else {
            analysis.append("⚠️ **需要优化**: 吞吐量低于 1,000 行/秒,建议进行性能优化。\n\n");
        }

        // Latency analysis
        analysis.append("### 延迟分析\n\n");
        if (latency.getP99Ms() < 100) {
            analysis.append("✅ **优秀**: P99 延迟低于 100ms,响应时间表现优异。\n\n");
        } else if (latency.getP99Ms() < 500) {
            analysis.append("✓ **良好**: P99 延迟在 100-500ms 之间,响应时间可接受。\n\n");
        } else {
            analysis.append("⚠️ **需要优化**: P99 延迟超过 500ms,建议优化响应时间。\n\n");
        }

        // Memory analysis
        analysis.append("### 内存使用分析\n\n");
        double memoryGrowth = (resources.getFinalHeapMB() - resources.getInitialHeapMB()) * 100.0 /
                resources.getInitialHeapMB();

        if (memoryGrowth < 20) {
            analysis.append("✅ **稳定**: 内存增长低于 20%,内存使用稳定。\n\n");
        } else if (memoryGrowth < 50) {
            analysis.append("✓ **正常**: 内存增长在 20-50% 之间,属于正常范围。\n\n");
        } else {
            analysis.append("⚠️ **警告**: 内存增长超过 50%,可能存在内存泄漏风险。\n\n");
        }

        // GC analysis
        analysis.append("### GC 影响分析\n\n");
        double gcImpact = (resources.getGcTimeMs() * 100.0) / metrics.getTotalDurationMs();

        if (gcImpact < 5) {
            analysis.append("✅ **优秀**: GC 时间占比低于 5%,GC 影响很小。\n\n");
        } else if (gcImpact < 10) {
            analysis.append("✓ **良好**: GC 时间占比在 5-10% 之间,GC 影响可接受。\n\n");
        } else {
            analysis.append("⚠️ **需要优化**: GC 时间占比超过 10%,建议优化 GC 配置或减少对象创建。\n\n");
        }

        return analysis.toString();
    }

    /**
     * Generate optimization recommendations.
     */
    private String generateRecommendations(PerformanceMetrics metrics) {
        StringBuilder recommendations = new StringBuilder();

        PerformanceMetrics.ResourceMetrics resources = metrics.getResources();
        PerformanceMetrics.DatabaseMetrics database = metrics.getDatabase();
        PerformanceMetrics.ConcurrencyMetrics concurrency = metrics.getConcurrency();

        // Memory recommendations
        if (resources.getPeakHeapMB() > resources.getInitialHeapMB() * 0.9) {
            recommendations.append("1. **增加堆内存**: 峰值内存接近初始堆大小,建议增加 JVM 堆内存配置。\n");
            recommendations.append(String.format("   - 建议配置: `-Xmx%dM -Xms%dM`\n\n",
                    resources.getPeakHeapMB() * 2, resources.getPeakHeapMB()));
        }

        // GC recommendations
        double gcImpact = (resources.getGcTimeMs() * 100.0) / metrics.getTotalDurationMs();
        if (gcImpact > 10) {
            recommendations.append("2. **优化 GC 配置**: GC 时间占比较高,建议:\n");
            recommendations.append("   - 使用 G1GC: `-XX:+UseG1GC`\n");
            recommendations.append("   - 调整 GC 线程数: `-XX:ParallelGCThreads=4`\n");
            recommendations.append("   - 设置最大 GC 暂停时间: `-XX:MaxGCPauseMillis=200`\n\n");
        }

        // Database recommendations
        if (database.getAvgQueryTimeMs() > 100) {
            recommendations.append("3. **优化数据库查询**: 平均查询时间较长,建议:\n");
            recommendations.append("   - 添加适当的索引\n");
            recommendations.append("   - 优化 SQL 查询语句\n");
            recommendations.append("   - 考虑使用查询缓存\n\n");
        }

        // Concurrency recommendations
        if (concurrency.getTotalTasksRejected() > 0) {
            recommendations.append("4. **调整线程池配置**: 存在任务被拒绝,建议:\n");
            recommendations.append(String.format("   - 增加核心线程数至: %d\n", concurrency.getCorePoolSize() * 2));
            recommendations.append("   - 增加任务队列容量\n\n");
        }

        // CPU recommendations
        if (resources.getAvgCpuUsagePercent() > 80) {
            recommendations.append("5. **CPU 使用率高**: 平均 CPU 使用率超过 80%,建议:\n");
            recommendations.append("   - 优化算法复杂度\n");
            recommendations.append("   - 减少不必要的计算\n");
            recommendations.append("   - 考虑水平扩展\n\n");
        }

        if (recommendations.length() == 0) {
            recommendations.append("✅ 系统性能表现良好,暂无优化建议。\n\n");
        }

        return recommendations.toString();
    }

    /**
     * Build HTML report content.
     */
    private String buildHtmlReport(PerformanceTestResult result) {
        validateReportInput(result);
        StringBuilder html = new StringBuilder();

        PerformanceMetrics metrics = result.getMetrics();
        PerformanceTestConfig config = result.getConfig();

        // HTML header
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("    <title>性能测试报告 - ").append(config.getTestName()).append("</title>\n");
        html.append("    <style>\n");
        html.append(getHtmlStyles());
        html.append("    </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");

        // Header
        html.append("    <div class=\"container\">\n");
        html.append("        <header>\n");
        html.append("            <h1>性能测试报告</h1>\n");
        html.append("            <p class=\"subtitle\">").append(config.getTestName()).append("</p>\n");
        html.append("            <p class=\"timestamp\">生成时间: ")
                .append(java.time.LocalDateTime.now().format(DATE_FORMATTER))
                .append("</p>\n");
        html.append("        </header>\n\n");

        if (!result.isSuccess()) {
            html.append("        <div class=\"alert alert-error\">\n");
            html.append("            <h2>❌ 测试失败</h2>\n");
            html.append("            <p>").append(result.getErrorMessage()).append("</p>\n");
            html.append("        </div>\n");
            html.append("    </div>\n");
            html.append("</body>\n</html>");
            return html.toString();
        }

        // Executive Summary
        html.append("        <section class=\"summary\">\n");
        html.append("            <h2>📊 执行摘要</h2>\n");
        html.append("            <div class=\"metrics-grid\">\n");
        html.append(String.format("                <div class=\"metric-card success\">\n"));
        html.append("                    <div class=\"metric-label\">测试状态</div>\n");
        html.append("                    <div class=\"metric-value\">✅ 成功</div>\n");
        html.append("                </div>\n");

        html.append("                <div class=\"metric-card\">\n");
        html.append("                    <div class=\"metric-label\">测试时长</div>\n");
        html.append(String.format("                    <div class=\"metric-value\">%,d ms</div>\n",
                metrics.getTotalDurationMs()));
        html.append(String.format("                    <div class=\"metric-detail\">%.2f 秒</div>\n",
                metrics.getTotalDurationMs() / 1000.0));
        html.append("                </div>\n");

        html.append("                <div class=\"metric-card\">\n");
        html.append("                    <div class=\"metric-label\">数据处理量</div>\n");
        html.append(String.format("                    <div class=\"metric-value\">%,d 行</div>\n",
                metrics.getTotalRowsProcessed()));
        html.append(String.format("                    <div class=\"metric-detail\">%.2f MB</div>\n",
                metrics.getTotalBytesProcessed() / (1024.0 * 1024.0)));
        html.append("                </div>\n");

        html.append("                <div class=\"metric-card\">\n");
        html.append("                    <div class=\"metric-label\">差异数量</div>\n");
        html.append(String.format("                    <div class=\"metric-value\">%,d</div>\n",
                metrics.getDifferencesFound()));
        html.append("                </div>\n");
        html.append("            </div>\n");
        html.append("        </section>\n\n");

        // Key Performance Indicators
        html.append("        <section>\n");
        html.append("            <h2>🎯 关键性能指标</h2>\n");
        html.append("            <div class=\"metrics-grid\">\n");

        html.append("                <div class=\"metric-card highlight\">\n");
        html.append("                    <div class=\"metric-label\">行吞吐量</div>\n");
        html.append(String.format("                    <div class=\"metric-value\">%.2f</div>\n",
                metrics.getThroughputRowsPerSecond()));
        html.append("                    <div class=\"metric-detail\">行/秒</div>\n");
        html.append("                </div>\n");

        html.append("                <div class=\"metric-card highlight\">\n");
        html.append("                    <div class=\"metric-label\">数据吞吐量</div>\n");
        html.append(String.format("                    <div class=\"metric-value\">%.2f</div>\n",
                metrics.getThroughputMBPerSecond()));
        html.append("                    <div class=\"metric-detail\">MB/秒</div>\n");
        html.append("                </div>\n");

        PerformanceMetrics.LatencyDistribution latency = metrics.getLatency();
        html.append("                <div class=\"metric-card\">\n");
        html.append("                    <div class=\"metric-label\">平均延迟</div>\n");
        html.append(String.format("                    <div class=\"metric-value\">%.2f ms</div>\n",
                latency.getAvgMs()));
        html.append("                </div>\n");

        html.append("                <div class=\"metric-card\">\n");
        html.append("                    <div class=\"metric-label\">P99 延迟</div>\n");
        html.append(String.format("                    <div class=\"metric-value\">%,d ms</div>\n",
                latency.getP99Ms()));
        html.append("                </div>\n");

        html.append("            </div>\n");
        html.append("        </section>\n\n");

        // Latency Distribution Table
        html.append("        <section>\n");
        html.append("            <h3>延迟分布</h3>\n");
        html.append("            <table>\n");
        html.append("                <tr><th>指标</th><th>值 (ms)</th></tr>\n");
        html.append(String.format("                <tr><td>最小值</td><td>%,d</td></tr>\n", latency.getMinMs()));
        html.append(String.format("                <tr><td>平均值</td><td>%.2f</td></tr>\n", latency.getAvgMs()));
        html.append(String.format("                <tr><td>P50 (中位数)</td><td>%,d</td></tr>\n", latency.getP50Ms()));
        html.append(String.format("                <tr><td>P95</td><td>%,d</td></tr>\n", latency.getP95Ms()));
        html.append(String.format("                <tr><td>P99</td><td>%,d</td></tr>\n", latency.getP99Ms()));
        html.append(String.format("                <tr><td>P99.9</td><td>%,d</td></tr>\n", latency.getP999Ms()));
        html.append(String.format("                <tr><td>最大值</td><td>%,d</td></tr>\n", latency.getMaxMs()));
        html.append(String.format("                <tr><td>标准差</td><td>%.2f</td></tr>\n", latency.getStdDevMs()));
        html.append("            </table>\n");
        html.append("        </section>\n\n");

        // Resource Usage
        PerformanceMetrics.ResourceMetrics resources = metrics.getResources();
        html.append("        <section>\n");
        html.append("            <h2>💻 资源使用情况</h2>\n");
        html.append("            <div class=\"metrics-grid\">\n");

        html.append("                <div class=\"metric-card\">\n");
        html.append("                    <div class=\"metric-label\">峰值堆内存</div>\n");
        html.append(String.format("                    <div class=\"metric-value\">%,d MB</div>\n",
                resources.getPeakHeapMB()));
        html.append("                </div>\n");

        html.append("                <div class=\"metric-card\">\n");
        html.append("                    <div class=\"metric-label\">GC 次数</div>\n");
        html.append(String.format("                    <div class=\"metric-value\">%,d</div>\n",
                resources.getGcCount()));
        html.append(String.format("                    <div class=\"metric-detail\">总时间: %,d ms</div>\n",
                resources.getGcTimeMs()));
        html.append("                </div>\n");

        html.append("                <div class=\"metric-card\">\n");
        html.append("                    <div class=\"metric-label\">平均 CPU</div>\n");
        html.append(String.format("                    <div class=\"metric-value\">%.2f%%</div>\n",
                resources.getAvgCpuUsagePercent()));
        html.append("                </div>\n");

        html.append("                <div class=\"metric-card\">\n");
        html.append("                    <div class=\"metric-label\">峰值线程数</div>\n");
        html.append(String.format("                    <div class=\"metric-value\">%d</div>\n",
                resources.getPeakThreadCount()));
        html.append("                </div>\n");

        html.append("            </div>\n");
        html.append("        </section>\n\n");

        // Footer
        html.append("    </div>\n");
        html.append("</body>\n");
        html.append("</html>");

        return html.toString();
    }

    /**
     * Get HTML styles.
     */
    private String getHtmlStyles() {
        StringBuilder css = new StringBuilder();
        css.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
        css.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; ");
        css.append("line-height: 1.6; color: #333; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 20px; }\n");
        css.append(".container { max-width: 1200px; margin: 0 auto; background: white; border-radius: 12px; ");
        css.append("box-shadow: 0 10px 40px rgba(0,0,0,0.2); overflow: hidden; }\n");
        css.append("header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; ");
        css.append("padding: 40px; text-align: center; }\n");
        css.append("header h1 { font-size: 2.5em; margin-bottom: 10px; }\n");
        css.append(".subtitle { font-size: 1.2em; opacity: 0.9; }\n");
        css.append(".timestamp { margin-top: 10px; opacity: 0.8; font-size: 0.9em; }\n");
        css.append("section { padding: 30px 40px; border-bottom: 1px solid #eee; }\n");
        css.append("section:last-child { border-bottom: none; }\n");
        css.append("h2 { color: #667eea; margin-bottom: 20px; font-size: 1.8em; }\n");
        css.append("h3 { color: #555; margin: 20px 0 15px 0; font-size: 1.3em; }\n");
        css.append(".metrics-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); ");
        css.append("gap: 20px; margin-top: 20px; }\n");
        css.append(".metric-card { background: #f8f9fa; padding: 20px; border-radius: 8px; ");
        css.append("border-left: 4px solid #667eea; transition: transform 0.2s, box-shadow 0.2s; }\n");
        css.append(".metric-card:hover { transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.1); }\n");
        css.append(".metric-card.highlight { background: linear-gradient(135deg, #667eea15 0%, #764ba215 100%); ");
        css.append("border-left-color: #764ba2; }\n");
        css.append(".metric-card.success { background: #d4edda; border-left-color: #28a745; }\n");
        css.append(".metric-label { font-size: 0.9em; color: #666; margin-bottom: 8px; ");
        css.append("text-transform: uppercase; letter-spacing: 0.5px; }\n");
        css.append(".metric-value { font-size: 2em; font-weight: bold; color: #333; }\n");
        css.append(".metric-detail { font-size: 0.9em; color: #888; margin-top: 5px; }\n");
        css.append("table { width: 100%; border-collapse: collapse; margin-top: 15px; background: white; ");
        css.append("box-shadow: 0 2px 8px rgba(0,0,0,0.05); border-radius: 8px; overflow: hidden; }\n");
        css.append("th { background: #667eea; color: white; padding: 12px; text-align: left; font-weight: 600; }\n");
        css.append("td { padding: 12px; border-bottom: 1px solid #eee; }\n");
        css.append("tr:last-child td { border-bottom: none; }\n");
        css.append("tr:hover { background: #f8f9fa; }\n");
        css.append(".alert { padding: 20px; border-radius: 8px; margin: 20px 0; }\n");
        css.append(".alert-error { background: #f8d7da; border-left: 4px solid #dc3545; color: #721c24; }\n");
        css.append("@media (max-width: 768px) { .metrics-grid { grid-template-columns: 1fr; } ");
        css.append("header h1 { font-size: 1.8em; } }\n");
        return css.toString();
    }
}
