package com.consilens.benchmark.baseline;

import com.consilens.benchmark.report.BenchmarkResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DriftCheckerTest {

    private Baseline baselineWith(Map<String, BaselineEntry> entries) {
        Baseline b = new Baseline();
        b.setVersion(1);
        b.setEntries(entries);
        return b;
    }

    private BenchmarkResult run(String scenarioId, double score) {
        BenchmarkResult r = new BenchmarkResult();
        r.setScenarioId(scenarioId);
        r.setScore(score);
        r.setUnit("ops/s");
        r.setStatus("RUN");
        return r;
    }

    private BaselineEntry entry(double score, double threshold) {
        return BaselineEntry.builder().key("k").score(score).unit("ops/s").threshold(threshold).build();
    }

    @Test
    void noBaselineIsNewAndNotRegression() {
        DriftReport report = new DriftChecker().check(
                List.of(run("M01", 100.0)), BaselineStore.load(Path.of("/nonexistent")));
        DriftReport.DriftItem item = report.getItems().get(0);
        assertThat(item.getStatus()).isEqualTo(DriftReport.DriftStatus.NEW);
        assertThat(report.hasRegression()).isFalse();
    }

    @Test
    void nullBaselineIsNew() {
        DriftReport report = new DriftChecker().check(List.of(run("M01", 100.0)), null);
        assertThat(report.getItems().get(0).getStatus()).isEqualTo(DriftReport.DriftStatus.NEW);
    }

    @Test
    void regressionFails() {
        Map<String, BaselineEntry> entries = new LinkedHashMap<>();
        entries.put("M01", entry(100.0, 0.10));
        DriftReport report = new DriftChecker()
                .check(List.of(run("M01", 80.0)), baselineWith(entries));
        DriftReport.DriftItem item = report.getItems().get(0);
        // ratio=0.8 < 1-0.1=0.9 -> FAIL
        assertThat(item.getStatus()).isEqualTo(DriftReport.DriftStatus.FAIL);
        assertThat(report.hasRegression()).isTrue();
    }

    @Test
    void improvementIsWarnNotFail() {
        Map<String, BaselineEntry> entries = new LinkedHashMap<>();
        entries.put("M01", entry(100.0, 0.10));
        DriftReport report = new DriftChecker()
                .check(List.of(run("M01", 120.0)), baselineWith(entries));
        DriftReport.DriftItem item = report.getItems().get(0);
        // ratio=1.2 > 1+0.1=1.1 -> WARN
        assertThat(item.getStatus()).isEqualTo(DriftReport.DriftStatus.WARN);
        assertThat(report.hasRegression()).isFalse();
    }

    @Test
    void withinThresholdPasses() {
        Map<String, BaselineEntry> entries = new LinkedHashMap<>();
        entries.put("M01", entry(100.0, 0.10));
        DriftReport report = new DriftChecker()
                .check(List.of(run("M01", 105.0)), baselineWith(entries));
        // ratio=1.05 in [0.9,1.1] -> PASS
        assertThat(report.getItems().get(0).getStatus()).isEqualTo(DriftReport.DriftStatus.PASS);
        assertThat(report.hasRegression()).isFalse();
    }

    @Test
    void boundaryLowerEdgeIsPass() {
        Map<String, BaselineEntry> entries = new LinkedHashMap<>();
        entries.put("M01", entry(100.0, 0.10));
        DriftReport report = new DriftChecker()
                .check(List.of(run("M01", 90.0)), baselineWith(entries));
        assertThat(report.getItems().get(0).getStatus()).isEqualTo(DriftReport.DriftStatus.PASS);
    }

    @Test
    void boundaryUpperEdgeIsPass() {
        Map<String, BaselineEntry> entries = new LinkedHashMap<>();
        entries.put("M01", entry(100.0, 0.10));
        DriftReport report = new DriftChecker()
                .check(List.of(run("M01", 110.0)), baselineWith(entries));
        assertThat(report.getItems().get(0).getStatus()).isEqualTo(DriftReport.DriftStatus.PASS);
    }

    @Test
    void skippedScenarioIsSkipped() {
        BenchmarkResult skipped = new BenchmarkResult();
        skipped.setScenarioId("E02");
        skipped.setStatus("SKIPPED");
        skipped.setMessage("no db");
        DriftReport report = new DriftChecker()
                .check(List.of(skipped), baselineWith(Collections.emptyMap()));
        assertThat(report.getItems().get(0).getStatus()).isEqualTo(DriftReport.DriftStatus.SKIPPED);
        assertThat(report.hasRegression()).isFalse();
    }

    @Test
    void nonPositiveBaselineIsNew() {
        Map<String, BaselineEntry> entries = new LinkedHashMap<>();
        entries.put("M01", entry(0.0, 0.10));
        DriftReport report = new DriftChecker()
                .check(List.of(run("M01", 100.0)), baselineWith(entries));
        assertThat(report.getItems().get(0).getStatus()).isEqualTo(DriftReport.DriftStatus.NEW);
    }

    @Test
    void higherLatencyIsRegression() {
        Map<String, BaselineEntry> entries = new LinkedHashMap<>();
        entries.put("G01", BaselineEntry.builder().score(100).unit("ms").threshold(0.10).build());
        BenchmarkResult result = run("G01", 120);
        result.setUnit("ms");

        DriftReport report = new DriftChecker().check(List.of(result), baselineWith(entries));

        assertThat(report.getItems().get(0).getStatus()).isEqualTo(DriftReport.DriftStatus.FAIL);
    }
}
