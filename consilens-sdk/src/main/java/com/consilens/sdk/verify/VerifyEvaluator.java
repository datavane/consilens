package com.consilens.sdk.verify;

import com.consilens.core.diff.ColumnStats;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Evaluates a {@link VerifyPolicy} against a completed diff result.
 * Deterministic by design: no sampling, no LLM, no hidden defaults.
 */
public final class VerifyEvaluator {

    private VerifyEvaluator() {
    }

    public static VerifyResult evaluate(DiffResult result, VerifyPolicy policy) {
        if (result == null) {
            return VerifyResult.builder()
                    .verdict(Verdict.ERROR)
                    .exitCode(Verdict.ERROR.exitCode())
                    .checks(List.of())
                    .message("compare runtime returned no result")
                    .build();
        }
        VerifyPolicy effective = policy == null ? VerifyPolicy.builder().build() : policy;
        List<PolicyCheck> checks = new ArrayList<>();
        boolean inconclusive = false;

        DiffResult.DiffStatistics statistics = result.getStatistics();
        long total = statistics != null ? statistics.getTotalDifferences() : safeCount(result);
        long mismatch = statistics != null ? statistics.getMismatchCount() : 0L;
        long sourceRows = statistics != null ? statistics.getSourceRowCount() : 0L;
        long targetRows = statistics != null ? statistics.getTargetRowCount() : 0L;
        FailOn failOn = effective.getFailOn() == null ? FailOn.ANY : effective.getFailOn();
        DeltaSummary delta = new DeltaSummary(0L, 0L, 0L);

        boolean hasBaseline = effective.getBaselineFile() != null;
        if (failOn == FailOn.NEW && !hasBaseline) {
            inconclusive = true;
            // passed=null: not evaluable, does not fail the gate by itself
            checks.add(PolicyCheck.builder()
                    .name("baseline")
                    .passed(null)
                    .message("failOn=new requires a baseline file")
                    .build());
        } else if (hasBaseline) {
            try {
                // 按身份出现次数比较（multiset）：重复键场景下"多出来的一行"才是新增差异
                Map<String, Integer> baselineIds = BaselineDiffReader.readCounts(
                        effective.getBaselineFile());
                Map<String, Integer> currentIds = new LinkedHashMap<>();
                for (DiffRow row : safeRows(result)) {
                    currentIds.merge(BaselineDiffReader.identityOf(row), 1, Integer::sum);
                }
                long newCount = 0L;
                long fixedCount = 0L;
                long persisting = 0L;
                for (Map.Entry<String, Integer> entry : currentIds.entrySet()) {
                    int baselineCount = baselineIds.getOrDefault(entry.getKey(), 0);
                    if (entry.getValue() > baselineCount) {
                        newCount += entry.getValue() - baselineCount;
                    }
                    persisting += Math.min(baselineCount, entry.getValue());
                }
                for (Map.Entry<String, Integer> entry : baselineIds.entrySet()) {
                    int currentCount = currentIds.getOrDefault(entry.getKey(), 0);
                    if (entry.getValue() > currentCount) {
                        fixedCount += entry.getValue() - currentCount;
                    }
                }
                delta = new DeltaSummary(newCount, fixedCount, persisting);
                // 显式给了 --max-new-differences 就必须生效，不能因为 failOn 不是 new 而被静默忽略
                if (failOn == FailOn.NEW || effective.getMaxNewDifferences() != null) {
                    long limit = effective.getMaxNewDifferences() != null
                            ? effective.getMaxNewDifferences() : 0L;
                    checks.add(check("maxNewDifferences", newCount <= limit, newCount, limit, null));
                }
            } catch (IOException exception) {
                inconclusive = true;
                checks.add(PolicyCheck.builder()
                        .name("baseline")
                        .passed(null)
                        .message("baseline file could not be read: " + exception.getMessage())
                        .build());
            }
        }
        if (failOn == FailOn.ANY) {
            checks.add(check("anyDifference", total == 0L, total, 0L, null));
        }
        // FailOn.NONE: never fail on differences; numeric checks below still apply.

        if (effective.getMaxMismatchRate() != null) {
            double rate = (sourceRows + targetRows) > 0
                    ? mismatch * 1.0 / (sourceRows + targetRows) : 0.0;
            checks.add(check("maxMismatchRate",
                    rate <= effective.getMaxMismatchRate() + 1e-9,
                    round4(rate), effective.getMaxMismatchRate(), null));
        }

        if (effective.getMaxRowCountDeltaPct() != null) {
            double maxRows = Math.max(sourceRows, targetRows);
            double pct = maxRows > 0 ? Math.abs(sourceRows - targetRows) * 100.0 / maxRows : 0.0;
            checks.add(check("maxRowCountDeltaPct",
                    pct <= effective.getMaxRowCountDeltaPct() + 1e-9,
                    round4(pct), effective.getMaxRowCountDeltaPct(), null));
        }

        if (effective.getRequiredColumns() != null && !effective.getRequiredColumns().isEmpty()) {
            Set<String> available = new TreeSet<>();
            for (ColumnStats columnStats : result.getColumnStats()) {
                if (columnStats.getName() != null) {
                    available.add(columnStats.getName().toLowerCase(java.util.Locale.ROOT));
                }
            }
            List<String> missing = new ArrayList<>();
            for (String required : effective.getRequiredColumns()) {
                if (required != null && !available.contains(required.toLowerCase(java.util.Locale.ROOT))) {
                    missing.add(required);
                }
            }
            checks.add(check("requiredColumns", missing.isEmpty(), missing,
                    effective.getRequiredColumns(),
                    missing.isEmpty() ? null : "columns missing from comparison: " + missing));
        }

        boolean failed = checks.stream().anyMatch(check -> check.getPassed() != null && !check.getPassed());
        Verdict verdict = failed
                ? Verdict.FAIL
                : inconclusive ? Verdict.INCONCLUSIVE : Verdict.PASS;
        String message = failedChecksMessage(checks);
        if (message == null && verdict == Verdict.INCONCLUSIVE) {
            // 不可评估时必须给出原因：否则报告里只有 0/0/0，读起来像"没有新增差异"
            message = inconclusiveMessage(checks);
        }

        return VerifyResult.builder()
                .verdict(verdict)
                .exitCode(verdict.exitCode())
                .summary(statistics)
                .delta(delta)
                .checks(checks)
                .withinToleranceDifferences(result.getWithinToleranceDifferences() == null
                        ? java.util.List.of() : result.getWithinToleranceDifferences())
                .message(message)
                .build();
    }

