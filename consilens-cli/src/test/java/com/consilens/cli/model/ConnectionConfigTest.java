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

    @Test
    void shouldValidateSqlResourceShapeAndTrustedSql() {
        ConnectionConfig validSelect = ConnectionConfig.builder()
                .type("mysql")
                .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                        .url("jdbc:mysql://localhost:3306/orders")
                        .username("root")
                        .password("secret")
                        .build())
                .resource(ConnectionConfig.ResourceConfig.builder()
                        .type("sql")
                        .path("WITH base AS (SELECT id FROM orders) SELECT id FROM base")
                        .build())
                .build();

        assertDoesNotThrow(() -> validSelect.validate("source"));

        ConnectionConfig missingPath = ConnectionConfig.builder()
                .type("mysql")
                .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                        .url("jdbc:mysql://localhost:3306/orders")
                        .username("root")
                        .password("secret")
                        .build())
                .resource(ConnectionConfig.ResourceConfig.builder()
                        .type("sql")
                        .build())
                .build();

        assertThrows(Exception.class, () -> missingPath.validate("source"));

        ConnectionConfig unsafeSql = ConnectionConfig.builder()
                .type("mysql")
                .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                        .url("jdbc:mysql://localhost:3306/orders")
                        .username("root")
                        .password("secret")
                        .build())
                .resource(ConnectionConfig.ResourceConfig.builder()
                        .type("sql")
                        .path("SELECT id FROM orders; DROP TABLE orders")
                        .build())
                .build();

        assertThrows(Exception.class, () -> unsafeSql.validate("source"));
    }

    @Test
    void shouldRequireJdbcUrlAndUsernameOnlyWhenJdbcUrlIsPresent() {
        ConnectionConfig jdbcWithoutUsername = ConnectionConfig.builder()
                .type("custom")
                .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                        .url("jdbc:custom://localhost/db")
                        .build())
                .resource(ConnectionConfig.ResourceConfig.builder()
                        .type("table")
                        .name("orders")
                        .build())
                .build();

        assertThrows(Exception.class, () -> jdbcWithoutUsername.validate("source"));

        ConnectionConfig nonJdbcConnection = ConnectionConfig.builder()
                .type("custom")
                .connection(ConnectionConfig.ConnectorConnectionProperties.builder().build())
                .resource(ConnectionConfig.ResourceConfig.builder()
                        .type("table")
                        .name("orders")
                        .build())
                .build();
        nonJdbcConnection.getConnection().addProperty("endpoint", "localhost:9000");

        assertDoesNotThrow(() -> nonJdbcConnection.validate("source"));
    }

    @Test
    void shouldRejectMissingConnectionForJdbcResource() {
        ConnectionConfig config = ConnectionConfig.builder()
                .type("mysql")
                .resource(ConnectionConfig.ResourceConfig.builder()
                        .type("table")
                        .name("orders")
                        .build())
                .build();

        assertThrows(Exception.class, () -> config.validate("source"));
    }
}
