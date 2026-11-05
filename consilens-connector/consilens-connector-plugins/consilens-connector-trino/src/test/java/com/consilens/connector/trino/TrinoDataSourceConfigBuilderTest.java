package com.consilens.connector.trino;

import com.consilens.connector.api.DataSourceField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for TrinoDataSourceConfigBuilder.
 */
class TrinoDataSourceConfigBuilderTest {

    @Test
    void testBuildHasSixDefaultFields() {
        List<DataSourceField> fields = new TrinoDataSourceConfigBuilder().build();

        assertEquals(6, fields.size());
        assertEquals("host", fields.get(0).getField());
        assertEquals("properties", fields.get(5).getField());
    }

    @Test
    void testDatabaseAndPasswordOptional() {
        List<DataSourceField> fields = new TrinoDataSourceConfigBuilder().build();

        assertFalse(fields.stream().filter(f -> "database".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
        assertFalse(fields.stream().filter(f -> "password".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
    }

    @Test
    void testHostAndUsernameStillRequired() {
        List<DataSourceField> fields = new TrinoDataSourceConfigBuilder().build();

        assertTrue(fields.stream().filter(f -> "host".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
        assertTrue(fields.stream().filter(f -> "username".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
    }
}
