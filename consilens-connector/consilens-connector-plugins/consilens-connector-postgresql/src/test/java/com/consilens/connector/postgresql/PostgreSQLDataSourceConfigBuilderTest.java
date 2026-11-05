package com.consilens.connector.postgresql;

import com.consilens.connector.api.DataSourceField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for PostgreSQLDataSourceConfigBuilder.
 */
class PostgreSQLDataSourceConfigBuilderTest {

    @Test
    void testBuildHasSevenFields() {
        List<DataSourceField> fields = new PostgreSQLDataSourceConfigBuilder().build();

        assertEquals(7, fields.size());
        assertEquals("host", fields.get(0).getField());
        assertEquals("database", fields.get(2).getField());
        assertEquals("schema", fields.get(3).getField());
        assertEquals("username", fields.get(4).getField());
    }

    @Test
    void testSchemaRequiredWithPublicDefault() {
        List<DataSourceField> fields = new PostgreSQLDataSourceConfigBuilder().build();

        DataSourceField schema = fields.stream()
                .filter(f -> "schema".equals(f.getField()))
                .findFirst().orElseThrow();
        assertTrue(schema.isRequired());
        assertEquals("public", schema.getDefaultValue());
        assertEquals("input", schema.getType());
    }

    @Test
    void testDatabaseStillRequired() {
        List<DataSourceField> fields = new PostgreSQLDataSourceConfigBuilder().build();

        assertTrue(fields.stream().filter(f -> "database".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
    }
}
