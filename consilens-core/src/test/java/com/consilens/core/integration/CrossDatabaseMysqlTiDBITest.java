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
 * Cross-database (MySQL → TiDB) Checksum diff integration test.
 * TiDB uses MySQL protocol, so it's highly compatible.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("跨数据库 MySQL vs TiDB Checksum Diff 集成测试")
class CrossDatabaseMysqlTiDBITest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("consilens_demo")
            .withUsername("test")
            .withPassword("test123")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Container
    private static final GenericContainer<?> TIDB = new GenericContainer<>(
            DockerImageName.parse("pingcap/tidb:v7.5.0"))
            .withExposedPorts(4000)
            .waitingFor(new LogMessageWaitStrategy()
                    .withRegEx(".*server is running MySQL protocol.*\\s")
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static DatabaseAdapter mysqlAdapter;
    private static DatabaseAdapter tidbAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        mysqlAdapter = CrossDatabaseITestBase.createAdapter("mysql-source", MYSQL, "mysql");

        // First create consilens_demo database on TiDB
        String tidbHost = TIDB.getHost();
        Integer tidbPort = TIDB.getMappedPort(4000);
        String tidbRootUrl = "jdbc:mysql://" + tidbHost + ":" + tidbPort + "/?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai";
        DatabaseAdapter tidbRootAdapter = CrossDatabaseITestBase.createAdapter("tidb-root", tidbRootUrl, "root", "", "tidb");
        CrossDatabaseITestBase.executeSql(tidbRootAdapter, "CREATE DATABASE IF NOT EXISTS consilens_demo");
        tidbRootAdapter.close();

        String tidbUrl = "jdbc:mysql://" + tidbHost + ":" + tidbPort + "/consilens_demo?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai";
        tidbAdapter = CrossDatabaseITestBase.createAdapter("tidb-target", tidbUrl, "root", "", "tidb");

        createTestTable(mysqlAdapter, "cross_source");
        createTestTable(tidbAdapter, "cross_target");

        insertTestData(mysqlAdapter, "cross_source");
        insertTestData(tidbAdapter, "cross_target");
    }

    @AfterAll
    static void tearDown() {
        if (mysqlAdapter != null) mysqlAdapter.close();
        if (tidbAdapter != null) tidbAdapter.close();
    }

    private static void createTestTable(DatabaseAdapter adapter, String tableName) throws Exception {
        CrossDatabaseITestBase.executeSql(adapter, "DROP TABLE IF EXISTS " + tableName);
        CrossDatabaseITestBase.executeSql(adapter,
            "CREATE TABLE " + tableName + " (" +
            "id INT PRIMARY KEY, name VARCHAR(100), value DECIMAL(10,2), " +
            "status VARCHAR(20), created_at TIMESTAMP, is_active BOOLEAN)");
    }

    private static void insertTestData(DatabaseAdapter adapter, String tableName) throws Exception {
        for (int i = 1; i <= 10; i++) {
            String sql = String.format(
                "INSERT INTO %s (id, name, value, status, created_at, is_active) VALUES (%d, 'item_%d', %s, 'active', '2026-01-01 00:00:00', %d)",
                tableName, i, i, String.valueOf(i * 10.50), i % 2);
            CrossDatabaseITestBase.executeSql(adapter, sql);
        }
    }

    @Test
    @Order(1)
    @DisplayName("MySQL 和 TiDB 中相同数据应无差异")
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
                .database(tidbAdapter)
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
    @DisplayName("MySQL vs TiDB 应检测到数据差异")
    void shouldDetectDifferencesAcrossDatabases() throws Exception {
        CrossDatabaseITestBase.executeSql(tidbAdapter,
            "INSERT INTO cross_target (id, name, value, status, created_at, is_active) VALUES (11, 'extra_item', 99.99, 'active', '2026-01-01 00:00:00', 1)");

        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(tidbAdapter)
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
    @DisplayName("MySQL 与 TiDB 应对所有公共类型和四类结果给出精确结论")
    void shouldVerifyPublicTypeFamiliesAndEveryDiffDirection() throws Exception {
        CrossDatabaseAccuracyFixture.verify(
                mysqlAdapter, TablePath.of("consilens_demo", "placeholder"),
                tidbAdapter, TablePath.of("consilens_demo", "placeholder"));
    }
}
