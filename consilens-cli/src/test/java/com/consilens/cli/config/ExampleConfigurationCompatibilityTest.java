package com.consilens.cli.config;

import com.consilens.cli.model.CliConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExampleConfigurationCompatibilityTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "examples/minimal-mysql-to-pg.yaml",
            "examples/same-db-mysql-comparison.yaml",
            "examples/large-table-mysql-to-starrocks.yaml",
            "examples/performance-test-mysql-vs-postgres.yaml",
            "examples/performance-test-mysql-vs-starrocks.yaml",
            "examples/custom-sql-mysql-vs-postgres-checksum.yaml",
            "examples/realtime-table-mysql-vs-postgres-loop.yaml",
            "examples/realtime-custom-sql-mysql-vs-postgres-checksum.yaml",
            "examples/mysql-to-doris-partitioned-checksum.yaml",
            "examples/detail-to-aggregate-custom-sql.yaml",
            "examples/performance-test-mysql-vs-postgres.json"
    })
    void shouldLoadExampleConfigurations(String relativePath) throws Exception {
        ConfigurationManager configurationManager = new ConfigurationManager(testEnvironment());
        Path configPath = Paths.get("..", relativePath).toAbsolutePath().normalize();

        CliConfiguration config = configurationManager.loadConfiguration(configPath.toString(), false);

        assertNotNull(config);
        assertNotNull(config.getSource());
        assertNotNull(config.getTarget());
        assertNotNull(config.getSource().getResource());
        assertNotNull(config.getTarget().getResource());
        assertNotNull(config.getComparison());
        assertNotNull(config.getComparison().getKeys());
    }

    private Map<String, String> testEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("MYSQL_USER", "test_user");
        env.put("MYSQL_PASSWORD", "test_password");
        env.put("PG_USER", "test_user");
        env.put("PG_PASSWORD", "test_password");
        env.put("STARROCKS_USER", "test_user");
        env.put("STARROCKS_PASSWORD", "test_password");
        env.put("DORIS_USER", "test_user");
        env.put("DORIS_PASSWORD", "test_password");
        return env;
    }
}
