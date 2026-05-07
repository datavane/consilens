package com.consilens.cli.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                .resource(ConnectionConfig.ResourceConfig.builder()
                        .type("table")
                        .name("orders")
                        .build())
                .build();

        assertDoesNotThrow(() -> config.validate("source"));
    }

    @Test
    void shouldNotRequireJdbcValidationBasedOnConnectorType() {
        ConnectionConfig.ConnectorConnectionProperties properties = ConnectionConfig.ConnectorConnectionProperties.builder()
                .build();
        properties.addProperty("host", "localhost");
        properties.addProperty("database", "orders");

        ConnectionConfig config = ConnectionConfig.builder()
                .type("mysql")
                .connection(properties)
                .resource(ConnectionConfig.ResourceConfig.builder()
                        .type("table")
                        .name("orders")
                        .build())
                .build();

        assertDoesNotThrow(() -> config.validate("source"));
    }

    @Test
    void shouldExposeConnectionPropertiesFromNestedConnectionBlock() {
        ConnectionConfig.ConnectorConnectionProperties properties = ConnectionConfig.ConnectorConnectionProperties.builder()
                .url("jdbc:mysql://localhost:3306/orders")
                .username("connector-user")
                .password("secret")
                .build();
        properties.addProperty("sslMode", "DISABLED");

        ConnectionConfig config = ConnectionConfig.builder()
                .type("mysql")
                .connection(properties)
                .build();

        Map<String, Object> connectionMap = config.toConnectionMap();

        assertEquals("jdbc:mysql://localhost:3306/orders", connectionMap.get("url"));
        assertEquals("connector-user", connectionMap.get("username"));
        assertEquals("secret", connectionMap.get("password"));
        assertEquals("DISABLED", connectionMap.get("sslMode"));
        assertEquals("jdbc:mysql://localhost:3306/orders", config.getUrl());
        assertEquals("connector-user", config.getUsername());
        assertEquals("secret", config.getPassword());
    }

    @Test
    void shouldRejectPathForTableResource() {
        ConnectionConfig config = ConnectionConfig.builder()
                .type("mysql")
                .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                        .url("jdbc:mysql://localhost:3306/orders")
                        .username("root")
                        .password("secret")
                        .build())
                .resource(ConnectionConfig.ResourceConfig.builder()
                        .type("table")
                        .name("orders")
                        .path("orders")
                        .build())
                .build();

        assertThrows(Exception.class, () -> config.validate("source"));
    }
}
