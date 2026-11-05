package com.consilens.sdk.result;

import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.diff.ColumnStats;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnStatsCalculatorTest {

    @Test
    void shouldCountInsertUpdateDeletePerColumn() {
        CompareRequest request = request(List.of("amount", "status"), List.of("amount", "status"), List.of());
        List<DiffRow> rows = List.of(
                modified("amount", "10.0", "11.0"),
                modified("status", "NEW", "OLD"),
                DiffRow.added(List.of(3), List.of("20.0", "NEW"), List.of("amount", "status")),
                DiffRow.removed(List.of(4), List.of("30.0", "OLD"), List.of("amount", "status")));
        DiffResult result = result(rows, 10, 8, 1, 1, 2);

        List<ColumnStats> stats = ColumnStatsCalculator.calculate(result, request, 1000);

        ColumnStats amount = find(stats, "amount");
        assertThat(amount.getInsertCount()).isEqualTo(1);
        assertThat(amount.getUpdateCount()).isEqualTo(1);
        assertThat(amount.getDeleteCount()).isEqualTo(1);
        assertThat(amount.getDifferenceCount()).isEqualTo(3);
        assertThat(amount.getDifferencePercentage()).isEqualTo(50.0);
        assertThat(amount.getDifferenceRate()).isEqualTo(16.6667);
        assertThat(amount.isAggregatedFromSamples()).isFalse();
        assertThat(amount.isTruncated()).isFalse();

        ColumnStats status = find(stats, "status");
        assertThat(status.getInsertCount()).isEqualTo(1);
        assertThat(status.getUpdateCount()).isEqualTo(1);
        assertThat(status.getDeleteCount()).isEqualTo(1);
        assertThat(status.getDifferenceCount()).isEqualTo(3);
    }

    @Test
    void shouldDeduplicateChangedColumnsAcrossMappedNames() {
        CompareRequest request = request(List.of("amount"), List.of("amt"), List.of());
        DiffRow row = DiffRow.modified(
                List.of(1),
                List.of("10.0"), List.of("11.0"),
                List.of("amount"), List.of("amt"),
                List.of("amount"), List.of("amt"));
        DiffResult result = result(List.of(row), 1, 1, 0, 0, 1);

        List<ColumnStats> stats = ColumnStatsCalculator.calculate(result, request, 1000);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).getName()).isEqualTo("amount");
        assertThat(stats.get(0).getTargetName()).isEqualTo("amt");
        assertThat(stats.get(0).getUpdateCount()).isEqualTo(1);
    }

    @Test
    void shouldFallBackToChangedColumnIndices() {
        CompareRequest request = request(List.of("id", "amount"), List.of("id", "amount"), List.of());
        List<String> columns = List.of("id", "amount");
        DiffRow row = DiffRow.modified(
                List.of(1),
                List.of(1, "10.0"), List.of(1, "11.0"),
                columns, columns,
                List.of(1));
        DiffResult result = result(List.of(row), 1, 1, 0, 0, 1);

        List<ColumnStats> stats = ColumnStatsCalculator.calculate(result, request, 1000);

        ColumnStats amount = find(stats, "amount");
        assertThat(amount.getUpdateCount()).isEqualTo(1);
        ColumnStats id = find(stats, "id");
        assertThat(id.getUpdateCount()).isZero();
    }

    @Test
    void shouldIgnoreExcludedColumns() {
        CompareRequest request = CompareRequest.builder()
                .sourceComparisons(ComparisonSpec.builder()
                        .fields(List.of("amount", "ignored")).exclude(List.of("ignored")).build())
                .targetComparisons(ComparisonSpec.builder()
                        .fields(List.of("amount", "ignored")).exclude(List.of("ignored")).build())
                .build();
        DiffRow row = DiffRow.added(List.of(1), List.of("10.0", "x"), List.of("amount", "ignored"));
        DiffResult result = result(List.of(row), 0, 1, 1, 0, 0);

        List<ColumnStats> stats = ColumnStatsCalculator.calculate(result, request, 1000);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).getName()).isEqualTo("amount");
        assertThat(stats.get(0).getInsertCount()).isEqualTo(1);
    }

    @Test
    void shouldMarkTruncatedWhenDifferenceLimitReached() {
        CompareRequest request = request(List.of("amount"), List.of("amount"), List.of());
        DiffRow row = modified("amount", "10.0", "11.0");
        DiffResult result = result(List.of(row), 1, 1, 0, 0, 1);

        List<ColumnStats> stats = ColumnStatsCalculator.calculate(result, request, 1);

        assertThat(stats.get(0).isTruncated()).isTrue();
    }

    private static CompareRequest request(List<String> sourceFields,
                                          List<String> targetFields,
                                          List<String> exclude) {
        return CompareRequest.builder()
                .sourceComparisons(ComparisonSpec.builder().fields(sourceFields).exclude(exclude).build())
                .targetComparisons(ComparisonSpec.builder().fields(targetFields).exclude(exclude).build())
                .build();
    }

    private static DiffRow modified(String column, String sourceValue, String targetValue) {
        List<String> columns = List.of("amount", "status");
        List<Object> sourceValues = "amount".equals(column)
                ? List.of(sourceValue, "NEW")
                : List.of("10.0", sourceValue);
        List<Object> targetValues = "amount".equals(column)
                ? List.of(targetValue, "NEW")
                : List.of("10.0", targetValue);
        return DiffRow.modified(
                List.of(1), sourceValues, targetValues,
                columns, columns,
                List.of(column), List.of(column));
    }

    private static DiffResult result(List<DiffRow> rows,
                                     long sourceRows,
                                     long targetRows,
                                     long sourceMissing,
                                     long targetMissing,
                                     long mismatches) {
        return DiffResult.builder()
                .differences(rows)
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(sourceRows)
                        .targetRowCount(targetRows)
                        .sourceMissingCount(sourceMissing)
                        .targetMissingCount(targetMissing)
                        .mismatchCount(mismatches)
                        .totalDifferences(sourceMissing + targetMissing + mismatches)
                        .build())
                .build();
    }

    private static ColumnStats find(List<ColumnStats> stats, String name) {
        return stats.stream()
                .filter(stat -> name.equalsIgnoreCase(stat.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing column stats for " + name));
    }
}
