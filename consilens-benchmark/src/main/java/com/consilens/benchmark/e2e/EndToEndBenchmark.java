package com.consilens.benchmark.e2e;

import com.consilens.benchmark.e2e.ScenarioLoader.Scenario;
import com.consilens.benchmark.report.BenchmarkResult;
import com.consilens.benchmark.report.BenchmarkSample;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 对真实 CLI 主链路执行预热、重复测量、准确性校验和分位数聚合。 */
public final class EndToEndBenchmark {

    public static final Duration DEFAULT_TIMEOUT = CliProcessRunner.DEFAULT_TIMEOUT;

    private static final String DEFAULT_CLI_JAR = "consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar";

    private final Path cliJar;
    private final List<String> scenarioIds;
    private final ScenarioLoader loader;
    private final CliExecutor executor;
    private final int warmupRuns;
    private final int measurementRuns;
    private final Function<String, Long> expectedDifferences;
    private final String datasetId;
    private final String keyDistribution;
    private final String differenceType;
    private final Map<String, Path> scenarioConfigs;

    public EndToEndBenchmark(List<String> scenarioIds, int warmupRuns, int measurementRuns,
                             Function<String, Long> expectedDifferences, String datasetId,
                             String keyDistribution, String differenceType,
                             Map<String, Path> scenarioConfigs) {
        this(defaultCliJar(), scenarioIds, new ScenarioLoader(),
                new CliProcessRunner(DEFAULT_TIMEOUT)::run, warmupRuns, measurementRuns,
                expectedDifferences, datasetId, keyDistribution, differenceType, scenarioConfigs);
    }

    EndToEndBenchmark(Path cliJar, List<String> scenarioIds, ScenarioLoader loader,
                      CliExecutor executor, int warmupRuns, int measurementRuns,
                      Function<String, Long> expectedDifferences, String datasetId,
                      String keyDistribution, String differenceType,
                      Map<String, Path> scenarioConfigs) {
        this.cliJar = cliJar;
        this.scenarioIds = scenarioIds == null ? Collections.emptyList() : new ArrayList<>(scenarioIds);
        this.loader = loader;
        this.executor = executor;
        this.warmupRuns = warmupRuns;
        this.measurementRuns = measurementRuns;
        this.expectedDifferences = expectedDifferences;
        this.datasetId = datasetId;
        this.keyDistribution = keyDistribution;
        this.differenceType = differenceType;
        this.scenarioConfigs = scenarioConfigs == null
                ? Collections.emptyMap() : new LinkedHashMap<>(scenarioConfigs);
    }

    public static Path defaultCliJar() {
        String override = System.getenv("CONSILENS_CLI_JAR");
        return Paths.get(override == null || override.trim().isEmpty() ? DEFAULT_CLI_JAR : override.trim());
    }

    public List<BenchmarkResult> run() {
        List<String> ids = scenarioIds.isEmpty() ? loader.defaultScenarioIds() : scenarioIds;
        List<BenchmarkResult> results = new ArrayList<>();
        for (String id : ids) {
            results.add(runScenario(id));
        }
        return results;
    }

    private BenchmarkResult runScenario(String id) {
        Scenario scenario = scenarioConfigs.containsKey(id)
                ? new Scenario(id, scenarioConfigs.get(id), Collections.emptyList(), externalDimensions())
                : loader.scenario(id);
        if (scenario == null) {
            return skipped(id, "unknown scenario id: " + id);
        }
        return runScenario(scenario);
    }

