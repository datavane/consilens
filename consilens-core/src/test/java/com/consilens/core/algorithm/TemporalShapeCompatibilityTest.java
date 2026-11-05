package com.consilens.core.algorithm;

import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalShapeCompatibilityTest {

    @Test
    void flagsDateVersusTimestampPair() {
        TableSegment left = table(column("created_at", DataType.DATE));
        TableSegment right = table(column("created_at", DataType.TIMESTAMP));

        List<String> mismatches = TemporalShapeCompatibility.mismatchedTemporalColumns(left, right);

        assertEquals(List.of("created_at(DATE↔TIMESTAMP)"), mismatches);
    }

    @Test
    void flagsTimestampVersusDateInEitherDirection() {
        TableSegment left = table(column("day", DataType.TIMESTAMP_WITH_TIMEZONE));
        TableSegment right = table(column("day", DataType.DATE));

        List<String> mismatches = TemporalShapeCompatibility.mismatchedTemporalColumns(left, right);
        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0).startsWith("day("), mismatches.get(0));
        assertTrue(mismatches.get(0).contains("↔"), mismatches.get(0));
    }

    @Test
    void ignoresMatchingTemporalShapes() {
        assertEquals(List.of(), TemporalShapeCompatibility.mismatchedTemporalColumns(
                table(column("d1", DataType.DATE)), table(column("d1", DataType.DATE))));
        assertEquals(List.of(), TemporalShapeCompatibility.mismatchedTemporalColumns(
                table(column("ts", DataType.DATETIME)), table(column("ts", DataType.TIMESTAMP))));
        assertEquals(List.of(), TemporalShapeCompatibility.mismatchedTemporalColumns(
                table(column("t", DataType.TIME)), table(column("t", DataType.DATE))));
    }

    @Test
    void onlyComparesColumnsPresentOnBothSides() {
        TableSegment left = table(column("created_at", DataType.DATE));
        TableSegment right = table(column("other", DataType.TIMESTAMP));

        assertEquals(List.of(), TemporalShapeCompatibility.mismatchedTemporalColumns(left, right));
    }

    @Test
    void handlesMissingSchema() {
        TableSegment noSchema = TableSegment.builder()
                .tablePath(TablePath.of("db", "t"))
                .keyColumns(List.of("id"))
                .extraColumns(List.of())
                .build();

        assertEquals(List.of(), TemporalShapeCompatibility.mismatchedTemporalColumns(noSchema, noSchema));
        assertEquals(List.of(), TemporalShapeCompatibility.mismatchedTemporalColumns(null, noSchema));
    }

    private TableSegment table(ColumnInfo column) {
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        columns.put("id", column("id", DataType.BIGINT));
        columns.put(column.getName(), column);
        return TableSegment.builder()
                .tablePath(TablePath.of("db", "t"))
                .keyColumns(List.of("id"))
                .extraColumns(List.of(column.getName()))
                .schema(Optional.of(new TableSchema(TablePath.of("db", "t"), columns)))
                .build();
    }

    private ColumnInfo column(String name, DataType type) {
        return ColumnInfo.builder()
                .name(name)
                .type(type)
                .nullable(true)
                .ordinalPosition(1)
                .precision(Optional.empty())
                .scale(Optional.empty())
                .maxLength(Optional.empty())
                .defaultValue(Optional.empty())
                .collation(Optional.empty())
                .primaryKey(false)
                .uniqueKey(false)
                .comment(Optional.empty())
                .build();
    }
}
