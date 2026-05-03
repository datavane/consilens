package com.consilens.cli.config;

import com.consilens.cli.model.CliConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadJsonConfigurationFile() throws Exception {
        Path configFile = tempDir.resolve("diff-config.json");
        Files.writeString(configFile, "{\n" +
                "  \"source\": {\n" +
                "    \"type\": \"mysql\",\n" +
                "    \"url\": \"jdbc:mysql://localhost:3306/test\",\n" +
                "    \"username\": \"root\",\n" +
                "    \"password\": \"123456\"\n" +
                "  },\n" +
                "  \"target\": {\n" +
                "    \"type\": \"postgresql\",\n" +
                "    \"url\": \"jdbc:postgresql://localhost:5432/postgres?currentSchema=public\",\n" +
                "    \"username\": \"postgres\",\n" +
                "    \"password\": \"123456\"\n" +
                "  },\n" +
                "  \"comparison\": {\n" +
                "    \"tables\": {\n" +
                "      \"source\": \"performance_test_table\",\n" +
                "      \"target\": \"performance_test_table\"\n" +
                "    },\n" +
                "    \"keys\": {\n" +
                "      \"source\": [\"record_id\"],\n" +
                "      \"target\": [\"record_id\"]\n" +
                "    }\n" +
                "  },\n" +
                "  \"strategy\": {\n" +
                "    \"mode\": \"checksum\",\n" +
                "    \"algorithm\": \"xor\",\n" +
                "    \"bisectionFactor\": 8,\n" +
                "    \"bisectionThreshold\": 8000,\n" +
                "    \"batchSize\": 1000,\n" +
                "    \"enableProfiling\": true\n" +
                "  },\n" +
                "  \"concurrency\": {\n" +
                "    \"io\": {\n" +
                "      \"core\": 8,\n" +
                "      \"max\": 32,\n" +
                "      \"queueSize\": 10000,\n" +
                "      \"keepAliveSeconds\": 60,\n" +
                "      \"threadNamePrefix\": \"consilens-io-\"\n" +
                "    },\n" +
                "    \"cpu\": {\n" +
                "      \"core\": 4,\n" +
                "      \"max\": 8,\n" +
                "      \"queueSize\": 10000,\n" +
                "      \"keepAliveSeconds\": 60,\n" +
                "      \"threadNamePrefix\": \"consilens-cpu-\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"result\": {\n" +
                "    \"sinks\": [\n" +
                "      {\n" +
                "        \"format\": \"table\",\n" +
                "        \"type\": \"result\",\n" +
                "        \"properties\": {\n" +
                "          \"type\": \"mysql\",\n" +
                "          \"url\": \"jdbc:mysql://localhost:3306/consilens\",\n" +
                "          \"tableName\": \"dv_job_execution_result\",\n" +
                "          \"createTable\": true,\n" +
                "          \"columns\": [\n" +
                "            {\n" +
                "              \"name\": \"expected_value\",\n" +
                "              \"columnType\": \"decimal(38,4)\",\n" +
                "              \"defaultValue\": \"${sourceRowCount}\"\n" +
                "            }\n" +
                "          ]\n" +
                "        }\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}\n");

        ConfigurationManager configurationManager = new ConfigurationManager();

        CliConfiguration config = configurationManager.loadConfiguration(configFile.toString(), false);

        assertNotNull(config);
        assertEquals("xor", config.getAlgorithm());
        assertEquals("performance_test_table", config.getComparison().getTables().getSource());
        assertEquals(1, config.getResult().getSinks().size());
        assertNotNull(config.getResult().getSinks().get(0).getProperties());
        assertEquals("json", configurationManager.detectFormat(configFile.toString()));
    }

    @Test
    void shouldResolveEnvironmentPlaceholdersInJsonConfiguration() throws Exception {
        Path configFile = tempDir.resolve("diff-config-env.json");
        Files.writeString(configFile, "{\n" +
                "  \"source\": {\n" +
                "    \"type\": \"mysql\",\n" +
                "    \"url\": \"${env.SOURCE_URL}\",\n" +
                "    \"username\": \"${env.DB_USER}\",\n" +
                "    \"password\": \"${env.DB_PASSWORD}\"\n" +
                "  },\n" +
                "  \"target\": {\n" +
                "    \"type\": \"postgresql\",\n" +
                "    \"url\": \"${env.TARGET_URL}\",\n" +
                "    \"username\": \"${env.DB_USER}\",\n" +
                "    \"password\": \"${env.DB_PASSWORD}\"\n" +
                "  },\n" +
                "  \"comparison\": {\n" +
                "    \"tables\": {\n" +
                "      \"source\": \"${env.TABLE_NAME}\",\n" +
                "      \"target\": \"${env.TABLE_NAME}\"\n" +
                "    },\n" +
                "    \"keys\": {\n" +
                "      \"source\": [\"record_id\"],\n" +
                "      \"target\": [\"record_id\"]\n" +
                "    }\n" +
                "  },\n" +
                "  \"strategy\": {\n" +
                "    \"mode\": \"checksum\",\n" +
                "    \"algorithm\": \"xor\",\n" +
                "    \"bisectionFactor\": 8,\n" +
                "    \"bisectionThreshold\": 8000,\n" +
                "    \"batchSize\": 1000,\n" +
                "    \"enableProfiling\": true\n" +
                "  },\n" +
                "  \"concurrency\": {\n" +
                "    \"io\": {\n" +
                "      \"core\": 8,\n" +
                "      \"max\": 32,\n" +
                "      \"queueSize\": 10000,\n" +
                "      \"keepAliveSeconds\": 60,\n" +
                "      \"threadNamePrefix\": \"consilens-io-\"\n" +
                "    },\n" +
                "    \"cpu\": {\n" +
                "      \"core\": 4,\n" +
                "      \"max\": 8,\n" +
                "      \"queueSize\": 10000,\n" +
                "      \"keepAliveSeconds\": 60,\n" +
                "      \"threadNamePrefix\": \"consilens-cpu-\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"result\": {\n" +
                "    \"sinks\": [\n" +
                "      {\n" +
                "        \"format\": \"table\",\n" +
                "        \"type\": \"result\",\n" +
                "        \"properties\": {\n" +
                "          \"type\": \"mysql\",\n" +
                "          \"url\": \"jdbc:mysql://localhost:3306/consilens\",\n" +
                "          \"tableName\": \"${env.RESULT_TABLE:dv_job_execution_result}\",\n" +
                "          \"createTable\": true,\n" +
                "          \"columns\": [\n" +
                "            {\n" +
                "              \"name\": \"expected_value\",\n" +
                "              \"columnType\": \"decimal(38,4)\",\n" +
                "              \"defaultValue\": \"${sourceRowCount}\"\n" +
                "            }\n" +
                "          ]\n" +
                "        }\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}\n");

        ConfigurationManager configurationManager = new ConfigurationManager(Map.of(
                "SOURCE_URL", "jdbc:mysql://localhost:3306/test",
                "TARGET_URL", "jdbc:postgresql://localhost:5432/postgres?currentSchema=public",
                "DB_USER", "root",
                "DB_PASSWORD", "123456",
                "TABLE_NAME", "performance_test_table"));

        CliConfiguration config = configurationManager.loadConfiguration(configFile.toString(), false);

        assertEquals("jdbc:mysql://localhost:3306/test", config.getSource().getUrl());
        assertEquals("performance_test_table", config.getComparison().getTables().getSource());
        assertEquals("root", config.getSource().getUsername());
        assertTrue(config.getResult().getSinks().get(0).getProperties().contains("dv_job_execution_result"));
        assertTrue(config.getResult().getSinks().get(0).getProperties().contains("${sourceRowCount}"));
    }

    @Test
    void shouldResolveEnvironmentPlaceholdersInYamlConfiguration() throws Exception {
        Path configFile = tempDir.resolve("diff-config-env.yaml");
        Files.writeString(configFile, "source:\n" +
                "  type: mysql\n" +
                "  url: ${env.SOURCE_URL}\n" +
                "  username: ${env.DB_USER}\n" +
                "  password: ${env.DB_PASSWORD}\n" +
                "target:\n" +
                "  type: postgresql\n" +
                "  url: ${env.TARGET_URL}\n" +
                "  username: ${env.DB_USER}\n" +
                "  password: ${env.DB_PASSWORD}\n" +
                "comparison:\n" +
                "  tables:\n" +
                "    source: ${env.TABLE_NAME}\n" +
                "    target: ${env.TABLE_NAME}\n" +
                "  keys:\n" +
                "    source:\n" +
                "      - record_id\n" +
                "    target:\n" +
                "      - record_id\n" +
                "strategy:\n" +
                "  mode: checksum\n" +
                "  algorithm: xor\n" +
                "concurrency:\n" +
                "  io:\n" +
                "    core: 8\n" +
                "    max: 32\n" +
                "    queueSize: 10000\n" +
                "    keepAliveSeconds: 60\n" +
                "    threadNamePrefix: consilens-io-\n" +
                "  cpu:\n" +
                "    core: 4\n" +
                "    max: 8\n" +
                "    queueSize: 10000\n" +
                "    keepAliveSeconds: 60\n" +
                "    threadNamePrefix: consilens-cpu-\n");

        ConfigurationManager configurationManager = new ConfigurationManager(Map.of(
                "SOURCE_URL", "jdbc:mysql://localhost:3306/test",
                "TARGET_URL", "jdbc:postgresql://localhost:5432/postgres?currentSchema=public",
                "DB_USER", "root",
                "DB_PASSWORD", "123456",
                "TABLE_NAME", "performance_test_table"));

        CliConfiguration config = configurationManager.loadConfiguration(configFile.toString(), false);

        assertEquals("jdbc:postgresql://localhost:5432/postgres?currentSchema=public", config.getTarget().getUrl());
        assertEquals("performance_test_table", config.getComparison().getTables().getTarget());
    }

    @Test
    void shouldFailWhenRequiredEnvironmentVariableIsMissing() throws Exception {
        Path configFile = tempDir.resolve("diff-config-missing-env.yaml");
        Files.writeString(configFile, "source:\n" +
                "  type: mysql\n" +
                "  url: ${env.SOURCE_URL}\n" +
                "  username: root\n" +
                "  password: 123456\n" +
                "target:\n" +
                "  type: postgresql\n" +
                "  url: jdbc:postgresql://localhost:5432/postgres?currentSchema=public\n" +
                "  username: postgres\n" +
                "  password: 123456\n" +
                "comparison:\n" +
                "  tables:\n" +
                "    source: performance_test_table\n" +
                "    target: performance_test_table\n" +
                "  keys:\n" +
                "    source:\n" +
                "      - record_id\n" +
                "    target:\n" +
                "      - record_id\n" +
                "strategy:\n" +
                "  mode: checksum\n" +
                "  algorithm: xor\n");

        ConfigurationManager configurationManager = new ConfigurationManager(Map.of());

        ConfigurationException exception = assertThrows(ConfigurationException.class,
                () -> configurationManager.loadConfiguration(configFile.toString(), false));

        assertEquals("Missing required environment variable: SOURCE_URL", exception.getMessage());
    }

    @Test
    void shouldRejectUnsupportedTableSinkDatabase() throws Exception {
        Path configFile = tempDir.resolve("diff-config-unsupported-sink.yaml");
        Files.writeString(configFile, "source:\n" +
                "  type: mysql\n" +
                "  url: jdbc:mysql://localhost:3306/test\n" +
                "  username: root\n" +
                "  password: 123456\n" +
                "target:\n" +
                "  type: postgresql\n" +
                "  url: jdbc:postgresql://localhost:5432/postgres?currentSchema=public\n" +
                "  username: postgres\n" +
                "  password: 123456\n" +
                "comparison:\n" +
                "  tables:\n" +
                "    source: performance_test_table\n" +
                "    target: performance_test_table\n" +
                "  keys:\n" +
                "    source:\n" +
                "      - record_id\n" +
                "    target:\n" +
                "      - record_id\n" +
                "strategy:\n" +
                "  mode: checksum\n" +
                "  algorithm: xor\n" +
                "result:\n" +
                "  sinks:\n" +
                "    - format: table\n" +
                "      type: result\n" +
                "      properties:\n" +
                "        type: oracle\n" +
                "        url: jdbc:oracle:thin:@localhost:1521/xe\n" +
                "        tableName: diff_result\n");

        ConfigurationManager configurationManager = new ConfigurationManager();

        ConfigurationException exception = assertThrows(ConfigurationException.class,
                () -> configurationManager.loadConfiguration(configFile.toString(), false));

        assertTrue(exception.getMessage().contains("properties.type must be mysql or postgresql"));
    }

    @Test
    void shouldRejectTableSinkWithoutExplicitDatabaseType() throws Exception {
        Path configFile = tempDir.resolve("diff-config-missing-sink-type.yaml");
        Files.writeString(configFile, "source:\n" +
                "  type: mysql\n" +
                "  url: jdbc:mysql://localhost:3306/test\n" +
                "  username: root\n" +
                "  password: 123456\n" +
                "target:\n" +
                "  type: postgresql\n" +
                "  url: jdbc:postgresql://localhost:5432/postgres?currentSchema=public\n" +
                "  username: postgres\n" +
                "  password: 123456\n" +
                "comparison:\n" +
                "  tables:\n" +
                "    source: performance_test_table\n" +
                "    target: performance_test_table\n" +
                "  keys:\n" +
                "    source:\n" +
                "      - record_id\n" +
                "    target:\n" +
                "      - record_id\n" +
                "strategy:\n" +
                "  mode: checksum\n" +
                "  algorithm: xor\n" +
                "result:\n" +
                "  sinks:\n" +
                "    - format: table\n" +
                "      type: result\n" +
                "      properties:\n" +
                "        url: jdbc:mysql://localhost:3306/test\n" +
                "        tableName: diff_result\n");

        ConfigurationManager configurationManager = new ConfigurationManager();

        ConfigurationException exception = assertThrows(ConfigurationException.class,
                () -> configurationManager.loadConfiguration(configFile.toString(), false));

        assertTrue(exception.getMessage().contains("properties.type is required"));
    }
}
