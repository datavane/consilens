package com.consilens.cli.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ConnectionConfigTest {

    @Test
    void shouldAllowNonJdbcConnectorValidationWhenConnectionMapIsPresent() {
        ConnectionConfig.ConnectorConnectionProperties properties = ConnectionConfig.ConnectorConnectionProperties.builder()
                .build();
        properties.addProperty("uri", "mongodb://localhost:27017");
        properties.addProperty("database", "orders");

        ConnectionConfig config = ConnectionConfig.builder()
                .type("mongodb")
                .connection(properties)
                .build();

        assertDoesNotThrow(() -> config.validate("source"));
    }

    @Test
    void shouldMergeGenericConnectionPropertiesWithTopLevelFields() {
        ConnectionConfig.ConnectorConnectionProperties properties = ConnectionConfig.ConnectorConnectionProperties.builder()
                .url("jdbc:mysql://localhost:3306/orders")
                .username("connector-user")
                .build();
        properties.addProperty("sslMode", "DISABLED");

        ConnectionConfig config = ConnectionConfig.builder()
                .type("mysql")
                .username("top-level-user")
                .connection(properties)
                .build();

        Map<String, Object> connectionMap = config.toConnectionMap();

        assertEquals("jdbc:mysql://localhost:3306/orders", connectionMap.get("url"));
        assertEquals("top-level-user", connectionMap.get("username"));
        assertEquals("DISABLED", connectionMap.get("sslMode"));
    }
}
