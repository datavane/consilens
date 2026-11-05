package com.consilens.sdk.result;

import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.diff.ColumnStats;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Computes exact per-column insert/update/delete counts from a completed
 * {@link DiffResult}. This is a post-processor: it does not change any comparison
 * algorithm and works for checksum, join and streaming results because all of them
 * populate {@link DiffResult#getDifferences()}.
 */
public final class ColumnStatsCalculator {

    private static final long DEFAULT_MAX_DIFFERENCES = 1_000_000L;

    private ColumnStatsCalculator() {
    }

    public static List<ColumnStats> calculate(DiffResult result,
                                              CompareRequest request,
                                              long maxDifferences) {
        if (result == null) {
            return List.of();
        }
        List<MutableColumn> columns = resolveColumns(request);
        if (columns.isEmpty()) {
            columns = deriveColumns(result);
        }
        if (columns.isEmpty()) {
            return List.of();
        }

        long mismatchRows = 0L;
        long sourceOnlyRows = 0L;
        long targetOnlyRows = 0L;
        for (DiffRow row : safeRows(result)) {
            switch (row.getOperation()) {
                case MISMATCH:
                    mismatchRows++;
                    accumulateUpdates(columns, row);
                    break;
                case SOURCE_MISSING:
                    sourceOnlyRows++;
                    for (MutableColumn column : columns) {
                        column.insertCount++;
                    }
                    break;
                case TARGET_MISSING:
                    targetOnlyRows++;
                    for (MutableColumn column : columns) {
                        column.deleteCount++;
                    }
                    break;
                default:
                    break;
            }
        }

        long max = maxDifferences > 0 ? maxDifferences : DEFAULT_MAX_DIFFERENCES;
        // `>=` 是有意的保守取值：命中上限时无法证明"没有更多"，因此对外标记"可能不完整"。
        // 代价是"恰好 N 条差异且 N == maxDifferences"也会标成截断（见 ColumnStatsCalculatorTest
        // 的 shouldMarkTruncatedWhenDifferenceLimitReached）——这是偏保守的选择，不是 bug。
        boolean truncated = result.getDifferenceCount() >= max;
        long totalRows = totalRows(result, mismatchRows, sourceOnlyRows, targetOnlyRows);

        List<ColumnStats> stats = new ArrayList<>(columns.size());
        for (MutableColumn column : columns) {
            long differenceCount = column.insertCount + column.updateCount + column.deleteCount;
            stats.add(ColumnStats.builder()
                    .name(column.name())
                    .sourceName(column.sourceName)
                    .targetName(column.targetName)
                    .insertCount(column.insertCount)
                    .updateCount(column.updateCount)
                    .deleteCount(column.deleteCount)
                    .differenceCount(differenceCount)
                    .differencePercentage(mismatchRows == 0
                            ? 0.0
                            : round1(column.updateCount * 100.0 / mismatchRows))
                    .differenceRate(totalRows == 0
                            ? 0.0
                            : round4(differenceCount * 100.0 / totalRows))
                    .aggregatedFromSamples(false)
                    .truncated(truncated)
                    .build());
        }
        stats.sort((left, right) -> {
            int byCount = Long.compare(right.getDifferenceCount(), left.getDifferenceCount());
            return byCount != 0 ? byCount : left.getName().compareToIgnoreCase(right.getName());
        });
        return stats;
    }

    private static void accumulateUpdates(List<MutableColumn> columns, DiffRow row) {
        Set<MutableColumn> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : changedColumns(row, true)) {
            MutableColumn column = find(columns, name);
            if (column != null) {
                touched.add(column);
            }
        }
        for (String name : changedColumns(row, false)) {
            MutableColumn column = find(columns, name);
            if (column != null) {
                touched.add(column);
            }
        }
        for (MutableColumn column : touched) {
            column.updateCount++;
        }
    }

    private static List<MutableColumn> resolveColumns(CompareRequest request) {
        if (request == null) {
            return List.of();
        }
        List<String> sourceFields = fields(request.getSourceComparisons());
        List<String> targetFields = fields(request.getTargetComparisons());
        if (sourceFields.isEmpty() && targetFields.isEmpty()) {
            return List.of();
        }
        Set<String> excluded = excludedNames(request.getSourceComparisons(), request.getTargetComparisons());

        Map<String, MutableColumn> columns = new LinkedHashMap<>();
        int size = Math.max(sourceFields.size(), targetFields.size());
        for (int index = 0; index < size; index++) {
            String source = index < sourceFields.size() ? sourceFields.get(index) : null;
            String target = index < targetFields.size() ? targetFields.get(index) : null;
            if ((source != null && isExcluded(source, excluded))
                    || (target != null && isExcluded(target, excluded))) {
                continue;
            }
            MutableColumn column = new MutableColumn(source, target);
            columns.putIfAbsent(column.key(), column);
        }
        return new ArrayList<>(columns.values());
    }

    private static List<MutableColumn> deriveColumns(DiffResult result) {
        Map<String, MutableColumn> columns = new LinkedHashMap<>();
        for (DiffRow row : safeRows(result)) {
            for (String name : row.resolvedColumnNames()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                MutableColumn column = new MutableColumn(name, null);
                columns.putIfAbsent(column.key(), column);
            }
        }
        return new ArrayList<>(columns.values());
    }

    private static List<String> fields(ComparisonSpec spec) {
        if (spec == null || spec.getFields() == null || spec.getFields().isEmpty()) {
            return List.of();
        }
        return spec.getFields();
    }

    private static Set<String> excludedNames(ComparisonSpec source, ComparisonSpec target) {
        Set<String> excluded = new LinkedHashSet<>();
        addExcluded(excluded, source);
        addExcluded(excluded, target);
        return excluded;
    }

    private static void addExcluded(Set<String> target, ComparisonSpec spec) {
        if (spec == null || spec.getExclude() == null) {
            return;
        }
        for (String name : spec.getExclude()) {
            if (name != null && !name.isBlank()) {
                target.add(name.toLowerCase(Locale.ROOT));
            }
        }
    }

    private static boolean isExcluded(String name, Set<String> excluded) {
        return excluded.contains(name.toLowerCase(Locale.ROOT));
    }

    private static List<String> changedColumns(DiffRow row, boolean sourceSide) {
        List<String> changed = sourceSide ? row.getChangedColumns1() : row.getChangedColumns2();
        if (changed != null && !changed.isEmpty()) {
            return changed;
        }
        Object rawIndices = row.getMetadata() == null ? null : row.getMetadata().get("changedColumnIndices");
        if (!(rawIndices instanceof List)) {
            return List.of();
        }
        List<String> names = sourceSide ? row.getColumnNames1() : row.getColumnNames2();
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object rawIndex : (List<?>) rawIndices) {
            if (rawIndex instanceof Number) {
                int index = ((Number) rawIndex).intValue();
                if (index >= 0 && index < names.size()) {
                    result.add(names.get(index));
                }
            }
        }
        return result;
    }

    private static MutableColumn find(List<MutableColumn> columns, String name) {
        if (name == null) {
            return null;
        }
        for (MutableColumn column : columns) {
            if (column.matches(name)) {
                return column;
            }
        }
        return null;
    }

    private static long totalRows(DiffResult result,
                                  long mismatchRows,
                                  long sourceOnlyRows,
                                  long targetOnlyRows) {
        DiffResult.DiffStatistics statistics = result.getStatistics();
        if (statistics != null && statistics.getSourceRowCount() + statistics.getTargetRowCount() > 0) {
            return statistics.getSourceRowCount() + statistics.getTargetRowCount();
        }
        return mismatchRows + sourceOnlyRows + targetOnlyRows;
    }

    private static List<DiffRow> safeRows(DiffResult result) {
        List<DiffRow> rows = result.getDifferences();
        return rows == null ? List.of() : rows;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static final class MutableColumn {
        private final String sourceName;
        private final String targetName;
        private long insertCount;
        private long updateCount;
        private long deleteCount;

        private MutableColumn(String sourceName, String targetName) {
            this.sourceName = blankToNull(sourceName);
            this.targetName = blankToNull(targetName);
        }

        private String name() {
            if (sourceName != null) {
                return sourceName;
            }
            if (targetName != null) {
                return targetName;
            }
            return "";
        }

        private String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        private boolean matches(String candidate) {
            return equalsIgnoreCase(sourceName, candidate)
                    || equalsIgnoreCase(targetName, candidate)
                    || equalsIgnoreCase(name(), candidate);
        }

        private static boolean equalsIgnoreCase(String left, String right) {
            return left != null && right != null && left.equalsIgnoreCase(right);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
