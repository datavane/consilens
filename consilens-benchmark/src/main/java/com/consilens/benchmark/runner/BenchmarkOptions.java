package com.consilens.benchmark.runner;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 基准套件命令行参数。解析 --mode / --scenario / --rows / --diff-ratio /
 * --update-baseline / --output-dir / --baseline-path 以及可选的 JMH 精简参数
 * --forks / --warmup-iterations / --measurement-iterations。
 *
 * 默认 JMH 参数为精简值（1/1/1），便于本地快速验证；正式基准可在命令行覆盖。
 */
public final class BenchmarkOptions {

    public static final String MODE_MICRO = "micro";
    public static final String MODE_E2E = "e2e";
    public static final String MODE_ALL = "all";

    private static final String DEFAULT_OUTPUT_DIR = "target/benchmark/report";
    private static final String DEFAULT_BASELINE_PATH =
            "src/main/resources/baseline/benchmark-baseline.json";

    private final String mode;
    private final Set<String> scenarios;
    private final boolean updateBaseline;
    private final Path outputDir;
    private final Path baselinePath;
    private final List<String> rows;
    private final List<String> diffRatios;
    private final int forks;
    private final int warmupIterations;
    private final int measurementIterations;
    private final int e2eWarmupRuns;
    private final int e2eMeasurementRuns;
    private final Map<String, Long> expectedDifferences;
    private final String datasetId;
    private final String keyDistribution;
    private final String differenceType;
    private final Map<String, Path> scenarioConfigs;

    private BenchmarkOptions(String mode, Set<String> scenarios, boolean updateBaseline,
                             Path outputDir, Path baselinePath, List<String> rows, List<String> diffRatios,
                             int forks, int warmupIterations, int measurementIterations,
                             int e2eWarmupRuns, int e2eMeasurementRuns,
                             Map<String, Long> expectedDifferences, String datasetId,
                             String keyDistribution, String differenceType,
                             Map<String, Path> scenarioConfigs) {
        this.mode = mode;
        this.scenarios = Collections.unmodifiableSet(scenarios);
        this.updateBaseline = updateBaseline;
        this.outputDir = outputDir;
        this.baselinePath = baselinePath;
        this.rows = rows;
        this.diffRatios = diffRatios;
        this.forks = forks;
        this.warmupIterations = warmupIterations;
        this.measurementIterations = measurementIterations;
        this.e2eWarmupRuns = e2eWarmupRuns;
        this.e2eMeasurementRuns = e2eMeasurementRuns;
        this.expectedDifferences = Collections.unmodifiableMap(expectedDifferences);
        this.datasetId = datasetId;
        this.keyDistribution = keyDistribution;
        this.differenceType = differenceType;
        this.scenarioConfigs = Collections.unmodifiableMap(new LinkedHashMap<>(scenarioConfigs));
    }

    public String mode() {
        return mode;
    }

    public Set<String> scenarios() {
        return scenarios;
    }

    public boolean updateBaseline() {
        return updateBaseline;
    }

    public Path outputDir() {
        return outputDir;
    }

    public Path baselinePath() {
        return baselinePath;
    }

    /** 是否显式指定数据量档位；未指定时使用微基准 @Param 默认值。 */
    public boolean hasRows() {
        return rows != null && !rows.isEmpty();
    }

    public List<String> rows() {
        return rows;
    }

    /** 是否显式指定差异比例档位；未指定时使用微基准 @Param 默认值。 */
    public boolean hasDiffRatios() {
        return diffRatios != null && !diffRatios.isEmpty();
    }

    public List<String> diffRatios() {
        return diffRatios;
    }

    public int forks() {
        return forks;
    }

    public int warmupIterations() {
        return warmupIterations;
    }

    public int measurementIterations() {
        return measurementIterations;
    }

    public int e2eWarmupRuns() {
        return e2eWarmupRuns;
    }

    public int e2eMeasurementRuns() {
        return e2eMeasurementRuns;
    }

    public Long expectedDifferences(String scenarioId) {
        return expectedDifferences.get(scenarioId);
    }

    public String datasetId() {
        return datasetId;
    }

    public String keyDistribution() {
        return keyDistribution;
    }

    public String differenceType() {
        return differenceType;
    }

    public Map<String, Path> scenarioConfigs() {
        return scenarioConfigs;
    }

    public boolean runMicro() {
        return MODE_MICRO.equals(mode) || MODE_ALL.equals(mode);
    }

    public boolean runE2e() {
        return MODE_E2E.equals(mode) || MODE_ALL.equals(mode);
    }

