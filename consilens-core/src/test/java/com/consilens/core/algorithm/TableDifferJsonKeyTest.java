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

class TableDifferJsonKeyTest {

    @Test
    void flagsJsonAndJsonbKeyColumns() {
        TableSegment table = table(column("payload", DataType.JSON), column("meta", DataType.JSONB));

        assertEquals(List.of("payload", "meta"), TableDiffer.jsonKeyColumns(table));
    }

    @Test
    void ignoresJsonColumnsThatAreNotKeys() {
        TableSegment table = TableSegment.builder()
                .tablePath(TablePath.of("db", "t"))
                .keyColumns(List.of("id"))
                .extraColumns(List.of("payload"))
                .schema(Optional.of(schema(column("id", DataType.BIGINT), column("payload", DataType.JSON))))
                .build();

        assertTrue(TableDiffer.jsonKeyColumns(table).isEmpty());
    }

    @Test
    void handlesMissingSchema() {
        TableSegment table = TableSegment.builder()
                .tablePath(TablePath.of("db", "t"))
                .keyColumns(List.of("id"))
                .extraColumns(List.of())
                .build();

        assertTrue(TableDiffer.jsonKeyColumns(table).isEmpty());
        assertTrue(TableDiffer.jsonKeyColumns(null).isEmpty());
    }

    private TableSegment table(ColumnInfo... keys) {
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        columns.put("id", column("id", DataType.BIGINT));
        List<String> keyNames = new java.util.ArrayList<>();
        for (ColumnInfo key : keys) {
            columns.put(key.getName(), key);
            keyNames.add(key.getName());
        }
        return TableSegment.builder()
                .tablePath(TablePath.of("db", "t"))
                .keyColumns(keyNames)
                .extraColumns(List.of())
                .schema(Optional.of(new TableSchema(TablePath.of("db", "t"), columns)))
                .build();
    }

    private TableSchema schema(ColumnInfo... columns) {
        Map<String, ColumnInfo> byName = new LinkedHashMap<>();
        for (ColumnInfo column : columns) {
            byName.put(column.getName(), column);
        }
        return new TableSchema(TablePath.of("db", "t"), byName);
    }

    private ColumnInfo column(String name, DataType type) {
        return ColumnInfo.builder()
                .name(name)
                .type(type)
                .nullable(false)
                .ordinalPosition(1)
                .precision(Optional.empty())
                .scale(Optional.empty())
                .maxLength(Optional.empty())
                .defaultValue(Optional.empty())
                .collation(Optional.empty())
                .primaryKey(true)
                .uniqueKey(false)
                .comment(Optional.empty())
                .build();
    }
}
