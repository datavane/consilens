package com.consilens.cli.config;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CliDiffResult;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.cli.service.DiffService;
import com.consilens.connector.api.normalization.DefaultNormalizationSpecValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for cross-database comparison configurations.
 *
 * Directory structure checks run on every build and need no external
 * services. The configuration loading test requires credentials for all
 * databases and is skipped by default; enable it with
 * {@code -Dconsilens.it.enabled=true} when the databases are running.
 * Credentials are injected through the MYSQL_*, PG_*, CLICKHOUSE_*,
 * TIDB_*, STARROCKS_*, DORIS_*, ORACLE_*, SQLSERVER_*, TRINO_*, PRESTO_*
 * and OCEANBASE_* environment variables (or the matching
 * {@code consilens.it.<NAME>} system properties, where {@code NAME} matches
 * the environment variable name, for example
 * {@code -Dconsilens.it.MYSQL_USER=root}); the repository contains no
 * plaintext defaults.
 */
class CrossDatabaseComparisonIntegrationTest {

    private static final List<String> ALL_ENV_VARS = List.of(
            "MYSQL_USER", "MYSQL_PASSWORD",
            "PG_USER", "PG_PASSWORD",
            "CLICKHOUSE_USER", "CLICKHOUSE_PASSWORD",
            "TIDB_USER", "TIDB_PASSWORD",
            "STARROCKS_USER", "STARROCKS_PASSWORD",
            "DORIS_USER", "DORIS_PASSWORD",
            "ORACLE_USER", "ORACLE_PASSWORD",
            "SQLSERVER_USER", "SQLSERVER_PASSWORD",
            "TRINO_USER", "TRINO_PASSWORD",
            "PRESTO_USER", "PRESTO_PASSWORD",
            "OCEANBASE_USER", "OCEANBASE_PASSWORD");