    /**
     * 解析命令行。未知参数忽略并记录到 stderr，不抛异常以保持套件可运行。
     */
    public static BenchmarkOptions parse(String[] args) {
        String mode = MODE_MICRO;
        Set<String> scenarios = new LinkedHashSet<>();
        boolean updateBaseline = false;
        Path outputDir = Paths.get(DEFAULT_OUTPUT_DIR);
        Path baselinePath = Paths.get(DEFAULT_BASELINE_PATH);
        List<String> rows = null;
        List<String> diffRatios = null;
        int forks = 1;
        int warmupIterations = 1;
        int measurementIterations = 1;
        int e2eWarmupRuns = 1;
        int e2eMeasurementRuns = 5;
        Map<String, Long> expectedDifferences = new LinkedHashMap<>();
        String datasetId = null;
        String keyDistribution = null;
        String differenceType = null;
        Map<String, Path> scenarioConfigs = new LinkedHashMap<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--mode":
                    mode = next(args, ++i, arg);
                    break;
                case "--scenario":
                    String raw = next(args, ++i, arg);
                    for (String s : raw.split(",")) {
                        String trimmed = s.trim();
                        if (!trimmed.isEmpty()) {
                            scenarios.add(trimmed);
                        }
                    }
                    break;
                case "--update-baseline":
                    updateBaseline = true;
                    break;
                case "--output-dir":
                    outputDir = Paths.get(next(args, ++i, arg));
                    break;
                case "--baseline-path":
                    baselinePath = Paths.get(next(args, ++i, arg));
                    break;
                case "--rows":
                    rows = splitList(next(args, ++i, arg));
                    break;
                case "--diff-ratio":
                    diffRatios = splitList(next(args, ++i, arg));
                    break;
                case "--forks":
                    forks = Integer.parseInt(next(args, ++i, arg));
                    break;
                case "--warmup-iterations":
                    warmupIterations = Integer.parseInt(next(args, ++i, arg));
                    break;
                case "--measurement-iterations":
                    measurementIterations = Integer.parseInt(next(args, ++i, arg));
                    break;
                case "--e2e-warmup-runs":
                    e2eWarmupRuns = Integer.parseInt(next(args, ++i, arg));
                    break;
                case "--e2e-measurement-runs":
                    e2eMeasurementRuns = Integer.parseInt(next(args, ++i, arg));
                    break;
                case "--expected-differences":
                    parseExpectedDifferences(next(args, ++i, arg), expectedDifferences);
                    break;
                case "--dataset-id":
                    datasetId = next(args, ++i, arg).trim();
                    break;
                case "--key-distribution":
                    keyDistribution = next(args, ++i, arg).trim().toLowerCase();
                    break;
                case "--difference-type":
                    differenceType = next(args, ++i, arg).trim().toLowerCase();
                    break;
                case "--scenario-config":
                    parseScenarioConfigs(next(args, ++i, arg), scenarioConfigs);
                    break;
                default:
                    System.err.println("benchmark: ignoring unknown argument: " + arg);
            }
        }
        if (e2eWarmupRuns < 0 || e2eMeasurementRuns < 1) {
            throw new IllegalArgumentException("benchmark: e2e warmup must be >= 0 and measurement runs must be > 0");
        }
        if (!MODE_MICRO.equals(mode) && !MODE_E2E.equals(mode) && !MODE_ALL.equals(mode)) {
            // 拼写错误不能让套件“零结果但退出码 0”，否则 CI 会误判通过
            throw new IllegalArgumentException(
                    "benchmark: --mode must be one of micro, e2e, all (got: " + mode + ")");
        }
        validateRows(rows);
        validateDiffRatios(diffRatios);
        return new BenchmarkOptions(mode, scenarios, updateBaseline, outputDir, baselinePath,
                rows, diffRatios, forks, warmupIterations, measurementIterations,
                e2eWarmupRuns, e2eMeasurementRuns, expectedDifferences, datasetId,
                keyDistribution, differenceType, scenarioConfigs);
    }

    private static void validateRows(List<String> rows) {
        if (rows == null) {
            return;
        }
        for (String row : rows) {
            try {
                if (Long.parseLong(row.trim()) <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "benchmark: --rows values must be positive integers (got: " + row + ")");
            }
        }
    }

    private static void validateDiffRatios(List<String> ratios) {
        if (ratios == null) {
            return;
        }
        for (String ratio : ratios) {
            try {
                double value = Double.parseDouble(ratio.trim());
                // NaN 与任何边界比较都为 false，必须在范围判断外单独拒绝
                if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "benchmark: --diff-ratio values must be numbers in [0,1] (got: " + ratio + ")");
            }
        }
    }

    private static String next(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("benchmark: missing value for " + flag);
        }
        return args[index];
    }

    private static List<String> splitList(String raw) {
        List<String> values = new ArrayList<>();
        for (String s : raw.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static void parseExpectedDifferences(String raw, Map<String, Long> sink) {
        for (String entry : raw.split(",")) {
            String[] pair = entry.trim().split("=", 2);
            if (pair.length != 2 || pair[0].isBlank()) {
                throw new IllegalArgumentException("benchmark: expected differences must use SCENARIO=COUNT");
            }
            long count = Long.parseLong(pair[1]);
            if (count < 0) {
                throw new IllegalArgumentException("benchmark: expected differences must be >= 0");
            }
            sink.put(pair[0], count);
        }
    }

    private static void parseScenarioConfigs(String raw, Map<String, Path> sink) {
        for (String entry : raw.split(",")) {
            String[] pair = entry.trim().split("=", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                throw new IllegalArgumentException("benchmark: scenario config must use SCENARIO=PATH");
            }
            sink.put(pair[0], Paths.get(pair[1]));
        }
    }

    /**
     * 过滤场景：若未指定 --scenario 返回全集；否则返回交集，保持入参顺序。
     */
    public List<String> filterScenarios(List<String> candidates) {
        if (scenarios.isEmpty()) {
            return new ArrayList<>(candidates);
        }
        List<String> result = new ArrayList<>();
        for (String c : candidates) {
            if (scenarios.contains(c)) {
                result.add(c);
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "BenchmarkOptions{mode=" + mode + ", scenarios=" + scenarios
                + ", updateBaseline=" + updateBaseline + ", outputDir=" + outputDir
                + ", baselinePath=" + baselinePath + ", rows=" + rows
                + ", diffRatios=" + diffRatios + ", forks=" + forks
                + ", warmup=" + warmupIterations + ", measurement=" + measurementIterations
                + ", e2eWarmupRuns=" + e2eWarmupRuns + ", e2eMeasurementRuns=" + e2eMeasurementRuns
                + ", expectedDifferences=" + expectedDifferences + ", datasetId=" + datasetId
                + ", keyDistribution=" + keyDistribution + ", differenceType=" + differenceType
                + ", scenarioConfigs=" + scenarioConfigs + "}";
    }
}
