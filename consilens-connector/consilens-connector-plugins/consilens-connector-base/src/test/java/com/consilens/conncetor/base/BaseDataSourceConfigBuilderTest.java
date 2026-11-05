package com.consilens.conncetor.base;

import com.consilens.connector.api.DataSourceField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for BaseDataSourceConfigBuilder: the generic 6-field template.
 */
class BaseDataSourceConfigBuilderTest {

    @Test
    void testBuildHasSixDefaultFieldsInOrder() {
        List<DataSourceField> fields = new BaseDataSourceConfigBuilder().build();

        assertEquals(6, fields.size());
        assertEquals("host", fields.get(0).getField());
        assertEquals("port", fields.get(1).getField());
        assertEquals("database", fields.get(2).getField());
        assertEquals("username", fields.get(3).getField());
        assertEquals("password", fields.get(4).getField());
        assertEquals("properties", fields.get(5).getField());
    }

    @Test
    void testRequiredFlags() {
        List<DataSourceField> fields = new BaseDataSourceConfigBuilder().build();

        assertTrue(field(fields, "host").isRequired());
        assertTrue(field(fields, "port").isRequired());
        assertTrue(field(fields, "database").isRequired());
        assertTrue(field(fields, "username").isRequired());
        assertFalse(field(fields, "password").isRequired());
        assertFalse(field(fields, "properties").isRequired());
    }

    @Test
    void testFieldTypes() {
        List<DataSourceField> fields = new BaseDataSourceConfigBuilder().build();

        assertEquals("input", field(fields, "host").getType());
        assertEquals("number", field(fields, "port").getType());
        assertEquals("textarea", field(fields, "properties").getType());
    }

    @Test
    void testPropertiesTextareaRowsAndPlaceholder() {
        DataSourceField properties = field(new BaseDataSourceConfigBuilder().build(), "properties");

        assertEquals(3, properties.getRows());
        assertEquals("key=value&key2=value2", properties.getPlaceholder());
    }

    @Test
    void testNoSchemaOrSidByDefault() {
        List<DataSourceField> fields = new BaseDataSourceConfigBuilder().build();

        assertTrue(fields.stream().noneMatch(f -> "schema".equals(f.getField())));
        assertTrue(fields.stream().noneMatch(f -> "sid".equals(f.getField())));
    }

    private static DataSourceField field(List<DataSourceField> fields, String name) {
        return fields.stream()
                .filter(f -> name.equals(f.getField()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing field: " + name));
    }
}
