package com.consilens.connector.mysql;

import com.consilens.connector.api.DataSourceField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MysqlDataSourceConfigBuilder.
 */
class MysqlDataSourceConfigBuilderTest {

    @Test
    void testBuildHasSixDefaultFields() {
        List<DataSourceField> fields = new MysqlDataSourceConfigBuilder().build();

        assertEquals(6, fields.size());
        assertEquals("host", fields.get(0).getField());
        assertEquals("properties", fields.get(5).getField());
    }

    @Test
    void testDatabaseIsOptional() {
        List<DataSourceField> fields = new MysqlDataSourceConfigBuilder().build();

        DataSourceField database = fields.stream()
                .filter(f -> "database".equals(f.getField()))
                .findFirst().orElseThrow();
        assertFalse(database.isRequired());
    }

    @Test
    void testHostAndUsernameStillRequired() {
        List<DataSourceField> fields = new MysqlDataSourceConfigBuilder().build();

        assertTrue(fields.stream().filter(f -> "host".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
        assertTrue(fields.stream().filter(f -> "username".equals(f.getField()))
                .findFirst().orElseThrow().isRequired());
    }

    @Test
    void testNoSchemaOrSidField() {
        List<DataSourceField> fields = new MysqlDataSourceConfigBuilder().build();

        assertTrue(fields.stream().noneMatch(f -> "schema".equals(f.getField())));
        assertTrue(fields.stream().noneMatch(f -> "sid".equals(f.getField())));
    }
}
