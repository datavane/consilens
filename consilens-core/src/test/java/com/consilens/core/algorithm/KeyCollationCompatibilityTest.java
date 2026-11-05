package com.consilens.core.algorithm;

import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KeyCollationCompatibilityTest {

    @Test
    void detectsEnginesWithCaseInsensitiveDefaults() {
        assertTrue(KeyCollationCompatibility.engineDefaultsToCaseInsensitiveCollation("mysql"));
        assertTrue(KeyCollationCompatibility.engineDefaultsToCaseInsensitiveCollation("MySQL"));
        assertTrue(KeyCollationCompatibility.engineDefaultsToCaseInsensitiveCollation("tidb"));
        assertTrue(KeyCollationCompatibility.engineDefaultsToCaseInsensitiveCollation("sqlserver"));
        assertFalse(KeyCollationCompatibility.engineDefaultsToCaseInsensitiveCollation("postgresql"));
        assertFalse(KeyCollationCompatibility.engineDefaultsToCaseInsensitiveCollation("oracle"));
        assertFalse(KeyCollationCompatibility.engineDefaultsToCaseInsensitiveCollation("clickhouse"));
        assertFalse(KeyCollationCompatibility.engineDefaultsToCaseInsensitiveCollation(null));
        assertFalse(KeyCollationCompatibility.engineDefaultsToCaseInsensitiveCollation("  "));
    }

    @Test
    void classifiesCollationNames() {
        assertTrue(KeyCollationCompatibility.isCaseInsensitiveCollation("utf8mb4_0900_ai_ci"));
        assertTrue(KeyCollationCompatibility.isCaseInsensitiveCollation("SQL_Latin1_General_CP1_CI_AS"));
        assertFalse(KeyCollationCompatibility.isCaseInsensitiveCollation("utf8mb4_bin"));
        assertTrue(KeyCollationCompatibility.isBinaryCollation("utf8mb4_bin"));
        assertTrue(KeyCollationCompatibility.isBinaryCollation("Latin1_General_BIN2"));
        assertTrue(KeyCollationCompatibility.isBinaryCollation("C_CS_AS"));
        assertFalse(KeyCollationCompatibility.isBinaryCollation("utf8mb4_general_ci"));
    }

    @Test
    void collectsOnlyStringKeyColumns() {
        TableSegment segment = segment("mysql",
                column("id", DataType.VARCHAR, Optional.empty()),
                column("tenant", DataType.INTEGER, Optional.empty()),
                column("name", DataType.VARCHAR, Optional.empty()));

        assertEquals(List.of("id"), KeyCollationCompatibility.stringKeyColumns(segment));
    }

    @Test
    void treatsExplicitBinaryCollationAsSafe() {
        TableSegment segment = segment("mysql",
                column("id", DataType.VARCHAR, Optional.of("utf8mb4_bin")));

        assertTrue(KeyCollationCompatibility.columnCollationIsBinary(segment, "id"));
        assertFalse(KeyCollationCompatibility.columnCollationIsCaseInsensitive(segment, "id"));
    }

    private TableSegment segment(String connectorType, ColumnInfo... columns) {
        Map<String, ColumnInfo> columnMap = new java.util.LinkedHashMap<>();
        for (ColumnInfo column : columns) {
            columnMap.put(column.getName(), column);
        }
        DatabaseAdapter adapter = mock(DatabaseAdapter.class);
        when(adapter.getConnectorType()).thenReturn(connectorType);
        TablePath path = TablePath.of("orders");
        return TableSegment.builder()
                .tablePath(path)
                .database(adapter)
                .keyColumns(List.of("id"))
                .extraColumns(List.of("name"))
                .schema(Optional.of(TableSchema.of(path, columnMap)))
                .build();
    }

    private ColumnInfo column(String name, DataType type, Optional<String> collation) {
        return ColumnInfo.builder()
                .name(name)
                .type(type)
                .nullable(true)
                .ordinalPosition(1)
                .precision(Optional.empty())
                .scale(Optional.empty())
                .maxLength(Optional.empty())
                .defaultValue(Optional.empty())
                .collation(collation)
                .primaryKey(false)
                .uniqueKey(false)
                .comment(Optional.empty())
                .build();
    }
}
