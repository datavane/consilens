package com.consilens.cli.service;

import com.consilens.cli.model.ConnectionConfig;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.model.ResourceLocator;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ConnectorConfigMapper {

    private ConnectorConfigMapper() {
    }

    public static ConnectorConfig toConnectorConfig(ConnectionConfig connectionConfig, String tableName) {
        return ConnectorConfig.builder()
                .type(connectionConfig.getType())
                .name(connectionConfig.getName())
                .connection(connectionConfig.toConnectionMap())
                .resource(toResourceLocator(connectionConfig, tableName))
                .readOptions(toReadOptions(connectionConfig.getReadOptions()))
                .build();
    }

    public static ResourceLocator toResourceLocator(ConnectionConfig connectionConfig, String tableName) {
        if (connectionConfig.getResource() != null) {
            return ResourceLocator.builder()
                    .type(connectionConfig.getResource().getType())
                    .name(connectionConfig.getResource().getName())
                    .path(connectionConfig.getResource().getPath())
                    .options(connectionConfig.getResource().getOptions())
                    .build();
        }
        return ResourceLocator.builder()
                .type("table")
                .name(tableName)
                .build();
    }

    public static ReadOptions toReadOptions(Map<String, Object> readOptions) {
        if (readOptions == null || readOptions.isEmpty()) {
            return null;
        }

        Map<String, Object> options = new LinkedHashMap<>(readOptions);
        String consistency = stringValue(options.remove("consistency"));
        Integer batchSize = integerValue(options.remove("batchSize"));
        Integer fetchSize = integerValue(options.remove("fetchSize"));

        return ReadOptions.builder()
                .consistency(consistency)
                .batchSize(batchSize)
                .fetchSize(fetchSize)
                .options(options.isEmpty() ? null : options)
                .build();
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Integer.parseInt(((String) value).trim());
        }
        return null;
    }
}
