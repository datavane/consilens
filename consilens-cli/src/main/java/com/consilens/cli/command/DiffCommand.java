package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CliDiffResult;
import com.consilens.cli.service.DiffService;
import com.consilens.cli.service.DuckDbCompareRuntime;
import com.consilens.cli.service.SensitiveValueMasker;
import com.consilens.cli.service.WindowCheckpoint;
import com.consilens.cli.service.WindowSpec;
import com.consilens.core.diff.DiffResult;

import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Comparator;

/**
 * Picocli subcommand for performing data comparison (diff) operations.
 */
@Slf4j
@Command(
    name = "diff",
    description = "Perform data comparison between source and target databases",
    mixinStandardHelpOptions = true
)
public class DiffCommand implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file path")
    private String configFile;

    @Option(names = {"--dry-run"}, description = "Perform a dry run without actual comparison")
    private boolean dryRun;

    @Option(names = {"--verbose"}, description = "Enable verbose logging")
    private boolean verbose;

    @Option(names = {"--benchmark-metrics"}, hidden = true,
            description = "Emit machine-readable benchmark metrics")
    private boolean benchmarkMetrics;

    @Option(names = {"--fail-on-diff"},
            // 注意：这里的 2 与 verify 的退出码表不是一套（verify 里 2 = 执行/配置错误）。
            // CI 门禁请用 verify（差异=1、无法判定=3、预算=4）；此选项只做"有差异就非 0"的粗判断。
            description = "Exit with status 2 when any difference is found "
                    + "(diff-local code: 2 = differences found; for policy gates use verify)")
    private boolean failOnDiff;

    @Option(names = {"--engine"}, defaultValue = "java",
            description = "Comparison engine: java (default) | duckdb (local files only)")
    private String engine;

    @Option(names = {"--window-column"},
            description = "Incremental window column used for both sides (JDBC only)")
    private String windowColumn;

    @Option(names = {"--source-window-column"},
            description = "Source window column when it differs from --window-column")
    private String sourceWindowColumn;

    @Option(names = {"--target-window-column"},
            description = "Target window column when it differs from --window-column")
    private String targetWindowColumn;

    @Option(names = {"--window-from"},
            description = "Inclusive window lower bound (SQL literal value)")
    private String windowFrom;

    @Option(names = {"--window-to"},
            description = "Exclusive window upper bound (SQL literal value)")
    private String windowTo;

    @Option(names = {"--checkpoint-file"},
            description = "Watermark file; reads the previous --window-to and advances it after success")
    private String checkpointFile;

    @Override
    public Integer call() {
        try {
            ConfigurationManager configurationManager = new ConfigurationManager();
            DiffService diffService = diffService(engine);

            CliConfiguration config = configurationManager.loadConfiguration(configFile);

            if (verbose) {
                log.info("Starting diff operation with configuration:");
                log.info("  Strategy: {}", config.getStrategyMode());
                log.info("  Algorithm: {}", config.getAlgorithm());
                log.info("  Source: {}", SensitiveValueMasker.maskJdbcUrl(config.getSource().getUrl()));
                log.info("  Source Resource: {}", resourceDisplay(config.getSource()));
                log.info("  Target: {}", SensitiveValueMasker.maskJdbcUrl(config.getTarget().getUrl()));
                log.info("  Target Resource: {}", resourceDisplay(config.getTarget()));
                log.info("  Source Key Columns: {}", config.getComparison().getKeys().getSource());
                log.info("  Target Key Columns: {}", config.getComparison().getKeys().getTarget());
                if (config.getComparison().getFields() != null
                        && config.getComparison().getFields().getSource() != null
                        && !config.getComparison().getFields().getSource().isEmpty()) {
                    log.info("  Source Fields: {}", config.getComparison().getFields().getSource());
                }
                if (config.getComparison().getFields() != null
                        && config.getComparison().getFields().getTarget() != null
                        && !config.getComparison().getFields().getTarget().isEmpty()) {
                    log.info("  Target Fields: {}", config.getComparison().getFields().getTarget());
                }
                log.info("  Concurrency: {}", config.getConcurrency());
                log.info("  Batch Size: {}", config.getStrategy().getBatchSize());
            }

            WindowSpec window = buildWindow();
            Path checkpointPath = WindowSpec.blank(checkpointFile) ? null : Paths.get(checkpointFile);
            if (checkpointPath != null) {
                if (window == null) {
                    throw new IllegalArgumentException("--checkpoint-file requires --window-column"
                            + " and --window-to");
                }
                if (WindowSpec.blank(window.getTo())) {
                    throw new IllegalArgumentException("--checkpoint-file requires --window-to");
                }
                String previous = WindowCheckpoint.read(checkpointPath);
                if (previous != null) {
                    if (!WindowSpec.blank(windowFrom)) {
                        // 显式 --window-from 优先，checkpoint 只在缺省时补位
                        log.warn("Ignoring checkpoint watermark {} because --window-from {} was given",
                                previous, window.getFrom());
                    } else {
                        log.info("Using checkpoint watermark {} from {}", previous, checkpointPath);
                        window.setFrom(previous);
                    }
                    if (window.isAlreadyCovered()) {
                        // watermark 已越过 --window-to：本次窗口里没有任何行。
                        // 必须显式跳过并提示，不能让"空窗口"看起来像"两侧完全一致"。
                        log.warn("Incremental window is already covered by the checkpoint"
                                + " (from {} >= to {}); nothing to compare",
                                window.getFrom(), window.getTo());
                        log.info("Clear {} or raise --window-to to compare new data",
                                checkpointPath);
                        return 0;
                    }
                }
            }

            CliDiffResult result;
            if (dryRun) {
                log.info("Performing dry run (validation only)...");
                result = diffService.performDryRun(config);
            } else {
                log.info("Starting diff operation...");
                log.info("This may take a while depending on table sizes and strategy chosen.");

                long startTime = System.currentTimeMillis();
                result = diffService.performDiff(config, window);
                long totalTime = System.currentTimeMillis() - startTime;

                log.info("Diff operation completed in {} ms", totalTime);
            }

            if (!dryRun && checkpointPath != null) {
                WindowCheckpoint.write(checkpointPath, window.getTo());
                log.info("Advanced checkpoint {} to {}", checkpointPath, window.getTo());
            }

            displayDiffResults(result, dryRun);
            if (benchmarkMetrics && !dryRun) {
                displayBenchmarkMetrics(result);
            }
            int exitCode = resolveExitCode(failOnDiff, dryRun, result.getTotalDifferences());
            if (exitCode == 2) {
                log.info("Differences found and --fail-on-diff is set; exiting with status 2");
            }
            return exitCode;
        } catch (Exception e) {
            log.error("Diff operation failed", e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * P2-13：CI 依赖的退出码契约，单独抽出以便单测（无需真实数据库即可验证）。
     * 差异存在且显式开启 --fail-on-diff 时返回 2；dry-run 永远返回 0（没有真正比对）。
     */
    static int resolveExitCode(boolean failOnDiff, boolean dryRun, long totalDifferences) {
        return failOnDiff && !dryRun && totalDifferences > 0 ? 2 : 0;
    }

    private void displayBenchmarkMetrics(CliDiffResult result) {
        benchmarkMetric("totalDifferences", result.getTotalDifferences());
        benchmarkMetric("sourceMissingRows", result.getSourceMissingCount());
        benchmarkMetric("targetMissingRows", result.getTargetMissingCount());
        benchmarkMetric("mismatchedRows", result.getMismatchCount());
        benchmarkMetric("heapPeakBytes", heapPeakBytes());

        if (result.getInfoTree() == null) {
            return;
        }
        DiffResult.InfoTree tree = result.getInfoTree();
        benchmarkMetric("segmentCount", tree.getTotalSegments());
        benchmarkMetric("maxDepth", tree.getMaxDepth());
        benchmarkMetric("logicalRowsScanned", tree.getTotalRowsScanned());
        benchmarkMetric("instrumentedQueryCount", tree.getNodes().stream()
                .mapToLong(DiffResult.InfoTreeNode::getQueryCount).sum());
        benchmarkMetric("rowsFetched", tree.getNodes().stream()
                .mapToLong(DiffResult.InfoTreeNode::getRowsFetched).sum());
        benchmarkMetric("resultBytesFetchedEstimate", tree.getNodes().stream()
                .mapToLong(DiffResult.InfoTreeNode::getBytesFetched).sum());
        Object firstDifferenceAt = tree.getMetrics() == null ? null : tree.getMetrics().get("firstDifferenceAt");
        if (firstDifferenceAt instanceof Number) {
            benchmarkMetric("firstDifferenceObservedMs",
                    Math.max(0L, ((Number) firstDifferenceAt).longValue() - tree.getStartTime()));
            return;
        }
        tree.getNodes().stream()
                .filter(node -> node.getDiffCount() > 0 && node.getEndedAt() > 0)
                .min(Comparator.comparingLong(DiffResult.InfoTreeNode::getEndedAt))
                .ifPresent(node -> benchmarkMetric("firstDifferenceObservedMs",
                        Math.max(0L, node.getEndedAt() - tree.getStartTime())));
    }

    private void benchmarkMetric(String name, long value) {
        System.out.println("BENCHMARK_METRIC " + name + "=" + value);
    }

    private long heapPeakBytes() {
        long peak = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                peak += Math.max(0L, pool.getPeakUsage().getUsed());
            }
        }
        return peak;
    }

    static DiffService diffService(String engine) {
        String normalized = engine == null || engine.isBlank() ? "java" : engine.trim();
        if ("java".equalsIgnoreCase(normalized)) {
            return new DiffService();
        }
        if ("duckdb".equalsIgnoreCase(normalized)) {
            return new DiffService(new DuckDbCompareRuntime());
        }
        throw new IllegalArgumentException("Unsupported diff engine: " + engine
                + " (supported: java, duckdb)");
    }

    private WindowSpec buildWindow() {
        return VerifyCommand.buildWindow(windowColumn, sourceWindowColumn, targetWindowColumn, windowFrom, windowTo);
    }

    private void displayDiffResults(CliDiffResult result, boolean isDryRun) {
        log.info("Diff Results" + (isDryRun ? " (Dry Run)" : "") + ":");
        log.info("=".repeat(50));
        log.info("Strategy: " + result.getStrategy());
        log.info("Source Row Count: " + result.getSourceRowCount());
        log.info("Target Row Count: " + result.getTargetRowCount());

        if (isDryRun) {
            log.info("Dry run completed successfully!");
            log.info("Database connections validated and table counts retrieved.");
            log.info("Run without --dry-run to perform actual diff operation.");
        } else {
            log.info("Differences Found:");
            log.info("  Source missing rows: " + result.getSourceMissingCount());
            log.info("  Target missing rows: " + result.getTargetMissingCount());
            log.info("  Mismatched rows: " + result.getMismatchCount());
            log.info("  Total differences: " + result.getTotalDifferences());
            if (result.getWithinToleranceCount() > 0) {
                log.info("  Within tolerance (excluded from total): " + result.getWithinToleranceCount());
            }

            if (result.getTotalDifferences() == 0) {
                log.info("No differences found! The tables are identical.");
            } else {
                log.info("Differences detected. Check the configured result sinks for details.");
            }

            if (result.getDurationMs() != null) {
                log.info("Operation duration: " + result.getDurationMs() + " ms");
            }
        }
    }

    private String resourceDisplay(com.consilens.cli.model.ConnectionConfig connectionConfig) {
        if (connectionConfig == null || connectionConfig.getResource() == null) {
            return "(not set)";
        }
        com.consilens.cli.model.ConnectionConfig.ResourceConfig resource = connectionConfig.getResource();
        String location = "sql".equalsIgnoreCase(resource.getType())
                ? resource.getPath()
                : resource.getName();
        return resource.getType() + ":" + location;
    }
}
