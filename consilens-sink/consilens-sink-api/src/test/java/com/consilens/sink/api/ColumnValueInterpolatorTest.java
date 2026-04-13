package com.consilens.sink.api;

import com.consilens.connector.api.model.TablePath;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.ColumnMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnValueInterpolatorTest {

    @Test
    void shouldResolvePlaceholderInDefaultValueForResultContext() {
        ColumnMapping field = new ColumnMapping();
        field.setName("expected_value");
        field.setDefaultValue("${sourceRowCount}");

        DiffContext context = DiffContext.builder()
                .taskId("task-1")
                .sourceTablePath(TablePath.of("test", "performance_test_table"))
                .targetTablePath(TablePath.of("public", "performance_test_table"))
                .strategy("checksum")
                .algorithm("xor")
                .build();

        DiffResult result = DiffResult.builder()
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(2000000L)
                        .targetRowCount(2000000L)
                        .sourceMissingCount(0L)
                        .targetMissingCount(0L)
                        .mismatchCount(1L)
                        .processingTimeMs(0L)
                        .unchangedCount(1999999L)
                        .differencePercentage(0.0D)
                        .build())
                .build();

        String resolved = ColumnValueInterpolator.resolveField(field, context, result);

        assertEquals("2000000", resolved);
    }

    @Test
    void shouldFallbackToInterpolatedDefaultValueWhenValueResolvesToEmpty() {
        ColumnMapping field = new ColumnMapping();
        field.setName("expected_value");
        field.setValue("${attr.missing}");
        field.setDefaultValue("${sourceRowCount}");

        DiffContext context = DiffContext.builder()
                .taskId("task-2")
                .build();

        DiffResult result = DiffResult.builder()
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(128L)
                        .targetRowCount(128L)
                        .sourceMissingCount(0L)
                        .targetMissingCount(0L)
                        .mismatchCount(0L)
                        .processingTimeMs(0L)
                        .unchangedCount(128L)
                        .differencePercentage(0.0D)
                        .build())
                .build();

        String resolved = ColumnValueInterpolator.resolveField(field, context, result);

        assertEquals("128", resolved);
    }
}
