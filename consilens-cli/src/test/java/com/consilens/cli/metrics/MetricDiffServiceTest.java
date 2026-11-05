package com.consilens.cli.metrics;

import com.consilens.cli.command.MetricsCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricDiffServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldGroupMetricsByDimensionWithDuckDb() throws Exception {
        Path config = config("source.csv", "target.csv");

        Result result = execute(config, "--engine", "duckdb",
                "--group-by", "category", "--metric", "sum:amount", "--format", "json");

        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.output).contains("\"group\" : \"A\"");
        assertThat(result.output).contains("\"group\" : \"B\"");
        assertThat(result.output).contains("\"sourceValue\" : \"30\"");
        assertThat(result.output).contains("\"targetValue\" : \"51\"");
    }

    @Test
    void shouldAggregateDecimalsWiderThanTheLegacyCastInDuckDbMetrics() throws Exception {
        Path source = tempDir.resolve("scale-source.parquet");
        Path target = tempDir.resolve("scale-target.parquet");
        // DECIMAL(38,3) 有 35 位整数：老实现统一 CAST 到 DECIMAL(38,15)（只剩 23 位整数）
        // 会直接溢出报错，指标查询整条失败
        String value = "CAST(12345678901234567890123456789.123 AS DECIMAL(38,3))";
        writeParquet(source, "SELECT 1 AS id, " + value + " AS amount");
        writeParquet(target, "SELECT 1 AS id, " + value + " AS amount");

        Path config = parquetConfig(source, target);
        Result result = execute(config, "--engine", "duckdb", "--metric", "sum:amount", "--format", "json");

        assertThat(result.exitCode).isEqualTo(0);
        assertThat(result.error).doesNotContain("Conversion");
        assertThat(result.output).contains("12345678901234567890123456789.123");
    }

    @Test
    void shouldGroupMetricsWithJavaEngine() throws Exception {
        Path config = config("source.csv", "target.csv");

        Result result = execute(config, "--group-by", "category",
                "--metric", "sum:amount", "--format", "json");

        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.output).contains("\"group\" : \"A\"");
        assertThat(result.output).contains("\"group\" : \"B\"");
        assertThat(result.output).contains("\"sourceValue\" : \"30\"");
        assertThat(result.output).contains("\"targetValue\" : \"51\"");
    }

    @Test
    void shouldFailWhenMetricExceedsTolerance() throws Exception {
        Path config = config("source.csv", "target.csv");

        Result result = execute(config,
                "--metric", "count",
                "--metric", "nulls:amount",
                "--metric", "sum:amount",
                "--format", "json");

        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.output).contains("\"passed\" : false");
        assertThat(result.output).contains("\"metric\" : \"nulls:amount\"");
        assertThat(result.output).contains("\"sourceValue\" : \"1\"");
        assertThat(result.output).contains("\"targetValue\" : \"0\"");
    }

    @Test
    void shouldPassWithinRelativeTolerance() throws Exception {
        Path config = config("source.csv", "target.csv");

        Result result = execute(config, "--metric", "sum:amount",
                "--max-relative-delta", "60", "--format", "json");

        assertThat(result.exitCode).isZero();
        assertThat(result.output).contains("\"passed\" : true");
    }

    @Test
    void shouldComputeMetricsWithDuckDbEngine() throws Exception {
        Path config = config("source.csv", "target.csv");

        Result javaResult = execute(config, "--metric", "count", "--metric", "nulls:amount",
                "--metric", "sum:amount", "--metric", "min:amount", "--metric", "max:amount",
                "--metric", "avg:amount", "--format", "json");
        Result duckResult = execute(config, "--engine", "duckdb",
                "--metric", "count", "--metric", "nulls:amount",
                "--metric", "sum:amount", "--metric", "min:amount", "--metric", "max:amount",
                "--metric", "avg:amount", "--format", "json");

        assertThat(duckResult.exitCode).isEqualTo(1);
        assertThat(duckResult.output).contains("\"metric\" : \"count\"");
        assertThat(duckResult.output).contains("\"sourceValue\" : \"30\"");
        assertThat(duckResult.output).contains("\"targetValue\" : \"61\"");
        assertThat(duckResult.output.replace("\"engine\"", ""))
                .isEqualTo(javaResult.output.replace("\"engine\"", ""));
    }

    @Test
    void shouldMatchJavaSumForFloatingPointColumns() throws Exception {
        Path config = config("source.csv", "target.csv");
        Files.writeString(tempDir.resolve("source.csv"),
                "id,name,amount,category\n1,a,0.1,A\n2,b,0.2,A\n");
        Files.writeString(tempDir.resolve("target.csv"),
                "id,name,amount,category\n1,a,0.1,A\n2,b,0.3,A\n");

        Result javaResult = execute(config, "--metric", "sum:amount", "--format", "json");
        Result duckResult = execute(config, "--engine", "duckdb",
                "--metric", "sum:amount", "--format", "json");

        assertThat(duckResult.output).contains("\"sourceValue\" : \"0.3\"");
        assertThat(duckResult.output.replace("\"engine\"", ""))
                .isEqualTo(javaResult.output.replace("\"engine\"", ""));
    }

    @Test
    void shouldReturnNullAggregatesForAllNullColumnWithDuckDb() throws Exception {
        Path config = config("source.csv", "target.csv");
        Files.writeString(tempDir.resolve("source.csv"), "id,name,amount,category\n1,a,,A\n");
        Files.writeString(tempDir.resolve("target.csv"), "id,name,amount,category\n1,a,,A\n");

        Result result = execute(config, "--engine", "duckdb",
                "--metric", "sum:amount", "--metric", "avg:amount", "--format", "json");

        assertThat(result.exitCode).isZero();
        assertThat(result.output).contains("\"metric\" : \"sum:amount\"");
        assertThat(result.output).contains("\"metric\" : \"avg:amount\"");
    }

    @Test
    void shouldHandleEmptyFilesWithDuckDbMetrics() throws Exception {
        Path config = config("source.csv", "target.csv");
        Files.writeString(tempDir.resolve("source.csv"), "id,name,amount,category\n");
        Files.writeString(tempDir.resolve("target.csv"), "id,name,amount,category\n");

        Result result = execute(config, "--engine", "duckdb",
                "--metric", "count", "--metric", "sum:amount", "--format", "json");

        assertThat(result.exitCode).isZero();
        assertThat(result.output).contains("\"sourceValue\" : \"0\"");
        assertThat(result.output).contains("\"metric\" : \"sum:amount\"");
    }

    @Test
    void shouldRejectUnknownMetricColumn() throws Exception {
        Path config = config("source.csv", "target.csv");

        Result result = execute(config, "--metric", "sum:missing");

        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.error).contains("not found");
    }

    @Test
    void shouldParseMetricExpressions() {
        assertThat(MetricDefinition.parse("count").isNumeric()).isFalse();
        assertThat(MetricDefinition.parse("count:amount").getColumn()).isEqualTo("amount");
        assertThat(MetricDefinition.parse("sum:amount").isNumeric()).isTrue();
        assertThat(MetricDefinition.parse("avg:amount").getKind())
                .isEqualTo(MetricDefinition.Kind.AVG);
        assertThatThrownBy(() -> MetricDefinition.parse("sum"))
                .hasMessageContaining("requires a column");
        assertThatThrownBy(() -> MetricDefinition.parse("median:amount"))
                .hasMessageContaining("Unsupported metric");
    }

    private void writeParquet(Path path, String select) throws Exception {
        try (java.sql.Connection connection =
                     java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("COPY (" + select + ") TO '"
                    + path.toAbsolutePath().toString().replace("'", "''") + "' (FORMAT PARQUET)");
        }
    }

    private Path parquetConfig(Path source, Path target) throws Exception {
        Path config = tempDir.resolve("parquet-compare.yaml");
        Files.writeString(config, "source:\n"
                + "  type: file\n"
                + "  name: source\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: " + source.getFileName() + "\n"
                + "    path: " + source.toAbsolutePath() + "\n"
                + "    format: parquet\n"
                + "target:\n"
                + "  type: file\n"
                + "  name: target\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: " + target.getFileName() + "\n"
                + "    path: " + target.toAbsolutePath() + "\n"
                + "    format: parquet\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n");
        return config;
    }

    private Result execute(Path config, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(new MetricsCommand());
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));
        String[] all = new String[args.length + 2];
        all[0] = "-c";
        all[1] = config.toString();
        System.arraycopy(args, 0, all, 2, args.length);
        int exitCode = commandLine.execute(all);
        return new Result(exitCode, out.toString(), err.toString());
    }

    private Path config(String sourceName, String targetName) throws Exception {
        Files.writeString(tempDir.resolve("source.csv"),
                "id,name,amount,category\n1,alice,10,A\n2,bob,20,A\n3,carol,,B\n");
        Files.writeString(tempDir.resolve("target.csv"),
                "id,name,amount,category\n1,alice,10,A\n2,bob,21,B\n3,carol,30,B\n");
        Path config = tempDir.resolve("compare.yaml");
        Files.writeString(config, "source:\n"
                + "  type: file\n"
                + "  name: source\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: " + sourceName + "\n"
                + "    path: " + tempDir.resolve(sourceName).toAbsolutePath() + "\n"
                + "    format: csv\n"
                + "target:\n"
                + "  type: file\n"
                + "  name: target\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: " + targetName + "\n"
                + "    path: " + tempDir.resolve(targetName).toAbsolutePath() + "\n"
                + "    format: csv\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "  fields:\n"
                + "    source: [name, amount]\n"
                + "    target: [name, amount]\n");
        return config;
    }

    private static final class Result {
        private final int exitCode;
        private final String output;
        private final String error;

        private Result(int exitCode, String output, String error) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }
    }
}
