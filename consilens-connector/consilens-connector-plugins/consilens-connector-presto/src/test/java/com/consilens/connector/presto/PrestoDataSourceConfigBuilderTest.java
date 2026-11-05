package com.consilens.connector.presto;

import com.consilens.connector.api.DataSourceField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for PrestoDataSourceConfigBuilder.
 */
class PrestoDataSourceConfigBuilderTest {

    @Test
    void testBuildHasSixDefaultFields() {
        List<DataSourceField> fields = new PrestoDataSourceConfigBuilder().build();

        assertEquals(6, fields.size());
        assertEquals("host", fields.get(0).getField());
        assertEquals("properties", fields.get(5).getField());
    }

    @Test
    void testDatabaseAndPasswordOptional() {
        List<DataSourceField> fields = new PrestoDataSourceConfigBuilder().build();

        assertFalse(fields.stream().filter(f -> "database".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
        assertFalse(fields.stream().filter(f -> "password".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
    }

    @Test
    void testHostAndUsernameStillRequired() {
        List<DataSourceField> fields = new PrestoDataSourceConfigBuilder().build();

        assertTrue(fields.stream().filter(f -> "host".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
        assertTrue(fields.stream().filter(f -> "username".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
    }
}
