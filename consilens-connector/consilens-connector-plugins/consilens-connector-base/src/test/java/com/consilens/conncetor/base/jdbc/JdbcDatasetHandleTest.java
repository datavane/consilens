package com.consilens.conncetor.base.jdbc;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.capability.ConnectorCapability;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.dataset.RelationalDatasetSupport;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.ResourceLocator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class JdbcDatasetHandleTest {

    @Test
    void shouldMapUsernameToJdbcUserProperty() {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("url", "jdbc:mysql://localhost:3306/test");
        connection.put("username", "app_user");
        connection.put("password", "secret");
        connection.put("useSSL", "false");

        Properties properties = JdbcDatasetHandle.buildConnectionProperties(connection, new Properties());

        assertEquals("app_user", properties.getProperty("user"));
        assertEquals("secret", properties.getProperty("password"));
        assertEquals("false", properties.getProperty("useSSL"));
        assertNull(properties.getProperty("username"));
    }

    @Test
    void shouldIgnoreUrlAndDriverProperties() {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("url", "jdbc:mysql://localhost:3306/test");
        connection.put("driver", "com.mysql.cj.jdbc.Driver");
        connection.put("driverClassName", "com.mysql.cj.jdbc.Driver");
        connection.put("user", "explicit_user");

        Properties properties = JdbcDatasetHandle.buildConnectionProperties(connection, new Properties());

        assertEquals("explicit_user", properties.getProperty("user"));
        assertNull(properties.getProperty("url"));
        assertNull(properties.getProperty("driver"));
        assertNull(properties.getProperty("driverClassName"));
    }

    @Test
    void shouldExposeRelationalSupportWithoutLeakingExecutionInputsToMetadata() {
        ReadOptions readOptions = ReadOptions.builder()
                .fetchSize(512)
                .build();

        JdbcDatasetHandle handle = new JdbcDatasetHandle(
                "mysql",
                DatabaseType.MYSQL,
                ignored -> stubDialect(),
                Map.of("url", "jdbc:mysql://localhost:3306/test"),
                ResourceLocator.builder().type("table").name("orders").build(),
                readOptions);

        assertTrue(handle.getSupport(RelationalDatasetSupport.class).isPresent());
        assertNull(handle.getMetadata().getAttributes().get("readOptions"));
        assertNull(handle.getMetadata().getAttributes().get("connection"));
        assertTrue(handle.getMetadata().getCapabilities().supports(ConnectorCapability.SERVER_SIDE_JOIN));
    }

    private DatabaseDialect stubDialect() {
        return (DatabaseDialect) Proxy.newProxyInstance(
                DatabaseDialect.class.getClassLoader(),
                new Class<?>[]{DatabaseDialect.class},
                (proxy, method, args) -> {
                    if ("getDatabaseType".equals(method.getName())) {
                        return DatabaseType.MYSQL;
                    }
                    return null;
                });
    }
}
