package com.consilens.benchmark.runner;

import com.consilens.benchmark.baseline.Baseline;
import com.consilens.benchmark.baseline.BaselineEntry;
import com.consilens.benchmark.baseline.BaselineStore;
import com.consilens.benchmark.baseline.DriftChecker;
import com.consilens.benchmark.baseline.DriftReport;
import com.consilens.benchmark.e2e.EndToEndBenchmark;
import com.consilens.benchmark.e2e.ScenarioLoader;
import com.consilens.benchmark.micro.FileDiffBenchmark;
import com.consilens.benchmark.micro.LocalDiffEngineBenchmark;
import com.consilens.benchmark.report.BenchmarkReportGenerator;
import com.consilens.benchmark.report.BenchmarkResult;
import com.consilens.common.io.PathSupport;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基准套件编排：运行 micro/e2e、聚合结果、读基线、漂移判定、产报告、可选回写基线。
 *
 * <p>micro 路径用 JMH 编程式 {@link Runner} 跑 {@link LocalDiffEngineBenchmark}，
 * 把每个 {@link RunResult} 的主指标解析为 {@link BenchmarkResult}（scenarioId = 场景编号 + 规模后缀）。
 * e2e 路径本单元留 placeholder，返回空列表，由 UNIT-E2E 接入。
 */
public final class BenchmarkSuite {

    static final String M01 = "M01";
    static final String M02 = "M02";
    static final String F01 = "F01";
    static final String F02 = "F02";

    private final BenchmarkOptions options;

    public BenchmarkSuite(BenchmarkOptions options) {
        this.options = options;
    }

    /**
     * 运行选定模式，返回退出码：有回归非零，否则 0。
     */
    public int run() throws RunnerException {
        List<BenchmarkResult> results = new ArrayList<>();
        if (options.runMicro()) {
            results.addAll(runMicro());
        }
        if (options.runE2e()) {
            results.addAll(runE2e());
        }

        return aggregateAndReport(results);
    }

    /**
     * 编程式跑 JMH 微基准，解析 RunResult 到 BenchmarkResult 列表。
     */
    public List<BenchmarkResult> runMicro() throws RunnerException {
        ChainedOptionsBuilder builder = new OptionsBuilder()
                .include(LocalDiffEngineBenchmark.class.getName())
                .include(FileDiffBenchmark.class.getName())
                .forks(options.forks())
                .warmupIterations(options.warmupIterations())
                .measurementIterations(options.measurementIterations())
                .shouldFailOnError(true);
        if (options.hasRows()) {
            builder.param("rows", options.rows().toArray(new String[0]));
        }
        if (options.hasDiffRatios()) {
            builder.param("diffRatio", options.diffRatios().toArray(new String[0]));
        }
        Options jmhOpts = builder.build();

        Collection<RunResult> runResults = new Runner(jmhOpts).run();

        List<BenchmarkResult> results = new ArrayList<>();
        for (RunResult rr : runResults) {
            double score = rr.getPrimaryResult().getScore();
            String unit = rr.getPrimaryResult().getScoreUnit();

            String scenarioId = resolveScenarioId(rr.getParams().getBenchmark(),
                    rr.getParams().getParam("rows"),
                    rr.getParams().getParam("format"),
                    rr.getParams().getParam("diffRatio"));
            // shouldFailOnError(true) makes JMH throw on benchmark errors, so a returned
            // RunResult is a successful execution even when throughput is legitimately 0.
            String status = "RUN";
            Map<String, Double> subMetrics = new TreeMap<>();
            long sampleCount = (long) rr.getPrimaryResult().getStatistics().getN();
            subMetrics.put("sampleCount", (double) sampleCount);
            String rowsParam = rr.getParams().getParam("rows");
            if (rowsParam != null) {
                subMetrics.put("rows", Double.parseDouble(rowsParam));
            }

            BenchmarkResult result = new BenchmarkResult();
            result.setScenarioId(scenarioId);
            result.setKind(ScenarioLoader.KIND_MICRO);
            result.setScore(score);
            result.setUnit(unit);
            result.setStatus(status);
            // JMH 编程式 RunResult 不直接暴露单次 wall-clock 耗时；保留 0，真实采样数记入 subMetrics
            result.setDurationMs(0L);
            result.setTimestamp(Instant.now().toString());
            result.setSubMetrics(subMetrics);
            results.add(result);
        }
        return results;
    }

