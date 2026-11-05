package com.consilens.connector.oracle;

import com.consilens.connector.api.DataSourceField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for OracleDataSourceConfigBuilder: sid-based template without
 * database/schema fields.
 */
class OracleDataSourceConfigBuilderTest {

    @Test
    void testBuildHasSixFieldsWithoutDatabase() {
        List<DataSourceField> fields = new OracleDataSourceConfigBuilder().build();

        assertEquals(6, fields.size());
        assertEquals("host", fields.get(0).getField());
        assertEquals("port", fields.get(1).getField());
        assertEquals("sid", fields.get(2).getField());
        assertEquals("username", fields.get(3).getField());
        assertEquals("password", fields.get(4).getField());
        assertEquals("properties", fields.get(5).getField());
    }

    @Test
    void testNoDatabaseOrSchemaField() {
        List<DataSourceField> fields = new OracleDataSourceConfigBuilder().build();

        assertTrue(fields.stream().noneMatch(f -> "database".equals(f.getField())));
        assertTrue(fields.stream().noneMatch(f -> "schema".equals(f.getField())));
    }

    @Test
    void testSidRequiredWithTitle() {
        List<DataSourceField> fields = new OracleDataSourceConfigBuilder().build();

        DataSourceField sid = fields.stream()
                .filter(f -> "sid".equals(f.getField()))
                .findFirst().orElseThrow();
        assertTrue(sid.isRequired());
        assertEquals("sid", sid.getTitle());
        assertEquals("input", sid.getType());
    }

    @Test
    void testPasswordOptional() {
        List<DataSourceField> fields = new OracleDataSourceConfigBuilder().build();

        assertFalse(fields.stream().filter(f -> "password".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
    }
}
