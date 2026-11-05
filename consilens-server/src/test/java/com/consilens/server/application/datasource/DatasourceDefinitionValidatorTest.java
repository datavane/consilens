package com.consilens.server.application.datasource;

import com.consilens.connector.api.DataSourceConfigBuilder;
import com.consilens.connector.api.DataSourceField;
import com.consilens.connector.api.DatabaseDialect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasourceDefinitionValidatorTest {

    private final DialectSupport dialectSupport = mock(DialectSupport.class);
    private final DatasourceDefinitionValidator validator = new DatasourceDefinitionValidator(dialectSupport);

    private void stubDialect(List<DataSourceField> fields) {
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        DataSourceConfigBuilder builder = mock(DataSourceConfigBuilder.class);
        when(builder.build()).thenReturn(fields);
        when(dialect.getDataSourceConfigBuilder()).thenReturn(builder);
        when(dialectSupport.find("mysql")).thenReturn(Optional.of(dialect));
    }

    @Test
    void rejectsUnknownType() {
        when(dialectSupport.find("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateDefinition("nope", Map.of("host", "h"), true));
    }

    @Test
    void requiresSchemaRequiredFieldsOnCreate() {
        stubDialect(List.of(
                field("host", true, false),
                field("password", true, true)));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateDefinition("mysql", Map.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateDefinition("mysql", Map.of("host", "h"), true));
        // Sensitive required fields are enforced on create.
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateDefinition("mysql", Map.of("host", "h"), true));
    }

    @Test
    void updateMayKeepSensitiveFieldsBlank() {
        stubDialect(List.of(
                field("host", true, false),
                field("password", true, true)));
        // Update with host but no password is allowed; missing host is not.
        validator.validateDefinition("mysql", Map.of("host", "h"), false);
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateDefinition("mysql", Map.of(), false));
    }

    private static DataSourceField field(String name, boolean required, boolean sensitive) {
        return DataSourceField.builder().field(name).title(name).type("input")
                .required(required).sensitive(sensitive).build();
    }
}
