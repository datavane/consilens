package com.consilens.benchmark.baseline;

import com.consilens.benchmark.report.BenchmarkResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 基于相对阈值的漂移判定。
 *
 * 判定方向（ratio = current / baseline）：
 * <ul>
 *   <li>无基线 → NEW（WARN，不 fail）</li>
 *   <li>ratio &lt; 1 - threshold → FAIL（回归）</li>
 *   <li>ratio &gt; 1 + threshold → WARN（改善，不 fail）</li>
 *   <li>否则 → PASS</li>
 * </ul>
 *
 * 仅对 status="RUN" 的结果做漂移判定；SKIPPED/FAILED 标记为 SKIPPED。
 */
@Slf4j
public class DriftChecker {

    /** Units whose metric is latency: lower is better. All other units are treated as throughput. */
    private static final Set<String> LATENCY_UNITS = Set.of("ms", "s/op", "us/op", "ns/op");

    public DriftChecker() {
    }

    /** 对一批结果按给定基线逐条判定；baseline 为 null 时按空基线处理。 */
    public DriftReport check(List<BenchmarkResult> results, Baseline baseline) {
        Map<String, BaselineEntry> entries = (baseline == null || baseline.getEntries() == null)
                ? Collections.emptyMap() : baseline.getEntries();
        List<DriftReport.DriftItem> items = new ArrayList<>();
        for (BenchmarkResult result : results) {
            items.add(checkOne(result, entries.get(result.getScenarioId())));
        }
        return DriftReport.builder().items(items).build();
    }

    private DriftReport.DriftItem checkOne(BenchmarkResult result, BaselineEntry entry) {
        String key = result.getScenarioId();
        String status = result.getStatus();
        if (!"RUN".equals(status)) {
            return DriftReport.DriftItem.builder()
                    .key(key)
                    .current(result.getScore())
                    .baseline(entry == null ? 0 : entry.getScore())
                    .ratio(Double.NaN)
                    .status(DriftReport.DriftStatus.SKIPPED)
                    .message("scenario not RUN: " + status)
                    .build();
        }
        if (entry == null) {
            return DriftReport.DriftItem.builder()
                    .key(key)
                    .current(result.getScore())
                    .baseline(0)
                    .ratio(Double.NaN)
                    .status(DriftReport.DriftStatus.NEW)
                    .message("no baseline, treat as NEW (WARN)")
                    .build();
        }
        if (entry.getScore() <= 0) {
            return DriftReport.DriftItem.builder()
                    .key(key)
                    .current(result.getScore())
                    .baseline(entry.getScore())
                    .ratio(Double.NaN)
                    .status(DriftReport.DriftStatus.NEW)
                    .message("baseline score <= 0, cannot compute ratio")
                    .build();
        }
        double ratio = result.getScore() / entry.getScore();
        double threshold = entry.getThreshold();
        boolean lowerIsBetter = isLatencyUnit(result.getUnit());
        boolean regression = lowerIsBetter ? ratio > 1.0 + threshold : ratio < 1.0 - threshold;
        boolean improvement = lowerIsBetter ? ratio < 1.0 - threshold : ratio > 1.0 + threshold;
        if (regression) {
            return DriftReport.DriftItem.builder()
                    .key(key)
                    .current(result.getScore())
                    .baseline(entry.getScore())
                    .ratio(ratio)
                    .status(DriftReport.DriftStatus.FAIL)
                    .message(String.format("regression: ratio=%.4f outside %.4f in worse direction",
                            ratio, lowerIsBetter ? 1.0 + threshold : 1.0 - threshold))
                    .build();
        }
        if (improvement) {
            return DriftReport.DriftItem.builder()
                    .key(key)
                    .current(result.getScore())
                    .baseline(entry.getScore())
                    .ratio(ratio)
                    .status(DriftReport.DriftStatus.WARN)
                    .message(String.format("improvement: ratio=%.4f above %.4f", ratio, 1.0 + threshold))
                    .build();
        }
        return DriftReport.DriftItem.builder()
                .key(key)
                .current(result.getScore())
                .baseline(entry.getScore())
                .ratio(ratio)
                .status(DriftReport.DriftStatus.PASS)
                .message(String.format("within threshold: ratio=%.4f", ratio))
                .build();
    }

    private boolean isLatencyUnit(String unit) {
        return unit != null && LATENCY_UNITS.contains(unit.toLowerCase(Locale.ROOT));
    }
}
