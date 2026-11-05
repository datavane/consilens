package com.consilens.connector.api.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableSchemaTest {

    @Test
    void shouldResolveColumnNamesCaseInsensitively() {
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        columns.put("ORDER_ID", ColumnInfo.builder()
                .name("ORDER_ID")
                .type(DataType.BIGINT)
                .nullable(true)
                .ordinalPosition(1)
                .build());
        TableSchema schema = TableSchema.of(TablePath.of("db.orders"), columns);

        assertTrue(schema.getColumn("order_id").isPresent());
        assertTrue(schema.getColumn("ORDER_ID").isPresent());
        assertEquals(DataType.BIGINT, schema.getColumnType("order_id"));
    }
}
