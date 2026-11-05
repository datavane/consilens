package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.cli.service.DuckDbCompareRuntime;
import com.consilens.cli.service.WindowCheckpoint;
import com.consilens.cli.service.WindowSpec;
import com.consilens.cli.verify.VerifyReportWriter;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.sdk.ConsilensEngine;
import com.consilens.sdk.DefaultConsilensEngine;
import com.consilens.sdk.RunOptions;
import com.consilens.sdk.verify.FailOn;
import com.consilens.core.exception.BudgetExceededException;
import com.consilens.sdk.verify.Verdict;
import com.consilens.sdk.verify.VerifyPolicy;
import com.consilens.sdk.verify.VerifyResult;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * CI gate subcommand: run a comparison and evaluate a pass/fail policy.
 * Exit codes: 0 PASS, 1 FAIL, 2 ERROR, 3 INCONCLUSIVE, 4 BUDGET_EXCEEDED.
 */
@Slf4j
@Command(
    name = "verify",
    description = "Run a comparison and evaluate a pass/fail policy for CI gates",
    mixinStandardHelpOptions = true
)
public class VerifyCommand implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file path")
    private String configFile;

    @Option(names = {"--engine"}, defaultValue = "java",
            description = "Comparison engine: java (default) | duckdb (local files only)")
    private String engineName;

    @Option(names = "--fail-on", defaultValue = "any",
            description = "any|new|none: what turns differences into a FAIL (default any)")
    private String failOn;

    @Option(names = "--max-mismatch-rate",
            description = "Mismatch rows / total rows (0..1); fails when exceeded")
    private Double maxMismatchRate;

    @Option(names = "--max-row-count-delta-pct",
            description = "|sourceRows-targetRows| / max(rows) * 100; fails when exceeded")
    private Double maxRowCountDeltaPct;

    @Option(names = "--max-new-differences",
            description = "Max differences not present in the baseline (default 0); requires --baseline with --fail-on new")
    private Long maxNewDifferences;

    @Option(names = "--baseline",
            description = "JSONL diff-record file used as the verification baseline")
    private String baselineFile;

    @Option(names = "--required-columns", split = ",",
            description = "Comma separated columns that must participate in the comparison")
    private List<String> requiredColumns;

    @Option(names = "--format", split = ",", defaultValue = "json",
            description = "Report formats: json,junit,sarif,markdown (comma separated)")
    private List<String> formats;

    @Option(names = "--output-dir", defaultValue = "./consilens-report",
            description = "Directory for generated reports")
    private String outputDir;

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
            description = "Watermark file; reads the previous --window-to and advances it only on PASS")
    private String checkpointFile;

    private final ConsilensEngine engine;

    public VerifyCommand() {
        this(new DefaultConsilensEngine());
    }

    VerifyCommand(ConsilensEngine engine) {
        this.engine = engine;
    }

    @Override
    public Integer call() {
        try {
            CliConfiguration configuration = new ConfigurationManager().loadConfiguration(configFile);
            WindowSpec window = buildWindow(windowColumn, sourceWindowColumn, targetWindowColumn,
                    windowFrom, windowTo);
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
                        // 显式给了 --window-from 就以命令行为准，checkpoint 只在缺省时补位，
                        // 否则用户想重跑某个历史区间会被静默改回水位
                        log.warn("Ignoring checkpoint watermark {} because --window-from {} was given",
                                previous, window.getFrom());
                    } else {
                        log.info("Using checkpoint watermark {} from {}", previous, checkpointPath);
                        window.setFrom(previous);
                    }
                    if (window.isAlreadyCovered()) {
                        log.warn("Incremental window is already covered by the checkpoint"
                                + " (from {} >= to {}); nothing to verify",
                                window.getFrom(), window.getTo());
                        log.info("Clear {} or raise --window-to to verify new data", checkpointPath);
                        return 0;
                    }
                }
            }
            CompareRequest request = new CompareRequestFactory().create(configuration, window);
            VerifyPolicy policy = buildPolicy(failOn, maxMismatchRate, maxRowCountDeltaPct,
                    maxNewDifferences, baselineFile, requiredColumns);

            VerifyResult result;
            try (ConsilensEngine used = engineFor(engineName, engine)) {
                result = used.verify(request, policy, RunOptions.defaults());
            }
            Path reportDir = Paths.get(outputDir == null || outputDir.isBlank()
                    ? "./consilens-report" : outputDir);
            List<Path> written = VerifyReportWriter.write(
                    result,
                    formats == null || formats.isEmpty() ? List.of("json") : formats,
                    reportDir);

            // 必须先落盘报告再推进 watermark：反过来的话，写报告失败（退出码 2）
            // 会留下已推进的水位，下次运行直接跳过这段窗口，差异被永久漏掉。
            if (checkpointPath != null && result.getVerdict() == Verdict.PASS) {
                WindowCheckpoint.write(checkpointPath, window.getTo());
                log.info("Advanced checkpoint {} to {}", checkpointPath, window.getTo());
            }

            log.info("verify verdict={} exitCode={} reports={}", result.getVerdict(),
                    result.getExitCode(), written);
            System.out.println("consilens verify: " + result.getVerdict()
                    + " (exitCode=" + result.getExitCode() + ")");
            if (result.getMessage() != null) {
                System.out.println(result.getMessage());
            }
            return result.getExitCode();
        } catch (Exception exception) {
            // 预算超限是"结论不完整"，不是执行失败：CI 需要能把它和真正的错误区分开
            // （引擎可能把它包在 RuntimeException 里，所以沿 cause 链找）。
            BudgetExceededException budget = findBudget(exception);
            if (budget != null) {
                log.warn("verify stopped by budget: {}", budget.getMessage());
                System.err.println("verify incomplete: " + budget.getMessage());
                return exitCodeOf(Verdict.BUDGET_EXCEEDED);
            }
            log.error("verify failed", exception);
            System.err.println("verify failed: " + exception.getMessage());
            return 2;
        }
    }

    static ConsilensEngine engineFor(String name, ConsilensEngine defaultEngine) {
        String normalized = name == null || name.isBlank() ? "java" : name.trim();
        if ("java".equalsIgnoreCase(normalized)) {
            return defaultEngine;
        }
        if ("duckdb".equalsIgnoreCase(normalized)) {
            return new DefaultConsilensEngine(new DuckDbCompareRuntime());
        }
        throw new IllegalArgumentException("Unsupported verify engine: " + name
                + " (supported: java, duckdb)");
    }

    static WindowSpec buildWindow(String column, String sourceColumn, String targetColumn,
                                  String from, String to) {
        if (WindowSpec.blank(column) && WindowSpec.blank(sourceColumn)
                && WindowSpec.blank(targetColumn) && WindowSpec.blank(from)
                && WindowSpec.blank(to)) {
            return null;
        }
        WindowSpec window = WindowSpec.builder()
                .column(column)
                .sourceColumn(sourceColumn)
                .targetColumn(targetColumn)
                .from(from)
                .to(to)
                .build();
        window.validate();
        return window;
    }

    static VerifyPolicy buildPolicy(String failOnValue,
                                    Double maxMismatchRate,
                                    Double maxRowCountDeltaPct,
                                    Long maxNewDifferences,
                                    String baselineFile,
                                    List<String> requiredColumns) {
        FailOn failOn = parseFailOn(failOnValue);
        if (failOn == FailOn.NEW && (baselineFile == null || baselineFile.isBlank())) {
            throw new IllegalArgumentException("--fail-on new requires --baseline <jsonl-file>");
        }
        return VerifyPolicy.builder()
                .failOn(failOn)
                .maxMismatchRate(maxMismatchRate)
                .maxRowCountDeltaPct(maxRowCountDeltaPct)
                .maxNewDifferences(maxNewDifferences)
                .baselineFile(baselineFile == null || baselineFile.isBlank()
                        ? null : Paths.get(baselineFile))
                .requiredColumns(requiredColumns == null ? List.of() : requiredColumns)
                .build();
    }

    static FailOn parseFailOn(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "any":
                return FailOn.ANY;
            case "new":
                return FailOn.NEW;
            case "none":
                return FailOn.NONE;
            default:
                throw new IllegalArgumentException("--fail-on must be any, new or none: " + value);
        }
    }

    static int exitCodeOf(Verdict verdict) {
        return verdict.exitCode();
    }

    static BudgetExceededException findBudget(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof BudgetExceededException) {
                return (BudgetExceededException) current;
            }
            current = current.getCause();
        }
        return null;
    }
}
