package com.consilens.benchmark.micro;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.compare.DefaultCompareRuntime;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end local file benchmark: generate CSV/Parquet files, then run the
 * real {@link DefaultCompareRuntime} (file connector + streaming merge) per
 * invocation. Data generation happens in {@code @Setup}, outside measurement.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
@Fork(1)
public class FileDiffBenchmark {

    private static final String LOGGER_NAME = "com.consilens";

    @Param({"10000", "100000"})
    private int rows;

    @Param({"csv", "parquet"})
    private String format;

    /** 0 = identical, 0.05 = every 20th row differs. */
    @Param({"0.00", "0.05"})
    private String diffRatio;

    private Path tempDir;
    private CompareRequest request;
    private DefaultCompareRuntime runtime;
    private LoggerContext loggerContext;
    private Level originalLevel;

    @Setup
    public void setup() throws Exception {
        silenceLogs();
        tempDir = Files.createTempDirectory("consilens-file-bench-");
        Path source = tempDir.resolve("source." + format);
        Path target = tempDir.resolve("target." + format);
        if ("parquet".equals(format)) {
            writeParquet(source, target, Double.parseDouble(diffRatio) > 0);
        } else {
            writeCsv(source, target, Double.parseDouble(diffRatio) > 0);
        }
        request = request(source, target, format);
        runtime = new DefaultCompareRuntime();
    }

    @Benchmark
    public long diff() throws Exception {
        return runtime.execute(request).getStatistics().getTotalDifferences();
    }

    @TearDown
    public void tearDown() throws IOException {
        restoreLogs();
        if (tempDir != null) {
            try (java.util.stream.Stream<Path> stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            }
        }
    }

    private void writeCsv(Path source, Path target, boolean different) throws IOException {
        writeCsvFile(source, false);
        writeCsvFile(target, different);
    }

    private void writeCsvFile(Path path, boolean different) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("id,payload,amount\n");
            for (int index = 1; index <= rows; index++) {
                String payload = different && index % 20 == 0 ? "changed" : "p-" + index;
                writer.write(index + "," + payload + "," + (index * 0.5d) + "\n");
            }
        }
    }

    private void writeParquet(Path source, Path target, boolean different) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute("COPY (SELECT i AS id, 'p-' || i AS payload,"
                    + " CAST(i AS DOUBLE) * 0.5 AS amount FROM range(1, " + (rows + 1) + ") t(i))"
                    + " TO '" + sqlPath(source) + "' (FORMAT PARQUET)");
            String targetPayload = different
                    ? "CASE WHEN i % 20 = 0 THEN 'changed' ELSE 'p-' || i END"
                    : "'p-' || i";
            statement.execute("COPY (SELECT i AS id, " + targetPayload + " AS payload,"
                    + " CAST(i AS DOUBLE) * 0.5 AS amount FROM range(1, " + (rows + 1) + ") t(i))"
                    + " TO '" + sqlPath(target) + "' (FORMAT PARQUET)");
        }
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }

    private static CompareRequest request(Path source, Path target, String format) {
        return CompareRequest.builder()
                .source(config("source", source, format))
                .target(config("target", target, format))
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .sourceComparisons(ComparisonSpec.builder().fields(List.of("payload", "amount")).build())
                .targetComparisons(ComparisonSpec.builder().fields(List.of("payload", "amount")).build())
                .build();
    }

    private static ConnectorConfig config(String name, Path path, String format) {
        return ConnectorConfig.builder()
                .type("file")
                .name(name)
                .resource(ResourceLocator.builder()
                        .type("file")
                        .path(path.toString())
                        .options(Map.of("format", format))
                        .build())
                .build();
    }

    private void silenceLogs() {
        loggerContext = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = loggerContext.getConfiguration();
        LoggerConfig logger = configuration.getLoggerConfig(LOGGER_NAME);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        loggerContext.updateLoggers();
    }

    private void restoreLogs() {
        if (loggerContext == null) {
            return;
        }
        Configuration configuration = loggerContext.getConfiguration();
        LoggerConfig logger = configuration.getLoggerConfig(LOGGER_NAME);
        logger.setLevel(originalLevel == null ? Level.INFO : originalLevel);
        loggerContext.updateLoggers();
    }
}