    public List<BenchmarkResult> runE2e() {
        return new EndToEndBenchmark(new ArrayList<>(options.scenarios()),
                options.e2eWarmupRuns(), options.e2eMeasurementRuns(), options::expectedDifferences,
                options.datasetId(), options.keyDistribution(), options.differenceType(),
                options.scenarioConfigs()).run();
    }

    /**
     * 聚合：读基线、漂移判定、产报告、可选回写基线。返回退出码。
     */
    int aggregateAndReport(List<BenchmarkResult> results) {
        Path baselinePath = options.baselinePath();
        Baseline baseline = null;
        boolean auxiliaryFailure = false;
        if (Files.exists(baselinePath)) {
            try {
                baseline = BaselineStore.load(baselinePath);
            } catch (RuntimeException e) {
                // 基线存在但损坏时不能静默降级成“无基线”：CI 会误判为通过。
                System.err.println("benchmark: failed to load baseline: " + e.getMessage());
                auxiliaryFailure = true;
            }
        }

        DriftReport drift = new DriftChecker().check(results, baseline);

       Path outputDir = options.outputDir();
       try {
           PathSupport.ensureDirectory(outputDir);
           new BenchmarkReportGenerator().generate(results, drift, outputDir);
        } catch (RuntimeException | IOException e) {
            System.err.println("benchmark: report generation failed: " + e.getMessage());
            auxiliaryFailure = true;
        }

        if (options.updateBaseline() && results.stream().anyMatch(result -> "RUN".equals(result.getStatus()))) {
            auxiliaryFailure |= !writeBaseline(results, baseline, baselinePath);
        }

        boolean hasRegression = drift.hasRegression();
        boolean hasExecutionFailure = results.stream().anyMatch(result -> "FAILED".equals(result.getStatus()));
        System.out.println("benchmark: regression=" + hasRegression + ", executionFailure="
                + hasExecutionFailure + ", results=" + results.size());
        return hasRegression || hasExecutionFailure || auxiliaryFailure ? 1 : 0;
    }

    private boolean writeBaseline(List<BenchmarkResult> results, Baseline existingBaseline, Path baselinePath) {
        Map<String, BaselineEntry> entries = new TreeMap<>();
        if (existingBaseline != null && existingBaseline.getEntries() != null) {
            entries.putAll(existingBaseline.getEntries());
        }
        for (BenchmarkResult r : results) {
            if (!"RUN".equals(r.getStatus())) {
                continue;
            }
            BaselineEntry entry = new BaselineEntry();
            entry.setScore(r.getScore());
            entry.setUnit(r.getUnit());
            entry.setThreshold(defaultThreshold(r));
            entries.put(r.getScenarioId(), entry);
        }
        Baseline baseline = new Baseline();
        baseline.setVersion(1);
        baseline.setCreatedAt(Instant.now().toString());
        baseline.setJdk(System.getProperty("java.version"));
        baseline.setEntries(entries);
       try {
           PathSupport.ensureParent(baselinePath);
           BaselineStore.store(baseline, baselinePath);
           System.out.println("benchmark: baseline written to " + baselinePath);
           return true;
        } catch (RuntimeException | IOException e) {
            System.err.println("benchmark: baseline write failed: " + e.getMessage());
            return false;
        }
    }

    private static double defaultThreshold(BenchmarkResult result) {
        // 微基准收紧，e2e 放宽；由场景显式 kind 决定，而不是从 scenarioId 前缀推断
        return ScenarioLoader.KIND_MICRO.equals(result.getKind()) ? 0.15 : 0.25;
    }

    static String resolveScenarioId(String benchmark, String rows, String format,
                                    String diffRatio) {
        double ratio = diffRatio == null ? 0.05 : Double.parseDouble(diffRatio);
        String ratioLabel = diffRatio == null ? "default" : diffRatio;
        if (benchmark != null && benchmark.contains("FileDiffBenchmark")) {
            String code = ratio <= 0 ? F01 : F02;
            return code + "." + rows + "." + (format == null ? "unknown" : format)
                    + ".ratio" + ratioLabel;
        }
        String code = ratio <= 0 ? M01 : M02;
        return code + "." + rows + ".ratio" + ratioLabel;
    }
}
