package com.consilens.connector.sqlserver;

import com.consilens.connector.api.DataSourceField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SqlServerDataSourceConfigBuilder.
 */
class SqlServerDataSourceConfigBuilderTest {

    @Test
    void testBuildHasSevenFields() {
        List<DataSourceField> fields = new SqlServerDataSourceConfigBuilder().build();

        assertEquals(7, fields.size());
        assertEquals("schema", fields.get(3).getField());
    }

    @Test
    void testSchemaRequiredWithDboDefault() {
        List<DataSourceField> fields = new SqlServerDataSourceConfigBuilder().build();

        DataSourceField schema = fields.stream()
                .filter(f -> "schema".equals(f.getField()))
                .findFirst().orElseThrow();
        assertTrue(schema.isRequired());
        assertEquals("dbo", schema.getDefaultValue());
        assertEquals("input", schema.getType());
    }

    @Test
    void testDatabaseStillRequired() {
        List<DataSourceField> fields = new SqlServerDataSourceConfigBuilder().build();

        assertTrue(fields.stream().filter(f -> "database".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
    }
}
