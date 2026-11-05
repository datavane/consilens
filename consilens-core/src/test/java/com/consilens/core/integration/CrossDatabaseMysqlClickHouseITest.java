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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-database (MySQL → ClickHouse) Checksum diff integration test.
 * ClickHouse uses HTTP protocol on port 8123.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("跨数据库 MySQL vs ClickHouse Checksum Diff 集成测试")
class CrossDatabaseMysqlClickHouseITest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("consilens_demo")
            .withUsername("test")
            .withPassword("test123")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(
            // 科学计数归一化使用 printf('%.6e', ...)，ClickHouse 从 23.7 起提供；
            // 23.3 会直接报 Unknown function printf，导致集成测试误判。
            DockerImageName.parse("clickhouse/clickhouse-server:24.8"))
            .withExposedPorts(8123, 9000)
            .withEnv("CLICKHOUSE_USER", "test")
            .withEnv("CLICKHOUSE_PASSWORD", "test123")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1");

    private static DatabaseAdapter mysqlAdapter;
    private static DatabaseAdapter clickhouseAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        mysqlAdapter = CrossDatabaseITestBase.createAdapter("mysql-source", MYSQL, "mysql");

        // First create database on ClickHouse (connect without database)
        String chHost = CLICKHOUSE.getHost();
        Integer chPort = CLICKHOUSE.getMappedPort(8123);
        String chRootUrl = "jdbc:clickhouse://" + chHost + ":" + chPort + "/";
        DatabaseAdapter chRootAdapter = TestDatabaseHelper.createAdapter(
                "clickhouse-root", chRootUrl, "test", "test123",
                "clickhouse", com.consilens.common.enums.ChecksumAlgorithm.CONCAT);
        CrossDatabaseITestBase.executeSql(chRootAdapter, "CREATE DATABASE IF NOT EXISTS consilens_demo");
        chRootAdapter.close();

        // Now connect with database
        String chUrl = "jdbc:clickhouse://" + chHost + ":" + chPort + "/consilens_demo";
        clickhouseAdapter = TestDatabaseHelper.createAdapter(
                "clickhouse-target", chUrl, "test", "test123",
                "clickhouse", com.consilens.common.enums.ChecksumAlgorithm.CONCAT);

        createTestTable(mysqlAdapter, "cross_source");
        createTestTable(clickhouseAdapter, "cross_target");

        insertTestData(mysqlAdapter, "cross_source");
        insertTestData(clickhouseAdapter, "cross_target");
    }

    @AfterAll
    static void tearDown() {
        if (mysqlAdapter != null) mysqlAdapter.close();
        if (clickhouseAdapter != null) clickhouseAdapter.close();
    }

    private static void createTestTable(DatabaseAdapter adapter, String tableName) throws Exception {
        String dialect = adapter.getConnectorType();
        if ("clickhouse".equalsIgnoreCase(dialect)) {
            CrossDatabaseITestBase.executeSql(adapter, "DROP TABLE IF EXISTS " + tableName);
            CrossDatabaseITestBase.executeSql(adapter,
                "CREATE TABLE " + tableName + " (" +
                "id Int32, name String, value Decimal(10,2), " +
                "status String, created_at DateTime, is_active UInt8" +
                ") ENGINE = Memory");
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
            if ("clickhouse".equalsIgnoreCase(dialect)) {
                sql = String.format(
                    "INSERT INTO %s (id, name, value, status, created_at, is_active) VALUES (%d, 'item_%d', %s, 'active', '2026-01-01 00:00:00', %d)",
                    tableName, i, i, String.valueOf(i * 10.50), i % 2);
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
    @DisplayName("MySQL 和 ClickHouse 中相同数据应无差异")
    void identicalDataAcrossDatabasesShouldHaveNoDifferences() throws Exception {
        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        // ClickHouse is case-sensitive for column names - use lowercase to match table definition
        TableSegment seg2 = TableSegment.builder()
                .database(clickhouseAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_target"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
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
    @DisplayName("MySQL vs ClickHouse 应检测到数据差异")
    void shouldDetectDifferencesAcrossDatabases() throws Exception {
        CrossDatabaseITestBase.executeSql(clickhouseAdapter,
            "INSERT INTO cross_target (id, name, value, status, created_at, is_active) VALUES (11, 'extra_item', 99.99, 'active', '2026-01-01 00:00:00', 1)");

        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        // ClickHouse is case-sensitive for column names - use lowercase to match table definition
        TableSegment seg2 = TableSegment.builder()
                .database(clickhouseAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_target"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
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
    @DisplayName("MySQL 与 ClickHouse 应对所有公共类型和四类结果给出精确结论")
    void shouldVerifyPublicTypeFamiliesAndEveryDiffDirection() throws Exception {
        CrossDatabaseAccuracyFixture.verify(
                mysqlAdapter, TablePath.of("consilens_demo", "placeholder"),
                clickhouseAdapter, TablePath.of("consilens_demo", "placeholder"));
    }
}
