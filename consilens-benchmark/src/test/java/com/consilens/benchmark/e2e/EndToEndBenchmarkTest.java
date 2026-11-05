package com.consilens.benchmark.e2e;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndToEndBenchmarkTest {

    @Test
    void percentileUsesNearestRankSoP95KeepsTailLatency() {
        long p50 = EndToEndBenchmark.percentile(List.of(10L, 20L, 30L, 40L, 100L), 0.50);
        long p95 = EndToEndBenchmark.percentile(List.of(10L, 20L, 30L, 40L, 100L), 0.95);

        assertThat(p50).isEqualTo(30L);
        assertThat(p95).isEqualTo(100L);
    }

    @Test
    void percentileRejectsMissingSamples() {
        assertThatThrownBy(() -> EndToEndBenchmark.percentile(List.of(), 0.95))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void excludesWarmupAndAggregatesOnlyMeasuredSamples() throws Exception {
        Path cliJar = Files.createTempFile("benchmark-cli", ".jar");
        Path config = Files.createTempFile("benchmark-config", ".yaml");
        ScenarioLoader.Scenario scenario = new ScenarioLoader.Scenario(
                "TEST", config, Collections.emptyList(), Collections.emptyMap());
        Deque<CliProcessRunner.RunResult> runs = new ArrayDeque<>(List.of(
                run(999, 3), run(10, 3), run(20, 3), run(100, 3)));
        EndToEndBenchmark benchmark = new EndToEndBenchmark(
                cliJar, List.of("TEST"), new ScenarioLoader(),
                (jar, yaml) -> runs.removeFirst(), 1, 3, id -> 3L, "dataset",
                null, null, Collections.emptyMap());

        com.consilens.benchmark.report.BenchmarkResult result = benchmark.runScenario(scenario);

        assertThat(result.getStatus()).isEqualTo("RUN");
        assertThat(result.getSamples()).extracting("durationMs").containsExactly(10L, 20L, 100L);
        assertThat(result.getSubMetrics()).containsEntry("durationP50Ms", 20.0)
                .containsEntry("durationP95Ms", 100.0)
                .containsEntry("operationDurationMsP50", 20.0)
                .containsEntry("accuracy", 1.0);
        assertThat(runs).isEmpty();
    }

    @Test
    void failsWhenMeasuredDifferenceCountIsWrong() throws Exception {
        Path cliJar = Files.createTempFile("benchmark-cli", ".jar");
        Path config = Files.createTempFile("benchmark-config", ".yaml");
        ScenarioLoader.Scenario scenario = new ScenarioLoader.Scenario(
                "TEST", config, Collections.emptyList(), Collections.emptyMap());
        EndToEndBenchmark benchmark = new EndToEndBenchmark(
                cliJar, List.of("TEST"), new ScenarioLoader(),
                (jar, yaml) -> run(10, 2), 0, 1, id -> 3L, "dataset",
                null, null, Collections.emptyMap());

        com.consilens.benchmark.report.BenchmarkResult result = benchmark.runScenario(scenario);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getMessage()).contains("expected totalDifferences=3");
    }

    @Test
    void usesWholeProcessWallClockAsPrimaryLatency() throws Exception {
        Path cliJar = Files.createTempFile("benchmark-cli", ".jar");
        Path config = Files.createTempFile("benchmark-config", ".yaml");
        ScenarioLoader.Scenario scenario = new ScenarioLoader.Scenario(
                "TEST", config, Collections.emptyList(), Collections.emptyMap());
        EndToEndBenchmark benchmark = new EndToEndBenchmark(
                cliJar, List.of("TEST"), new ScenarioLoader(),
                (jar, yaml) -> run(10, 80, 0), 0, 1, id -> 0L, "dataset",
                null, null, Collections.emptyMap());

        com.consilens.benchmark.report.BenchmarkResult result = benchmark.runScenario(scenario);

        assertThat(result.getScore()).isEqualTo(80.0);
        assertThat(result.getSubMetrics()).containsEntry("operationDurationMsP50", 10.0)
                .containsEntry("processWallClockMsP50", 80.0);
    }

    @Test
    void refusesToRunWithoutExpectedDifferenceCount() throws Exception {
        Path cliJar = Files.createTempFile("benchmark-cli", ".jar");
        Path config = Files.createTempFile("benchmark-config", ".yaml");
        ScenarioLoader.Scenario scenario = new ScenarioLoader.Scenario(
                "TEST", config, Collections.emptyList(), Collections.emptyMap());
        EndToEndBenchmark benchmark = new EndToEndBenchmark(
                cliJar, List.of("TEST"), new ScenarioLoader(),
                (jar, yaml) -> {
                    throw new AssertionError("CLI must not run without accuracy contract");
                }, 0, 1, id -> null, "dataset", null, null, Collections.emptyMap());

        com.consilens.benchmark.report.BenchmarkResult result = benchmark.runScenario(scenario);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getMessage()).contains("--expected-differences TEST=COUNT");
    }

    @Test
    void externalScenarioConfigUsesTheSameAccuracyAndSamplingPath() throws Exception {
        Path cliJar = Files.createTempFile("benchmark-cli", ".jar");
        Path config = Files.createTempFile("external-benchmark", ".yaml");
        EndToEndBenchmark benchmark = new EndToEndBenchmark(
                cliJar, List.of("CUSTOM"), new ScenarioLoader(),
                (jar, yaml) -> run(10, 0), 0, 1, id -> 0L, "dataset",
                null, null, Map.of("CUSTOM", config));

        com.consilens.benchmark.report.BenchmarkResult result = benchmark.run().get(0);

        assertThat(result.getStatus()).isEqualTo("RUN");
        assertThat(result.getDimensions()).containsEntry("strategy", "external-config");
    }

    private CliProcessRunner.RunResult run(long durationMs, long differences) {
        return run(durationMs, durationMs, differences);
    }

    private CliProcessRunner.RunResult run(long durationMs, long wallClockMs, long differences) {
        String output = "Operation duration: " + durationMs + " ms\n"
                + "Total differences: " + differences + "\n"
                + "BENCHMARK_METRIC totalDifferences=" + differences + "\n";
        return CliProcessRunner.RunResult.completed(0, output, wallClockMs, false);
    }
}
