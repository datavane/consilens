package com.consilens.connector.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnInfoTest {

    @Test
    void shouldPreserveTimestampTimezoneSemanticsFromMetadata() {
        assertEquals(DataType.TIMESTAMP, ColumnInfo.fromDatabaseMetadata(
                "created_at", "timestamp", 0, 0, 0, true, null).getType());
        assertEquals(DataType.TIMESTAMP_WITH_TIMEZONE, ColumnInfo.fromDatabaseMetadata(
                "created_at", "timestamptz", 0, 0, 0, true, null).getType());
    }
}
