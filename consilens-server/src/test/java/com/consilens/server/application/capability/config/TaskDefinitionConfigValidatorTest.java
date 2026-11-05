package com.consilens.server.application.capability.config;

import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskDefinitionConfigValidatorTest {

    private final DataSourceRepository repository = mock(DataSourceRepository.class);
    private final TaskDefinitionConfigValidator validator = new TaskDefinitionConfigValidator(repository);

    private Map<String, Object> config(Object sourceId, String sourceType) {
        return Map.of(
                "source", Map.of("datasourceId", sourceId, "type", sourceType),
                "target", Map.of("datasourceId", 2L, "type", "mysql"));
    }

    @Test
    void requiresDatasourceIdOnBothSides() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateDatasourceRefs(Map.of(
                        "source", Map.of("type", "mysql"),
                        "target", Map.of("datasourceId", 2L))));
    }

    @Test
    void rejectsMissingDatasource() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateDatasourceRefs(config(1L, "mysql")));
    }

    @Test
    void rejectsTypeMismatch() {
        when(repository.findById(1L)).thenReturn(Optional.of(
                DataSourceRecord.builder().id(1L).name("ds").type("postgresql").build()));
        when(repository.findById(2L)).thenReturn(Optional.of(
                DataSourceRecord.builder().id(2L).name("ds2").type("mysql").build()));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateDatasourceRefs(config(1L, "mysql")));
    }

    @Test
    void acceptsMatchingRefs() {
        when(repository.findById(1L)).thenReturn(Optional.of(
                DataSourceRecord.builder().id(1L).name("ds").type("mysql").build()));
        when(repository.findById(2L)).thenReturn(Optional.of(
                DataSourceRecord.builder().id(2L).name("ds2").type("mysql").build()));
        validator.validateDatasourceRefs(config(1L, "mysql"));
    }
}
