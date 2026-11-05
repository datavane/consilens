package com.consilens.cli.config;

import com.consilens.cli.model.CliConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                "    \"connection\": {\n" +
                "      \"url\": \"jdbc:mysql://localhost:3306/test\",\n" +
                "      \"username\": \"root\",\n" +
                "      \"password\": \"123456\"\n" +
                "    },\n" +
                "    \"resource\": {\n" +
                "      \"type\": \"table\",\n" +
                "      \"name\": \"performance_test_table\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"target\": {\n" +
                "    \"type\": \"postgresql\",\n" +
                "    \"connection\": {\n" +
                "      \"url\": \"jdbc:postgresql://localhost:5432/postgres?currentSchema=public\",\n" +
                "      \"username\": \"postgres\",\n" +
                "      \"password\": \"123456\"\n" +
                "    },\n" +
                "    \"resource\": {\n" +
                "      \"type\": \"table\",\n" +
                "      \"name\": \"performance_test_table\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"comparison\": {\n" +
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
                "    \"batchSize\": 1000\n" +
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
        assertEquals("performance_test_table", config.getSource().getResource().getName());
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
                "    \"connection\": {\n" +
                "      \"url\": \"${env.SOURCE_URL}\",\n" +
                "      \"username\": \"${env.DB_USER}\",\n" +
                "      \"password\": \"${env.DB_PASSWORD}\"\n" +
                "    },\n" +
                "    \"resource\": {\n" +
                "      \"type\": \"table\",\n" +
                "      \"name\": \"${env.TABLE_NAME}\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"target\": {\n" +
                "    \"type\": \"postgresql\",\n" +
                "    \"connection\": {\n" +
                "      \"url\": \"${env.TARGET_URL}\",\n" +
                "      \"username\": \"${env.DB_USER}\",\n" +
                "      \"password\": \"${env.DB_PASSWORD}\"\n" +
                "    },\n" +
                "    \"resource\": {\n" +
                "      \"type\": \"table\",\n" +
                "      \"name\": \"${env.TABLE_NAME}\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"comparison\": {\n" +
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
                "    \"batchSize\": 1000\n" +
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
        assertEquals("performance_test_table", config.getSource().getResource().getName());
        assertEquals("root", config.getSource().getUsername());
        assertTrue(config.getResult().getSinks().get(0).getProperties().contains("dv_job_execution_result"));
        assertTrue(config.getResult().getSinks().get(0).getProperties().contains("${sourceRowCount}"));
    }

    @Test
    void shouldResolveEnvironmentPlaceholdersInYamlConfiguration() throws Exception {
        Path configFile = tempDir.resolve("diff-config-env.yaml");
        Files.writeString(configFile, "source:\n" +
                "  type: mysql\n" +
                "  connection:\n" +
                "    url: ${env.SOURCE_URL}\n" +
                "    username: ${env.DB_USER}\n" +
                "    password: ${env.DB_PASSWORD}\n" +
                "  resource:\n" +
                "    type: table\n" +
                "    name: ${env.TABLE_NAME}\n" +
                "target:\n" +
                "  type: postgresql\n" +
                "  connection:\n" +
                "    url: ${env.TARGET_URL}\n" +
                "    username: ${env.DB_USER}\n" +
                "    password: ${env.DB_PASSWORD}\n" +
                "  resource:\n" +
                "    type: table\n" +
                "    name: ${env.TABLE_NAME}\n" +
                "comparison:\n" +
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
        assertEquals("performance_test_table", config.getTarget().getResource().getName());
    }

    @Test
    void shouldFailWhenRequiredEnvironmentVariableIsMissing() throws Exception {
        Path configFile = tempDir.resolve("diff-config-missing-env.yaml");
        Files.writeString(configFile, "source:\n" +
                "  type: mysql\n" +
                "  connection:\n" +
                "    url: ${env.SOURCE_URL}\n" +
                "    username: root\n" +
                "    password: 123456\n" +
                "  resource:\n" +
                "    type: table\n" +
                "    name: performance_test_table\n" +
                "target:\n" +
                "  type: postgresql\n" +
                "  connection:\n" +
                "    url: jdbc:postgresql://localhost:5432/postgres?currentSchema=public\n" +
                "    username: postgres\n" +
                "    password: 123456\n" +
                "  resource:\n" +
                "    type: table\n" +
                "    name: performance_test_table\n" +
                "comparison:\n" +
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
                "  connection:\n" +
                "    url: jdbc:mysql://localhost:3306/test\n" +
                "    username: root\n" +
                "    password: 123456\n" +
                "  resource:\n" +
                "    type: table\n" +
                "    name: performance_test_table\n" +
                "target:\n" +
                "  type: postgresql\n" +
                "  connection:\n" +
                "    url: jdbc:postgresql://localhost:5432/postgres?currentSchema=public\n" +
                "    username: postgres\n" +
                "    password: 123456\n" +
                "  resource:\n" +
                "    type: table\n" +
                "    name: performance_test_table\n" +
                "comparison:\n" +
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
                "        type: db2\n" +
                "        url: jdbc:db2://localhost:50000/sample\n" +
                "        tableName: diff_result\n");

        ConfigurationManager configurationManager = new ConfigurationManager();

        ConfigurationException exception = assertThrows(ConfigurationException.class,
                () -> configurationManager.loadConfiguration(configFile.toString(), false));

        assertTrue(exception.getMessage().contains("is not a registered dialect: db2"));
    }

    @Test
    void shouldRejectTableSinkWithoutExplicitDatabaseType() throws Exception {
        Path configFile = tempDir.resolve("diff-config-missing-sink-type.yaml");
        Files.writeString(configFile, "source:\n" +
                "  type: mysql\n" +
                "  connection:\n" +
                "    url: jdbc:mysql://localhost:3306/test\n" +
                "    username: root\n" +
                "    password: 123456\n" +
                "  resource:\n" +
                "    type: table\n" +
                "    name: performance_test_table\n" +
                "target:\n" +
                "  type: postgresql\n" +
                "  connection:\n" +
                "    url: jdbc:postgresql://localhost:5432/postgres?currentSchema=public\n" +
                "    username: postgres\n" +
                "    password: 123456\n" +
                "  resource:\n" +
                "    type: table\n" +
                "    name: performance_test_table\n" +
                "comparison:\n" +
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

    @Test
    void shouldReportMissingTargetInsteadOfNullPointer() throws Exception {
        Path configFile = tempDir.resolve("missing-target.yaml");
        Files.writeString(configFile, "source:\n"
                + "  type: file\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: source.csv\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n");

        ConfigurationManager.ValidationResult result =
                new ConfigurationManager().validateConfigurationFile(configFile.toString());

        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("target 配置不能为空"),
                "expected a field-specific message, got: " + result.getMessage());
    }

    @Test
    void shouldReportMissingComparisonKeysInsteadOfNullPointer() throws Exception {
        Path configFile = tempDir.resolve("missing-keys.yaml");
        Files.writeString(configFile, "source:\n"
                + "  type: file\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: source.csv\n"
                + "target:\n"
                + "  type: file\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: target.csv\n"
                + "comparison: {}\n");

        ConfigurationManager.ValidationResult result =
                new ConfigurationManager().validateConfigurationFile(configFile.toString());

        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("comparison.keys 配置不能为空"),
                "expected a field-specific message, got: " + result.getMessage());
    }

    @Test
    void shouldRejectUnknownSinkFormatDuringValidation() throws Exception {
        Path configFile = tempDir.resolve("bad-sink.yaml");
        Files.writeString(configFile, fileConfigWithSink("    - format: consol\n      type: result\n"));

        ConfigurationManager.ValidationResult result =
                new ConfigurationManager().validateConfigurationFile(configFile.toString());

        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("no SinkProvider"),
                "expected a provider error, got: " + result.getMessage());
        assertTrue(result.getMessage().contains("console"),
                "expected available providers in the message, got: " + result.getMessage());
    }

    @Test
    void shouldSkipSinkFormatCheckForDisabledSinks() throws Exception {
        Path configFile = tempDir.resolve("disabled-sink.yaml");
        Files.writeString(configFile, fileConfigWithSink(
                "    - format: consol\n      type: result\n      enabled: false\n"));

        ConfigurationManager.ValidationResult result =
                new ConfigurationManager().validateConfigurationFile(configFile.toString());

        assertTrue(result.isValid(), "disabled sinks should not block validation: " + result.getMessage());
    }

    @Test
    void shouldExplainEmptyConfigurationFile() throws Exception {
        Path configFile = tempDir.resolve("empty.yaml");
        Files.writeString(configFile, "");

        ConfigurationManager.ValidationResult result =
                new ConfigurationManager().validateConfigurationFile(configFile.toString());

        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("Cannot deserialize"),
                "expected the Jackson cause in the message, got: " + result.getMessage());
    }

    @Test
    void shouldExplainMalformedConfigurationFile() throws Exception {
        Path configFile = tempDir.resolve("malformed.yaml");
        Files.writeString(configFile, "source: [unclosed\n");

        ConfigurationManager.ValidationResult result =
                new ConfigurationManager().validateConfigurationFile(configFile.toString());

        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("line 1"),
                "expected line information in the message, got: " + result.getMessage());
    }

    @Test
    void shouldFlattenNestedResourceOptionsBlock() throws Exception {
        Path configFile = tempDir.resolve("nested-options.yaml");
        Files.writeString(configFile, "source:\n"
                + "  type: file\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    path: s3://bucket/data/\n"
                + "    options:\n"
                + "      s3Region: eu-central-1\n"
                + "      s3Endpoint: http://127.0.0.1:9000\n"
                + "      s3PathStyle: true\n"
                + "      s3AccessKey: minio\n"
                + "      s3SecretKey: minio123\n"
                + "target:\n"
                + "  type: iceberg\n"
                + "  resource:\n"
                + "    type: iceberg\n"
                + "    path: /warehouse/db/users/metadata/v3.metadata.json\n"
                + "    format: parquet\n"
                + "    options:\n"
                + "      snapshotId: \"1234567890\"\n"
                + "      partition.dt: \"2026-10-27\"\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n");

        CliConfiguration config = new ConfigurationManager().loadConfiguration(configFile.toString(), false);

        Map<String, Object> sourceOptions = config.getSource().getResource().getOptions();
        assertEquals("eu-central-1", sourceOptions.get("s3Region"));
        assertEquals("http://127.0.0.1:9000", sourceOptions.get("s3Endpoint"));
        assertEquals(Boolean.TRUE, sourceOptions.get("s3PathStyle"));
        assertEquals("minio", sourceOptions.get("s3AccessKey"));
        assertEquals("minio123", sourceOptions.get("s3SecretKey"));
        assertFalse(sourceOptions.containsKey("options"),
                "nested options must not leak into the option map: " + sourceOptions);

        Map<String, Object> targetOptions = config.getTarget().getResource().getOptions();
        assertEquals("1234567890", targetOptions.get("snapshotId"));
        assertEquals("2026-10-27", targetOptions.get("partition.dt"));
        assertEquals("parquet", targetOptions.get("format"));
    }

    @Test
    void shouldAcceptIcebergResourceDocumentedInDocs15() throws Exception {
        Path configFile = tempDir.resolve("iceberg-resource.yaml");
        Files.writeString(configFile, "source:\n"
                + "  type: iceberg\n"
                + "  name: source-iceberg\n"
                + "  resource:\n"
                + "    type: iceberg\n"
                + "    path: /warehouse/db/users/metadata/v3.metadata.json\n"
                + "target:\n"
                + "  type: file\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: target.csv\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n");

        ConfigurationManager.ValidationResult result =
                new ConfigurationManager().validateConfigurationFile(configFile.toString());

        assertTrue(result.isValid(), "docs/15 Iceberg configuration must validate: " + result.getMessage());
    }

    @Test
    void shouldRejectResourceTypeThatDoesNotMatchConnector() throws Exception {
        Path configFile = tempDir.resolve("mismatched-resource-type.yaml");
        Files.writeString(configFile, "source:\n"
                + "  type: mysql\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/test\n"
                + "    username: root\n"
                + "    password: secret\n"
                + "  resource:\n"
                + "    type: iceberg\n"
                + "    path: /warehouse/db/users/metadata/v3.metadata.json\n"
                + "target:\n"
                + "  type: file\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: target.csv\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n");

        ConfigurationManager.ValidationResult result =
                new ConfigurationManager().validateConfigurationFile(configFile.toString());

        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("source.resource.type"),
                "expected a resource type error, got: " + result.getMessage());
    }

    @Test
    void shouldKeepFlatResourceOptionsWorking() throws Exception {
        Path configFile = tempDir.resolve("flat-options.yaml");
        Files.writeString(configFile, "source:\n"
                + "  type: file\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    path: s3://bucket/data/\n"
                + "    format: csv\n"
                + "    s3Endpoint: http://127.0.0.1:9000\n"
                + "target:\n"
                + "  type: file\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: target.csv\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n");

        CliConfiguration config = new ConfigurationManager().loadConfiguration(configFile.toString(), false);

        Map<String, Object> sourceOptions = config.getSource().getResource().getOptions();
        assertEquals("http://127.0.0.1:9000", sourceOptions.get("s3Endpoint"));
        assertEquals("csv", sourceOptions.get("format"));
    }

    private String fileConfigWithSink(String sinkYaml) {
        return "source:\n"
                + "  type: file\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: source.csv\n"
                + "target:\n"
                + "  type: file\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: target.csv\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "result:\n"
                + "  sinks:\n"
                + sinkYaml;
    }
}