    BenchmarkResult runScenario(Scenario scenario) {
        String id = scenario.id();
        if (!scenario.supported()) {
            return unsupported(scenario);
        }
        if (!Files.exists(cliJar)) {
            return skipped(id, "CLI jar not found: " + cliJar
                    + "; build it with ./mvnw -pl consilens-cli -am package");
        }
        if (!Files.exists(scenario.configPath())) {
            return skipped(id, "config file not found: " + scenario.configPath());
        }
        List<String> missing = missingEnv(scenario.requiredEnvVars());
        if (!missing.isEmpty()) {
            return skipped(id, "missing database credentials: " + String.join(", ", missing));
        }

        Long expected = expectedDifferences.apply(id);
        if (expected == null) {
            return failed(id, "expected total differences is required for an executable scenario; "
                    + "use --expected-differences " + id + "=COUNT");
        }

        for (int i = 0; i < warmupRuns; i++) {
            CliProcessRunner.RunResult warmup = executor.run(cliJar, scenario.configPath());
            String failure = failure(warmup);
            if (failure != null) {
                return failed(id, "warmup " + (i + 1) + " failed: " + failure);
            }
        }

        List<BenchmarkSample> samples = new ArrayList<>();
        for (int i = 0; i < measurementRuns; i++) {
            CliProcessRunner.RunResult run = executor.run(cliJar, scenario.configPath());
            String failure = failure(run);
            if (failure != null) {
                return failed(id, "measurement " + (i + 1) + " failed: " + failure);
            }
            Double actual = metric(run, "totalDifferences");
            if (expected != null && (actual == null || actual.longValue() != expected)) {
                return failed(id, "accuracy check failed at measurement " + (i + 1)
                        + ": expected totalDifferences=" + expected + ", actual=" + actual);
            }
            samples.add(sample(i + 1, run));
        }
        return aggregate(scenario, samples, expected);
    }

    private BenchmarkResult aggregate(Scenario scenario, List<BenchmarkSample> samples, Long expected) {
        List<Long> durations = new ArrayList<>();
        for (BenchmarkSample sample : samples) {
            durations.add(sample.getDurationMs());
        }
        long p50 = percentile(durations, 0.50);
        long p95 = percentile(durations, 0.95);

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("sampleCount", (double) samples.size());
        metrics.put("durationMinMs", (double) Collections.min(durations));
        metrics.put("durationMeanMs", durations.stream().mapToLong(Long::longValue).average().orElse(0));
        metrics.put("durationP50Ms", (double) p50);
        metrics.put("durationP95Ms", (double) p95);
        metrics.put("durationMaxMs", (double) Collections.max(durations));
        aggregateSampleMetrics(samples, metrics);
        metrics.put("expectedDifferences", expected.doubleValue());
        metrics.put("accuracy", 1.0);

        BenchmarkResult result = baseResult(resultScenarioId(scenario.id()), "RUN", null);
        result.setKind(scenario.kind());
        result.setScore(p95);
        result.setDurationMs(p50);
        result.setSubMetrics(metrics);
        result.setSamples(samples);
        result.setDimensions(new LinkedHashMap<>(scenario.dimensions()));
        if (datasetId != null && !datasetId.isBlank()) {
            result.getDimensions().put("datasetId", datasetId);
        }
        if (keyDistribution != null && !keyDistribution.isBlank()) {
            result.getDimensions().put("keyDistribution", keyDistribution);
        }
        if (differenceType != null && !differenceType.isBlank()) {
            result.getDimensions().put("differenceType", differenceType);
        }
        result.getUnavailableMetrics().put("databaseCpuMs",
                "database-vendor probe is not configured");
        result.getUnavailableMetrics().put("databaseIoBytes",
                "database-vendor probe is not configured");
        result.getUnavailableMetrics().put("databasePhysicalRowsScanned",
                "logicalRowsScanned is an application-level count; database execution-plan probe is not configured");
        result.getUnavailableMetrics().put("databaseQueryCountExact",
                "instrumentedQueryCount is a lower bound until adapter-level JDBC instrumentation is available");
        result.getUnavailableMetrics().put("networkBytes",
                "JDBC driver does not expose actual wire bytes; resultBytesFetchedEstimate is payload estimate");
        return result;
    }

