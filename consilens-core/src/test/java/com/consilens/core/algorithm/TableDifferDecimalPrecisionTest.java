package com.consilens.core.algorithm;

import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.TablePath;
import com.consilens.core.segment.TableSegment;
import com.consilens.connector.api.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-1：精度告警必须跟随配置的 normalization precision，而不是写死 4；
 * 否则用户把 precision 调到 8 之后仍会收到"超过 4 位会判等"的误导告警。
 */
class TableDifferDecimalPrecisionTest {

    @Test
    void warnsOnlyForColumnsBeyondConfiguredPrecision() {
        TableSegment table = tableWithDecimalScale(6);

        assertTrue(TableDiffer.decimalColumnsBeyondScale(table, 4).contains("amount(scale=6)"));
        assertTrue(TableDiffer.decimalColumnsBeyondScale(table, 6).isEmpty());
        assertTrue(TableDiffer.decimalColumnsBeyondScale(table, 8).isEmpty());
    }

    @Test
    void ignoresNonDecimalColumnsRegardlessOfScale() {
        TableSegment table = TableSegment.builder()
                .tablePath(TablePath.of("db", "t"))
                .keyColumns(List.of("id"))
                .extraColumns(List.of("name"))
                .schema(Optional.of(schema(
                        column("id", DataType.BIGINT, 0),
                        column("name", DataType.VARCHAR, 20))))
                .build();

        assertTrue(TableDiffer.decimalColumnsBeyondScale(table, 0).isEmpty());
    }

    @Test
    void handlesMissingSchema() {
        TableSegment table = TableSegment.builder()
                .tablePath(TablePath.of("db", "t"))
                .keyColumns(List.of("id"))
                .extraColumns(List.of())
                .build();

        assertEquals(List.of(), TableDiffer.decimalColumnsBeyondScale(table, 4));
        assertEquals(List.of(), TableDiffer.decimalColumnsBeyondScale(null, 4));
    }

    private TableSegment tableWithDecimalScale(int scale) {
        return TableSegment.builder()
                .tablePath(TablePath.of("db", "t"))
                .keyColumns(List.of("id"))
                .extraColumns(List.of("amount"))
                .schema(Optional.of(schema(
                        column("id", DataType.BIGINT, 0),
                        column("amount", DataType.DECIMAL, scale))))
                .build();
    }

    private TableSchema schema(ColumnInfo... columns) {
        java.util.Map<String, ColumnInfo> byName = new java.util.LinkedHashMap<>();
        for (ColumnInfo column : columns) {
            byName.put(column.getName(), column);
        }
        return new TableSchema(TablePath.of("db", "t"), byName);
    }

    private ColumnInfo column(String name, DataType type, int scale) {
        return ColumnInfo.builder()
                .name(name)
                .type(type)
                .nullable(true)
                .ordinalPosition(1)
                .precision(Optional.empty())
                .scale(type == DataType.DECIMAL ? Optional.of(scale) : Optional.empty())
                .maxLength(Optional.empty())
                .defaultValue(Optional.empty())
                .collation(Optional.empty())
                .primaryKey(false)
                .uniqueKey(false)
                .comment(Optional.empty())
                .build();
    }
}
