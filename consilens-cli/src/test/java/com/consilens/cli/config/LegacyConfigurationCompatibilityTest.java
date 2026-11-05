package com.consilens.cli.config;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.connector.api.planner.CompareRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A configuration written before the R1-R3 additions must still load and build
 * the same comparison request. New fields are additive and optional.
 */
class LegacyConfigurationCompatibilityTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadLegacyConfigurationWithoutNewFields() throws Exception {
        Path config = tempDir.resolve("legacy.yaml");
        Files.writeString(config, "source:\n"
                + "  type: mysql\n"
                + "  name: legacy-source\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/source_db\n"
                + "    username: user\n"
                + "    password: pass\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: users\n"
                + "target:\n"
                + "  type: postgresql\n"
                + "  name: legacy-target\n"
                + "  connection:\n"
                + "    url: jdbc:postgresql://localhost:5432/target_db\n"
                + "    username: user\n"
                + "    password: pass\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: users\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "  fields:\n"
                + "    source: [name, amount]\n"
                + "    target: [name, amount]\n"
                + "strategy:\n"
                + "  mode: checksum\n"
                + "  algorithm: concat\n");

        CliConfiguration loaded = new ConfigurationManager().loadConfiguration(config.toString());
        CompareRequest request = new CompareRequestFactory().create(loaded);

        assertThat(loaded.getSource().getType()).isEqualTo("mysql");
        assertThat(loaded.getTarget().getType()).isEqualTo("postgresql");
        assertThat(request.getSourceKeySpec().getFields()).containsExactly("id");
        assertThat(request.getTargetKeySpec().getFields()).containsExactly("id");
        assertThat(request.getSourceComparisons().getFields()).containsExactly("name", "amount");
        assertThat(request.getTargetComparisons().getFields()).containsExactly("name", "amount");
        assertThat(request.getExecutionOptions()).isNotNull();
        assertThat(request.getExecutionOptions().getMaxDurationMs()).isNull();
        assertThat(request.getExecutionOptions().getMaxRowsScanned()).isNull();
        assertThat(request.getExecutionOptions().getMaxBytesFetched()).isNull();
        assertThat(request.getSourceFilter()).isNull();
        assertThat(request.getTargetFilter()).isNull();
    }

    @Test
    void shouldLoadLegacyJsonConfigurationWithEnvironmentDefaults() throws Exception {
        Path config = tempDir.resolve("legacy.json");
        Files.writeString(config, "{"
                + "\"source\":{\"type\":\"mysql\",\"name\":\"source\","
                + "\"connection\":{\"url\":\"jdbc:mysql://localhost:3306/db\","
                + "\"username\":\"${env.CONSILENS_LEGACY_DB_USER:user}\","
                + "\"password\":\"${env.MYSQL_PASSWORD:pass}\"},"
                + "\"resource\":{\"type\":\"table\",\"name\":\"users\"}},"
                + "\"target\":{\"type\":\"mysql\",\"name\":\"target\","
                + "\"connection\":{\"url\":\"jdbc:mysql://localhost:3306/db2\","
                + "\"username\":\"user\",\"password\":\"pass\"},"
                + "\"resource\":{\"type\":\"table\",\"name\":\"users\"}},"
                + "\"comparison\":{\"keys\":{\"source\":[\"id\"],\"target\":[\"id\"]},"
                + "\"fields\":{\"source\":[\"name\"],\"target\":[\"name\"]}},"
                + "\"strategy\":{\"mode\":\"checksum\",\"algorithm\":\"concat\"}"
                + "}");

        CliConfiguration loaded = new ConfigurationManager().loadConfiguration(config.toString());
        CompareRequest request = new CompareRequestFactory().create(loaded);

        assertThat(loaded.getSource().getConnection().getUsername()).isEqualTo("user");
        assertThat(loaded.getTarget().getConnection().getPassword()).isEqualTo("pass");
        assertThat(request.getSourceKeySpec().getFields()).containsExactly("id");
        assertThat(request.getSourceComparisons().getFields()).containsExactly("name");
    }

    @Test
    void shouldLoadLegacyConfigurationWithoutOptionalBlocks() throws Exception {
        Path config = tempDir.resolve("minimal.yaml");
        Files.writeString(config, "source:\n"
                + "  type: mysql\n"
                + "  name: source\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/db\n"
                + "    username: user\n"
                + "    password: pass\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: users\n"
                + "target:\n"
                + "  type: mysql\n"
                + "  name: target\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/db2\n"
                + "    username: user\n"
                + "    password: pass\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: users\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "  fields:\n"
                + "    source: [name]\n"
                + "    target: [name]\n");

        CliConfiguration loaded = new ConfigurationManager().loadConfiguration(config.toString());

        assertThat(loaded.getStrategy()).isNotNull();
        assertThat(loaded.getStrategyMode()).isNotBlank();
        assertThat(loaded.getComparison().getFilters()).isNull();
        assertThat(loaded.getNormalization()).isNull();
        assertThat(loaded.getResult()).isNull();
    }
}