    private Map<String, String> externalDimensions() {
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("strategy", "external-config");
        dimensions.put("algorithm", "external-config");
        dimensions.put("keyDistribution", "external-config");
        dimensions.put("differenceType", "external-config");
        return dimensions;
    }

    private String resultScenarioId(String scenarioId) {
        return datasetId == null || datasetId.isBlank() ? scenarioId : scenarioId + "." + datasetId;
    }

    private static void aggregateSampleMetrics(List<BenchmarkSample> samples, Map<String, Double> target) {
        Map<String, List<Long>> values = new LinkedHashMap<>();
        for (BenchmarkSample sample : samples) {
            sample.getMetrics().forEach((key, value) -> values
                    .computeIfAbsent(key, ignored -> new ArrayList<>()).add(value.longValue()));
        }
        values.forEach((key, metricValues) -> {
            target.put(key + "P50", (double) percentile(metricValues, 0.50));
            target.put(key + "P95", (double) percentile(metricValues, 0.95));
        });
    }

    static long percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("percentile requires at least one value");
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private static BenchmarkSample sample(int iteration, CliProcessRunner.RunResult run) {
        BenchmarkSample sample = new BenchmarkSample();
        sample.setIteration(iteration);
        sample.setDurationMs(run.getWallClockMs());
        Map<String, Double> metrics = new LinkedHashMap<>(run.getDiffCounts());
        metrics.putAll(run.getBenchmarkMetrics());
        metrics.put("operationDurationMs", (double) run.effectiveDurationMs());
        metrics.put("processWallClockMs", (double) run.getWallClockMs());
        if (run.getSourceRowCount() != null) {
            metrics.put("sourceRowCount", run.getSourceRowCount().doubleValue());
            if (run.getWallClockMs() > 0) {
                metrics.put("rowsPerSecond", run.getSourceRowCount() * 1000.0 / run.getWallClockMs());
            }
        }
        sample.setMetrics(metrics);
        return sample;
    }

    private static Double metric(CliProcessRunner.RunResult run, String name) {
        Double value = run.getBenchmarkMetrics().get(name);
        return value != null ? value : run.getDiffCounts().get(name);
    }

    private static String failure(CliProcessRunner.RunResult run) {
        if (run.isTimedOut()) {
            return "timed out";
        }
        if (run.getExitCode() != 0) {
            return "CLI exited with code " + run.getExitCode() + ": " + tail(run.getOutput(), 20);
        }
        return null;
    }

    private static List<String> missingEnv(List<String> names) {
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            String value = System.getenv(name);
            if (value == null || value.trim().isEmpty()) {
                missing.add(name);
            }
        }
        return missing;
    }

    private static BenchmarkResult unsupported(Scenario scenario) {
        BenchmarkResult result = baseResult(scenario.id(), "UNSUPPORTED", scenario.unsupportedReason());
        result.setKind(scenario.kind());
        result.setDimensions(scenario.dimensions());
        return result;
    }

    private static BenchmarkResult skipped(String id, String message) {
        return baseResult(id, "SKIPPED", message);
    }

    private static BenchmarkResult failed(String id, String message) {
        return baseResult(id, "FAILED", message);
    }

    private static BenchmarkResult baseResult(String id, String status, String message) {
        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioId(id);
        result.setScore(0);
        result.setUnit("ms");
        result.setStatus(status);
        result.setTimestamp(Instant.now().toString());
        result.setMessage(message);
        return result;
    }

    private static String tail(String text, int lines) {
        if (text == null) {
            return "";
        }
        String[] parts = text.trim().split("\\R");
        StringBuilder result = new StringBuilder();
        int start = Math.max(0, parts.length - lines);
        for (int i = start; i < parts.length; i++) {
            result.append(parts[i]).append('\n');
        }
        return result.toString();
    }

    @FunctionalInterface
    interface CliExecutor {
        CliProcessRunner.RunResult run(Path cliJar, Path configPath);
    }
}