    private static PolicyCheck check(String name, boolean passed, Object actual, Object limit, String message) {
        return PolicyCheck.builder()
                .name(name)
                .passed(passed)
                .actual(actual)
                .limit(limit)
                .message(message)
                .build();
    }

    private static String failedChecksMessage(List<PolicyCheck> checks) {
        List<String> failed = new ArrayList<>();
        for (PolicyCheck check : checks) {
            if (check.getPassed() != null && !check.getPassed()) {
                failed.add(check.getName());
            }
        }
        return failed.isEmpty() ? null : "failed checks: " + String.join(", ", failed);
    }

    private static String inconclusiveMessage(List<PolicyCheck> checks) {
        List<String> reasons = new ArrayList<>();
        for (PolicyCheck check : checks) {
            if (check.getPassed() == null && check.getMessage() != null) {
                reasons.add(check.getName() + ": " + check.getMessage());
            }
        }
        return reasons.isEmpty() ? "not evaluable: no policy check produced a verdict"
                : "not evaluable: " + String.join("; ", reasons);
    }

    private static List<DiffRow> safeRows(DiffResult result) {
        List<DiffRow> rows = result.getDifferences();
        return rows == null ? List.of() : rows;
    }

    private static long safeCount(DiffResult result) {
        List<DiffRow> rows = result.getDifferences();
        return rows == null ? 0L : rows.size();
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
