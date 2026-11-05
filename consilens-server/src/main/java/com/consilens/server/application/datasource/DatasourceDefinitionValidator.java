package com.consilens.server.application.datasource;

import com.consilens.connector.api.DataSourceField;
import com.consilens.server.support.security.SensitiveValueGuard;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Definition-level validation before any datasource write: the type must have
 * a registered dialect and every schema-required field must be present
 * (sensitive required fields are only enforced on create, since updates may
 * keep the existing credential by leaving it blank).
 */
@Component
public class DatasourceDefinitionValidator {

    private final DialectSupport dialectSupport;

    public DatasourceDefinitionValidator(DialectSupport dialectSupport) {
        this.dialectSupport = dialectSupport;
    }

    public void validateDefinition(String type, Map<String, Object> param, boolean isCreate) {
        if (dialectSupport.find(type).isEmpty()) {
            throw new IllegalArgumentException("unsupported datasource type: " + type);
        }
        List<DataSourceField> fields = dialectSupport.find(type)
                .map(d -> d.getDataSourceConfigBuilder() == null
                        ? new com.consilens.conncetor.base.BaseDataSourceConfigBuilder().build()
                        : d.getDataSourceConfigBuilder().build())
                .orElse(List.of());
        for (DataSourceField field : fields) {
            if (!field.isRequired()) {
                continue;
            }
            boolean sensitive = field.isSensitive() || SensitiveValueGuard.isSensitiveName(field.getField());
            if (sensitive && !isCreate) {
                continue;
            }
            Object value = param == null ? null : param.get(field.getField());
            if (value == null || String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("param." + field.getField() + " is required");
            }
        }
    }
}
