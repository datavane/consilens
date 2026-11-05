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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-database (MySQL → SQL Server) Checksum diff integration test.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("跨数据库 MySQL vs SQL Server Checksum Diff 集成测试")
class CrossDatabaseMysqlSqlServerITest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("consilens_demo")
            .withUsername("test")
            .withPassword("test123")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Container
    private static final JdbcDatabaseContainer<?> SQLSERVER = new MSSQLServerContainer<>(
            "mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense()
            .withPassword("Test@123456")
            .withInitScript(null);

    private static DatabaseAdapter mysqlAdapter;
    private static DatabaseAdapter sqlServerAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        mysqlAdapter = CrossDatabaseITestBase.createAdapter("mysql-source", MYSQL, "mysql");

        // First connect to master database to create consilens_demo
        String masterUrl = SQLSERVER.getJdbcUrl() + ";databaseName=master";
        DatabaseAdapter masterAdapter = CrossDatabaseITestBase.createAdapter(
                "sqlserver-master", masterUrl,
                SQLSERVER.getUsername(), SQLSERVER.getPassword(), "sqlserver");
        CrossDatabaseITestBase.executeSql(masterAdapter, "CREATE DATABASE consilens_demo");
        masterAdapter.close();

        String sqlServerUrl = SQLSERVER.getJdbcUrl() + ";databaseName=consilens_demo";
        sqlServerAdapter = CrossDatabaseITestBase.createAdapter(
                "sqlserver-target", sqlServerUrl,
                SQLSERVER.getUsername(), SQLSERVER.getPassword(), "sqlserver");

        // Create test tables
        createTestTable(mysqlAdapter, "cross_source");
        createTestTable(sqlServerAdapter, "CROSS_TARGET");

        // Insert test data
        insertTestData(mysqlAdapter, "cross_source");
        insertTestData(sqlServerAdapter, "CROSS_TARGET");
    }

    @AfterAll
    static void tearDown() {
        if (mysqlAdapter != null) mysqlAdapter.close();
        if (sqlServerAdapter != null) sqlServerAdapter.close();
    }

    private static void createTestTable(DatabaseAdapter adapter, String tableName) throws Exception {
        String dialect = adapter.getConnectorType();
        if ("sqlserver".equalsIgnoreCase(dialect)) {
            CrossDatabaseITestBase.executeSql(adapter,
                "IF OBJECT_ID('" + tableName + "', 'U') IS NOT NULL DROP TABLE " + tableName);
            CrossDatabaseITestBase.executeSql(adapter,
                "CREATE TABLE " + tableName + " (" +
                "id INT PRIMARY KEY, " +
                "name NVARCHAR(100), " +
                "value DECIMAL(10,2), " +
                "status NVARCHAR(20), " +
                "created_at DATETIME2, " +
                "is_active BIT)");
        } else {
            CrossDatabaseITestBase.executeSql(adapter, "DROP TABLE IF EXISTS " + tableName);
            CrossDatabaseITestBase.executeSql(adapter,
                "CREATE TABLE " + tableName + " (" +
                "id INT PRIMARY KEY, " +
                "name VARCHAR(100), " +
                "value DECIMAL(10,2), " +
                "status VARCHAR(20), " +
                "created_at TIMESTAMP, " +
                "is_active BOOLEAN)");
        }
    }

    private static void insertTestData(DatabaseAdapter adapter, String tableName) throws Exception {
        String dialect = adapter.getConnectorType();
        for (int i = 1; i <= 10; i++) {
            String sql;
            if ("sqlserver".equalsIgnoreCase(dialect)) {
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
    @DisplayName("MySQL 和 SQL Server 中相同数据应无差异")
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
                .database(sqlServerAdapter)
                .tablePath(TablePath.of("dbo", "CROSS_TARGET"))
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
    @DisplayName("MySQL vs SQL Server 应检测到数据差异")
    void shouldDetectDifferencesAcrossDatabases() throws Exception {
        // Use separate tables to avoid interfering with other tests
        createTestTable(mysqlAdapter, "cross_diff_source");
        createTestTable(sqlServerAdapter, "CROSS_DIFF_TARGET");
        insertTestData(mysqlAdapter, "cross_diff_source");
        insertTestData(sqlServerAdapter, "CROSS_DIFF_TARGET");

        // Insert extra row in SQL Server
        CrossDatabaseITestBase.executeSql(sqlServerAdapter,
            "INSERT INTO CROSS_DIFF_TARGET (id, name, value, status, created_at, is_active) VALUES (11, 'extra_item', 99.99, 'active', '2026-01-01 00:00:00', 1)");

        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_diff_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(sqlServerAdapter)
                .tablePath(TablePath.of("dbo", "CROSS_DIFF_TARGET"))
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
    @DisplayName("MySQL 与 SQL Server 应对所有公共类型和四类结果给出精确结论")
    void shouldVerifyPublicTypeFamiliesAndEveryDiffDirection() throws Exception {
        CrossDatabaseAccuracyFixture.verify(
                mysqlAdapter, TablePath.of("consilens_demo", "placeholder"),
                sqlServerAdapter, TablePath.of("dbo", "PLACEHOLDER"));
    }
}
