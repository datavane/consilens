package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.metrics.MetricDefinition;
import com.consilens.cli.metrics.MetricDiffService;
import com.consilens.cli.metrics.MetricReportWriter;
import com.consilens.cli.metrics.MetricResult;
import com.consilens.cli.model.CliConfiguration;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Compares aggregate metrics on both sides of an existing comparison config.
 *
 * <p>Exit codes: 0 all metrics within tolerance, 1 at least one exceeds,
 * 2 configuration/execution error.
 */
@Command(
        name = "metrics",
        description = "Compare aggregate metrics from a comparison config",
        mixinStandardHelpOptions = true
)
public class MetricsCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file path")
    private String configFile;

    @Option(names = {"--metric"}, split = ",",
            description = "Metric expressions: count, count:col, nulls:col, sum:col, min:col, max:col, avg:col")
    private List<String> metrics;

    @Option(names = {"--engine"}, defaultValue = "java",
            description = "Aggregation engine: java (default) | duckdb (local files)")
    private String engine;

    @Option(names = {"--group-by"}, split = ",",
            // 两个引擎都支持：duckdb 下推成 SQL GROUP BY，java 下在内存里聚合。
            // 早先这里写成 "requires --engine duckdb"，而 duckdb 只吃本地文件，
            // 会让"JDBC 源想按维度看指标"的人以为这个功能用不了。
            description = "Comma separated dimension columns (java aggregates in memory, "
                    + "duckdb pushes down SQL GROUP BY)")
    private List<String> groupBy;

    @Option(names = {"--max-absolute-delta"}, defaultValue = "0",
            description = "Absolute delta tolerance (default 0)")
    private String maxAbsoluteDelta;

    @Option(names = {"--max-relative-delta"}, defaultValue = "0",
            description = "Relative delta tolerance in percent (default 0)")
    private String maxRelativeDelta;

    @Option(names = {"--format"}, defaultValue = "markdown",
            description = "Report format: markdown | json")
    private String format;

    @Option(names = {"-o", "--output"}, description = "Report output file (default: stdout)")
    private String outputFile;

    @Override
    public Integer call() {
        try {
            CliConfiguration config = new ConfigurationManager().loadConfiguration(configFile);
            List<MetricDefinition> definitions = new ArrayList<>();
            if (metrics == null || metrics.isEmpty()) {
                definitions.add(MetricDefinition.parse("count"));
            } else {
                for (String metric : metrics) {
                    definitions.add(MetricDefinition.parse(metric));
                }
            }
            BigDecimal absolute = new BigDecimal(maxAbsoluteDelta.trim());
            BigDecimal relative = new BigDecimal(maxRelativeDelta.trim());
            if (absolute.signum() < 0 || relative.signum() < 0) {
                throw new IllegalArgumentException("tolerances must be non-negative");
            }

            List<MetricResult> results = new MetricDiffService()
                    .compare(config, definitions, absolute, relative,
                            com.consilens.cli.metrics.MetricEngine.from(engine),
                            groupBy == null ? List.of() : groupBy);
            Path output = outputFile == null || outputFile.isBlank() ? null : Paths.get(outputFile);
            String report = MetricReportWriter.write(results, format, output);

            boolean passed = results.stream().allMatch(MetricResult::isPassed);
            if (output != null) {
                spec.commandLine().getOut().println("metrics: " + (passed ? "PASS" : "FAIL")
                        + " (" + results.size() + " metrics) -> " + output);
            } else {
                spec.commandLine().getOut().print(report);
                spec.commandLine().getOut().flush();
            }
            return passed ? 0 : 1;
        } catch (Exception exception) {
            spec.commandLine().getErr().println("metrics failed: " + exception.getMessage());
            return 2;
        }
    }
}
