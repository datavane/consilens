package com.consilens.core.integration;

import com.consilens.connector.api.model.TablePath;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-database (MySQL → Trino) Checksum diff integration test.
 * Trino is a query engine, uses memory connector for testing.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("跨数据库 MySQL vs Trino Checksum Diff 集成测试")
class CrossDatabaseMysqlTrinoITest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("consilens_demo")
            .withUsername("test")
            .withPassword("test123")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Container
    private static final GenericContainer<?> TRINO = new GenericContainer<>(
            DockerImageName.parse("trinodb/trino:430"))
            .withExposedPorts(8080)
            .withEnv("TRINO_DISCOVERY_URI", "http://localhost:8080")
            .waitingFor(new LogMessageWaitStrategy()
                    .withRegEx(".*SERVER STARTED.*\\s")
                    .withStartupTimeout(Duration.ofMinutes(5)));

    private static DatabaseAdapter mysqlAdapter;
    private static DatabaseAdapter trinoAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        mysqlAdapter = CrossDatabaseITestBase.createAdapter("mysql-source", MYSQL, "mysql");

        String trinoHost = TRINO.getHost();
        Integer trinoPort = TRINO.getMappedPort(8080);

        // Wait for Trino worker to fully register (SERVER STARTED log is not enough)
        System.out.println("Waiting for Trino worker to register...");
        Thread.sleep(15000);

        // First connect without schema to create the schema
        String trinoRootUrl = "jdbc:trino://" + trinoHost + ":" + trinoPort + "/memory";
        DatabaseAdapter trinoRootAdapter = CrossDatabaseITestBase.createAdapter("trino-root", trinoRootUrl, "test", "", "trino");
        CrossDatabaseITestBase.executeSql(trinoRootAdapter, "CREATE SCHEMA IF NOT EXISTS consilens_demo");
        trinoRootAdapter.close();

        // Now connect with schema
        String trinoUrl = "jdbc:trino://" + trinoHost + ":" + trinoPort + "/memory/consilens_demo";
        trinoAdapter = CrossDatabaseITestBase.createAdapter("trino-target", trinoUrl, "test", "", "trino");

        createTestTable(mysqlAdapter, "cross_source");
        createTestTable(trinoAdapter, "cross_target");

        insertTestData(mysqlAdapter, "cross_source");
        insertTestData(trinoAdapter, "cross_target");
    }

    @AfterAll
    static void tearDown() {
        if (mysqlAdapter != null) mysqlAdapter.close();
        if (trinoAdapter != null) trinoAdapter.close();
    }

    private static void createTestTable(DatabaseAdapter adapter, String tableName) throws Exception {
        String dialect = adapter.getConnectorType();
        if ("trino".equalsIgnoreCase(dialect)) {
            CrossDatabaseITestBase.executeSql(adapter, "DROP TABLE IF EXISTS " + tableName);
            CrossDatabaseITestBase.executeSql(adapter,
                "CREATE TABLE " + tableName + " (" +
                "id INTEGER, name VARCHAR(100), value DECIMAL(10,2), " +
                "status VARCHAR(20), created_at TIMESTAMP, is_active BOOLEAN" +
                ")");
        } else {
            CrossDatabaseITestBase.executeSql(adapter, "DROP TABLE IF EXISTS " + tableName);
            CrossDatabaseITestBase.executeSql(adapter,
                "CREATE TABLE " + tableName + " (" +
                "id INT PRIMARY KEY, name VARCHAR(100), value DECIMAL(10,2), " +
                "status VARCHAR(20), created_at TIMESTAMP, is_active BOOLEAN)");
        }
    }

    private static void insertTestData(DatabaseAdapter adapter, String tableName) throws Exception {
        String dialect = adapter.getConnectorType();
        for (int i = 1; i <= 10; i++) {
            String sql;
            if ("trino".equalsIgnoreCase(dialect)) {
                sql = String.format(
                    "INSERT INTO %s (id, name, value, status, created_at, is_active) VALUES (%d, 'item_%d', %s, 'active', TIMESTAMP '2026-01-01 00:00:00', %s)",
                    tableName, i, i, String.valueOf(i * 10.50), i % 2 == 0 ? "true" : "false");
            } else {
                sql = String.format(
                    "INSERT INTO %s (id, name, value, status, created_at, is_active) VALUES (%d, 'item_%d', %s, 'active', '2026-01-01 00:00:00', %d)",
                    tableName, i, i, String.valueOf(i * 10.50), i % 2);
            }
            CrossDatabaseITestBase.executeSql(adapter, sql);
        }
    }

    @Test
    @Order(1)
    @DisplayName("MySQL 和 Trino 中相同数据应无差异")
    void identicalDataAcrossDatabasesShouldHaveNoDifferences() throws Exception {
        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(trinoAdapter)
                .tablePath(TablePath.of("memory", "consilens_demo", "cross_target"))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        DiffResult result = CrossDatabaseITestBase.runChecksumDiff(seg1, seg2);

        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isFalse();
        assertThat(result.getStatistics().getSourceRowCount()).isEqualTo(10);
        assertThat(result.getStatistics().getTargetRowCount()).isEqualTo(10);
    }

    @Test
    @Order(2)
    @DisplayName("MySQL vs Trino 应检测到数据差异")
    void shouldDetectDifferencesAcrossDatabases() throws Exception {
        CrossDatabaseITestBase.executeSql(trinoAdapter,
            "INSERT INTO cross_target (id, name, value, status, created_at, is_active) VALUES (11, 'extra_item', 99.99, 'active', TIMESTAMP '2026-01-01 00:00:00', true)");

        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(trinoAdapter)
                .tablePath(TablePath.of("memory", "consilens_demo", "cross_target"))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        DiffResult result = CrossDatabaseITestBase.runChecksumDiff(seg1, seg2);

        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isTrue();

        List<DiffRow> sourceMissing = result.getDifferences().stream()
                .filter(r -> r.getOperation() == DiffOperation.SOURCE_MISSING)
                .collect(Collectors.toList());
        assertThat(sourceMissing).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("MySQL 与 Trino 应对所有公共类型和四类结果给出精确结论")
    void shouldVerifyPublicTypeFamiliesAndEveryDiffDirection() throws Exception {
        CrossDatabaseAccuracyFixture.verify(
                mysqlAdapter, TablePath.of("consilens_demo", "placeholder"),
                trinoAdapter, TablePath.of("memory", "consilens_demo", "placeholder"));
    }
}
