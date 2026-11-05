package com.consilens.benchmark.runner;

import com.consilens.benchmark.report.BenchmarkResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkSuiteTest {

    @Test
    void shouldUseDistinctScenarioIdsForAlgorithmAndFileBenchmarks() {
        assertThat(BenchmarkSuite.resolveScenarioId(
                "com.consilens.benchmark.micro.LocalDiffEngineBenchmark",
                "100000", null, "0.00"))
                .isEqualTo("M01.100000.ratio0.00");
        assertThat(BenchmarkSuite.resolveScenarioId(
                "com.consilens.benchmark.micro.FileDiffBenchmark",
                "100000", "parquet", "0.05"))
                .isEqualTo("F02.100000.parquet.ratio0.05");
        assertThat(BenchmarkSuite.resolveScenarioId(
                "com.consilens.benchmark.micro.FileDiffBenchmark.diff",
                "100000", "parquet", "0.05"))
                .isEqualTo("F02.100000.parquet.ratio0.05");
        assertThat(BenchmarkSuite.resolveScenarioId(
                "com.consilens.benchmark.micro.FileDiffBenchmark",
                "10000", "csv", "0.00"))
                .isEqualTo("F01.10000.csv.ratio0.00");
    }

    @Test
    void updateBaselineDoesNotEraseItWhenEveryScenarioIsSkipped() throws Exception {
        Path baseline = Files.createTempFile("benchmark-baseline", ".json");
        String original = "{\"version\":1,\"entries\":{\"G01\":{\"score\":10,\"unit\":\"ms\",\"threshold\":0.2}}}";
        Files.writeString(baseline, original);
        Path output = Files.createTempDirectory("benchmark-report");
        BenchmarkOptions options = BenchmarkOptions.parse(new String[]{
                "--update-baseline", "--baseline-path", baseline.toString(),
                "--output-dir", output.toString()
        });
        BenchmarkResult skipped = new BenchmarkResult();
        skipped.setScenarioId("G01");
        skipped.setStatus("SKIPPED");

        int exitCode = new BenchmarkSuite(options).aggregateAndReport(List.of(skipped));

        assertThat(exitCode).isZero();
        assertThat(Files.readString(baseline)).isEqualTo(original);
    }

    @Test
    void corruptBaselineReturnsNonZero() throws Exception {
        Path baseline = Files.createTempFile("benchmark-baseline", ".json");
        Files.writeString(baseline, "not-json");
        Path output = Files.createTempDirectory("benchmark-report");
        BenchmarkOptions options = BenchmarkOptions.parse(new String[]{
                "--baseline-path", baseline.toString(),
                "--output-dir", output.toString()
        });
        BenchmarkResult skipped = new BenchmarkResult();
        skipped.setScenarioId("G01");
        skipped.setStatus("SKIPPED");

        int exitCode = new BenchmarkSuite(options).aggregateAndReport(List.of(skipped));

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    void reportGenerationFailureReturnsNonZero() throws Exception {
        Path outputAsFile = Files.createTempFile("benchmark-output", ".tmp");
        BenchmarkOptions options = BenchmarkOptions.parse(new String[]{
                "--output-dir", outputAsFile.toString()
        });
        BenchmarkResult skipped = new BenchmarkResult();
        skipped.setScenarioId("G01");
        skipped.setStatus("SKIPPED");

        int exitCode = new BenchmarkSuite(options).aggregateAndReport(List.of(skipped));

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    void partialUpdatePreservesBaselinesForScenariosNotRun() throws Exception {
        Path baseline = Files.createTempFile("benchmark-baseline", ".json");
        Files.writeString(baseline, "{\"version\":1,\"entries\":{"
                + "\"G01\":{\"score\":10,\"unit\":\"ms\",\"threshold\":0.2},"
                + "\"G02\":{\"score\":20,\"unit\":\"ms\",\"threshold\":0.2}}}");
        Path output = Files.createTempDirectory("benchmark-report");
        BenchmarkOptions options = BenchmarkOptions.parse(new String[]{
                "--update-baseline", "--baseline-path", baseline.toString(),
                "--output-dir", output.toString()
        });
        BenchmarkResult run = new BenchmarkResult();
        run.setScenarioId("G01");
        run.setStatus("RUN");
        run.setScore(15);
        run.setUnit("ms");

        new BenchmarkSuite(options).aggregateAndReport(List.of(run));

        String updated = Files.readString(baseline);
        assertThat(updated).contains("\"G01\"").contains("15.0")
                .contains("\"G02\"").contains("20.0");
    }

    @Test
    void generatedE2eBaselineUsesRelaxedLatencyThreshold() throws Exception {
        Path baseline = Files.createTempFile("benchmark-baseline", ".json");
        Files.writeString(baseline, "{\"version\":1,\"entries\":{}}");
        Path output = Files.createTempDirectory("benchmark-report");
        BenchmarkOptions options = BenchmarkOptions.parse(new String[]{
                "--update-baseline", "--baseline-path", baseline.toString(),
                "--output-dir", output.toString()
        });
        BenchmarkResult run = new BenchmarkResult();
        run.setScenarioId("G01.dataset");
        run.setKind(com.consilens.benchmark.e2e.ScenarioLoader.KIND_E2E);
        run.setStatus("RUN");
        run.setScore(100);
        run.setUnit("ms");

        new BenchmarkSuite(options).aggregateAndReport(List.of(run));

        assertThat(Files.readString(baseline)).contains("\"threshold\" : 0.25");
    }
}
