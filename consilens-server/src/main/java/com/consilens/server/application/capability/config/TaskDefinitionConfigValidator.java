package com.consilens.server.application.capability.config;

import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Config-level validation before a task definition write: both endpoints must
 * reference existing datasources via datasourceId (no inline connections) and
 * the declared endpoint type, when present, must match the datasource type.
 *
 * <p>任务定义与 CLI 配置同构（{@code source/target} + {@code resource}），数据源引用允许写在
 * endpoint 上（{@code source.datasourceId}），也允许写在 CLI 风格的
 * {@code source.connection.datasourceId} 里。
 */
@Component
public class TaskDefinitionConfigValidator {

    private final DataSourceRepository dataSourceRepository;

    public TaskDefinitionConfigValidator(DataSourceRepository dataSourceRepository) {
        this.dataSourceRepository = dataSourceRepository;
    }

    @SuppressWarnings("unchecked")
    public void validateDatasourceRefs(Map<String, Object> config) {
        validateSide(config.get("source"), "source");
        validateSide(config.get("target"), "target");
    }

    @SuppressWarnings("unchecked")
    private void validateSide(Object raw, String side) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("config." + side + " endpoint is required");
        }
        Map<String, Object> endpoint = (Map<String, Object>) raw;
        Object id = endpoint.get("datasourceId");
        if (id == null || String.valueOf(id).isBlank()) {
            Object connection = endpoint.get("connection");
            if (connection instanceof Map) {
                id = ((Map<String, Object>) connection).get("datasourceId");
            }
        }
        if (id == null || String.valueOf(id).isBlank()) {
            throw new IllegalArgumentException(
                    "config." + side + ".datasourceId is required"
                            + "（可写在 endpoint 上，或 CLI 风格的 " + side + ".connection.datasourceId）");
        }
        Long datasourceId;
        try {
            datasourceId = Long.valueOf(String.valueOf(id));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("config." + side + ".datasourceId is not a valid id");
        }
        DataSourceRecord datasource = dataSourceRepository.findById(datasourceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "config." + side + " references missing datasource: " + datasourceId));
        Object type = endpoint.get("type");
        if (type != null && !String.valueOf(type).isBlank()
                && !datasource.getType().equals(String.valueOf(type))) {
            throw new IllegalArgumentException(
                    "config." + side + ".type does not match datasource type");
        }
    }
}