    /**
     * Locates the examples directory from the test classes output location
     * (consilens-cli/target/test-classes), so it does not depend on the
     * current working directory.
     */
    private static Path examplesDirectory() {
        try {
            Path testClasses = Paths.get(CrossDatabaseComparisonIntegrationTest.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            Path examples = testClasses.getParent().getParent().getParent().resolve("examples").normalize();
            if (!Files.isDirectory(examples)) {
                throw new IllegalStateException("examples directory not found at: " + examples);
            }
            return examples;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate examples directory from test classes location", e);
        }
    }

    /**
     * Provides all cross-database comparison configurations
     * (cross-db/mysql-* pair directories, YAML + JSON).
     */
    static Stream<Path> crossDatabaseComparisonConfigs() throws IOException {
        Path examplesDirectory = examplesDirectory();
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(examplesDirectory.resolve("cross-db"), 2)) {
            paths = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".yaml") || name.endsWith(".json");
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }
        return paths.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("crossDatabaseComparisonConfigs")
    @EnabledIfSystemProperty(named = "consilens.it.enabled", matches = "true")
    void shouldProduceValidComparisonRequest(Path configPath) throws Exception {
        ConfigurationManager configurationManager = new ConfigurationManager(testEnvironment());
        CliConfiguration config = configurationManager.loadConfiguration(configPath.toString(), false);

        assertNotNull(config, "Config should load: " + configPath);
        assertNotNull(config.getSource(), "Source config should not be null");
        assertNotNull(config.getTarget(), "Target config should not be null");
        assertNotNull(config.getComparison(), "Comparison config should not be null");
        assertNotNull(config.getStrategy(), "Strategy config should not be null");

        // Validate the configuration
        config.validate();

        // Create comparison request
        CompareRequestFactory factory = new CompareRequestFactory();
        var request = factory.create(config);

        // Validate normalization spec
        if (request.getNormalizationSpec() != null) {
            new DefaultNormalizationSpecValidator().validate(request.getNormalizationSpec());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("crossDatabaseComparisonConfigs")
    @EnabledIfSystemProperty(named = "consilens.it.enabled", matches = "true")
    void shouldMatchBaselineDifferences(Path configPath) throws Exception {
        ConfigurationManager configurationManager = new ConfigurationManager(testEnvironment());
        CliConfiguration config = configurationManager.loadConfiguration(configPath.toString(), false);

        CliDiffResult result = new DiffService().performDiff(config);

        JsonNode baseline = baselineFor(configPath);
        if (baseline == null) {
            return;
        }
        assertTotalDifferences(configPath, result, baseline);
        assertBreakdown(configPath, result, baseline);
    }

    private void assertTotalDifferences(Path configPath, CliDiffResult result, JsonNode baseline) {
        if (baseline.has("total")) {
            assertEquals(baseline.get("total").asLong(), result.getTotalDifferences(),
                    "total differences mismatch for " + configPath);
        }
        if (baseline.has("totalMin")) {
            assertTrue(result.getTotalDifferences() >= baseline.get("totalMin").asLong(),
                    "total differences below minimum for " + configPath);
        }
    }

    private void assertBreakdown(Path configPath, CliDiffResult result, JsonNode baseline) {
        if (baseline.has("sourceMissing")) {
            assertEquals(baseline.get("sourceMissing").asLong(), result.getSourceMissingCount(),
                    "source missing count mismatch for " + configPath);
        }
        if (baseline.has("targetMissing")) {
            assertEquals(baseline.get("targetMissing").asLong(), result.getTargetMissingCount(),
                    "target missing count mismatch for " + configPath);
        }
        if (baseline.has("mismatch")) {
            assertEquals(baseline.get("mismatch").asLong(), result.getMismatchCount(),
                    "mismatch count mismatch for " + configPath);
        }
    }

    private JsonNode baselineFor(Path configPath) throws IOException {
        Path baselineFile = examplesDirectory().resolve("test-baselines.json");
        if (!Files.exists(baselineFile)) {
            return null;
        }
        JsonNode baselines = new ObjectMapper().readTree(baselineFile.toFile()).get("baselines");
        String relative = examplesDirectory().relativize(configPath).toString().replace('\\', '/');
        return baselines.get(relative);
    }

    @Test
    void shouldSupportAllScenarioConfigs() throws Exception {
        // Verify each cross-db mysql-* pair directory has the full scenario set
        Path examplesDirectory = examplesDirectory();
        List<String> expectedConfigs = List.of(
                "01-custom-sql-checksum.yaml",
                "02-detail-to-aggregate.yaml",
                "03-large-table.yaml",
                "04-mapped-checksum.yaml",
                "05-same-db-join.yaml",
                "06-performance-full-fields.yaml",
                "07-exclude-fields.yaml",
                "09-json-format.json",
                "11-identical.yaml"
        );

        try (Stream<Path> stream = Files.list(examplesDirectory.resolve("cross-db"))) {
            List<Path> pairDirs = stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("mysql-"))
                    .collect(Collectors.toList());

            assertFalse(pairDirs.isEmpty(), "Should have at least one mysql-* pair directory");

            for (Path pairDir : pairDirs) {
                String pairName = pairDir.getFileName().toString();
                for (String expectedConfig : expectedConfigs) {
                    Path configPath = pairDir.resolve(expectedConfig);
                    assertTrue(Files.exists(configPath),
                            pairName + " should contain " + expectedConfig);
                }
            }
        }

        assertTrue(Files.exists(examplesDirectory.resolve("cross-db/mysql-pg/08-output-postgres.yaml")),
                "mysql-pg should contain 08-output-postgres.yaml");
        assertTrue(Files.exists(examplesDirectory.resolve("cross-db/mysql-doris/10-partition-filter.yaml")),
                "mysql-doris should contain 10-partition-filter.yaml");

        // Verify each same-db data source has identical + join-diff scenarios
        List<String> sameDbDirs = List.of(
                "mysql", "postgresql", "clickhouse", "tidb", "starrocks", "doris",
                "trino", "presto", "oceanbase", "oracle", "sqlserver");
        for (String name : sameDbDirs) {
            Path sameDbDir = examplesDirectory.resolve("same-db").resolve(name);
            assertTrue(Files.exists(sameDbDir.resolve("01-identical.yaml")),
                    "same-db/" + name + " should contain 01-identical.yaml");
            assertTrue(Files.exists(sameDbDir.resolve("02-join-diff.yaml")),
                    "same-db/" + name + " should contain 02-join-diff.yaml");
        }
        assertTrue(Files.exists(examplesDirectory.resolve("same-db/mysql/03-normalization.yaml")),
                "same-db/mysql should contain 03-normalization.yaml");
    }

    @Test
    void shouldHaveLoadSqlForEachPair() throws Exception {
        // Verify each cross-db mysql-* pair directory has load-mysql.sql and load-<target>.sql
        Path examplesDirectory = examplesDirectory();

        try (Stream<Path> stream = Files.list(examplesDirectory.resolve("cross-db"))) {
            List<Path> pairDirs = stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("mysql-"))
                    .collect(Collectors.toList());

            for (Path pairDir : pairDirs) {
                String pairName = pairDir.getFileName().toString();
                assertTrue(Files.exists(pairDir.resolve("load-mysql.sql")),
                        pairName + " should contain load-mysql.sql");

                // Check for at least one load-*.sql file besides load-mysql.sql
                try (Stream<Path> sqlStream = Files.list(pairDir)) {
                    long targetSqlCount = sqlStream
                            .filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().startsWith("load-"))
                            .filter(path -> !path.getFileName().toString().equals("load-mysql.sql"))
                            .count();
                    assertTrue(targetSqlCount > 0,
                            pairName + " should contain at least one load-<target>.sql file");
                }
            }
        }
    }

    private Map<String, String> testEnvironment() {
        Map<String, String> env = new HashMap<>();
        for (String name : ALL_ENV_VARS) {
            String property = System.getProperty("consilens.it." + name);
            if (property != null) {
                env.put(name, property);
            } else if (System.getenv().containsKey(name)) {
                env.put(name, System.getenv(name));
            }
        }
        return env;
    }
}
