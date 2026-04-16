package com.consilens.cli.service;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.core.compare.registry.DefaultConnectorRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorProviderDiscoveryTest {

    private final DefaultConnectorRegistry registry = new DefaultConnectorRegistry();

    @Test
    void shouldDiscoverJdbcConnectorProvidersFromPluginModules() {
        assertTrue(registry.findProvider("mysql").isPresent());
        assertTrue(registry.findProvider("postgresql").isPresent());
        assertTrue(registry.findProvider("sqlserver").isPresent());
        assertTrue(registry.findProvider("oracle").isPresent());
        assertTrue(registry.findProvider("presto").isPresent());
        assertTrue(registry.findProvider("trino").isPresent());
        assertTrue(registry.findProvider("doris").isPresent());
        assertTrue(registry.findProvider("starrocks").isPresent());
        assertTrue(registry.findProvider("clickhouse").isPresent());
        assertTrue(registry.findProvider("tidb").isPresent());
    }

    @Test
    void shouldResolveJdbcProviderFromPluginsWithoutHardcodedRegistryMapping() {
        ConnectorConfig config = ConnectorConfig.builder()
                .connection(Map.of(
                        "url", "jdbc:mysql://localhost:3306/orders",
                        "username", "root",
                        "password", "secret"))
                .resource(ResourceLocator.builder()
                        .type("table")
                        .name("orders")
                        .build())
                .build();

        ConnectorAdapter adapter = registry.create(config);

        assertNotNull(adapter);
        assertEquals("mysql", adapter.getType());
    }
}
