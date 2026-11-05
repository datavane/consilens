package com.consilens.core.integration;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.LocalCompareMode;
import com.consilens.connector.api.model.TablePath;
import com.consilens.core.algorithm.ChecksumDiffer;
import com.consilens.core.algorithm.TableDiffer.DifferConfig;
import com.consilens.core.algorithm.ValueNormalizer;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.segment.TableSegment;
import com.consilens.core.thread.ConcurrencyConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for cross-database integration tests.
 * Provides helper methods for creating adapters, segments, and running diff comparisons.
 */
public abstract class CrossDatabaseITestBase {

    protected static final Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Creates a DatabaseAdapter for the given container.
     */
    protected static DatabaseAdapter createAdapter(String name, JdbcDatabaseContainer<?> container,
                                                    String connectorType) {
        return TestDatabaseHelper.createAdapter(
                name,
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword(),
                connectorType,
                ChecksumAlgorithm.CONCAT
        );
    }

    /**
     * Creates a DatabaseAdapter for a GenericContainer with custom JDBC URL.
     */
    protected static DatabaseAdapter createAdapter(String name, String jdbcUrl,
                                                    String username, String password,
                                                    String connectorType) {
        return TestDatabaseHelper.createAdapter(
                name, jdbcUrl, username, password, connectorType, ChecksumAlgorithm.CONCAT
        );
    }

    /**
     * Creates a test table with standard cross-database schema.
     */
    protected static void createCrossDbTestTable(DatabaseAdapter adapter, String tableName) throws Exception {
        String dialect = adapter.getConnectorType();
        String sql;
        switch (dialect.toLowerCase()) {
            case "oracle":
                sql = "BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + tableName + " CASCADE CONSTRAINTS'; EXCEPTION WHEN OTHERS THEN NULL; END;";
                executeSql(adapter, sql);
                sql = "CREATE TABLE " + tableName + " (" +
                        "id NUMBER(10) PRIMARY KEY, " +
                        "name VARCHAR2(100), " +
                        "value NUMBER(10,2), " +
                        "status VARCHAR2(20), " +
                        "created_at TIMESTAMP, " +
                        "is_active NUMBER(1)" +
                        ")";
                break;
            case "sqlserver":
                sql = "IF OBJECT_ID('" + tableName + "', 'U') IS NOT NULL DROP TABLE " + tableName + ";";
                executeSql(adapter, sql);
                sql = "CREATE TABLE " + tableName + " (" +
                        "id INT PRIMARY KEY, " +
                        "name NVARCHAR(100), " +
                        "value DECIMAL(10,2), " +
                        "status NVARCHAR(20), " +
                        "created_at DATETIME2, " +
                        "is_active BIT" +
                        ")";
                break;
            case "clickhouse":
                sql = "DROP TABLE IF EXISTS " + tableName;
                executeSql(adapter, sql);
                sql = "CREATE TABLE " + tableName + " (" +
                        "id Int32, " +
                        "name String, " +
                        "value Decimal(10,2), " +
                        "status String, " +
                        "created_at DateTime, " +
                        "is_active UInt8" +
                        ") ENGINE = Memory";
                break;
            case "trino":
            case "presto":
                sql = "DROP TABLE IF EXISTS " + tableName;
                executeSql(adapter, sql);
                sql = "CREATE TABLE " + tableName + " (" +
                        "id INTEGER, " +
                        "name VARCHAR(100), " +
                        "value DECIMAL(10,2), " +
                        "status VARCHAR(20), " +
                        "created_at TIMESTAMP, " +
                        "is_active BOOLEAN" +
                        ")";
                break;
            default:
                // MySQL, PostgreSQL, TiDB, StarRocks, Doris
                sql = "DROP TABLE IF EXISTS " + tableName;
                executeSql(adapter, sql);
                sql = "CREATE TABLE " + tableName + " (" +
                        "id INT PRIMARY KEY, " +
                        "name VARCHAR(100), " +
                        "value DECIMAL(10,2), " +
                        "status VARCHAR(20), " +
                        "created_at TIMESTAMP, " +
                        "is_active BOOLEAN" +
                        ")";
                break;
        }
        executeSql(adapter, sql);
    }

    /**
     * Inserts standard test data into the cross-database test table.
     */
    protected static void insertCrossDbTestData(DatabaseAdapter adapter, String tableName) throws Exception {
        String dialect = adapter.getConnectorType();
        for (int i = 1; i <= 10; i++) {
            String sql;
            switch (dialect.toLowerCase()) {
                case "oracle":
                    sql = String.format(
                            "INSERT INTO %s (id, name, value, status, created_at, is_active) VALUES (%d, 'item_%d', %s, 'active', " +
                            "TO_TIMESTAMP('2026-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS'), %d)",
                            tableName, i, i, String.valueOf(i * 10.50), i % 2);
                    break;
                case "sqlserver":
                    sql = String.format(
                            "INSERT INTO %s (id, name, value, status, created_at, is_active) VALUES (%d, 'item_%d', %s, 'active', " +
                            "'2026-01-01 00:00:00', %d)",
                            tableName, i, i, String.valueOf(i * 10.50), i % 2);
                    break;
                case "clickhouse":
                    sql = String.format(
                            "INSERT INTO %s (id, name, value, status, created_at, is_active) VALUES (%d, 'item_%d', %s, 'active', " +
                            "'2026-01-01 00:00:00', %d)",
                            tableName, i, i, String.valueOf(i * 10.50), i % 2);
                    break;
                case "trino":
                case "presto":
                    sql = String.format(
                            "INSERT INTO %s (id, name, value, status, created_at, is_active) VALUES (%d, 'item_%d', %s, 'active', " +
                            "TIMESTAMP '2026-01-01 00:00:00', %s)",
                            tableName, i, i, String.valueOf(i * 10.50), i % 2 == 0 ? "true" : "false");
                    break;
                default:
                    sql = String.format(
                            "INSERT INTO %s (id, name, value, status, created_at, is_active) VALUES (%d, 'item_%d', %s, 'active', " +
                            "'2026-01-01 00:00:00', %d)",
                            tableName, i, i, String.valueOf(i * 10.50), i % 2);
                    break;
            }
            executeSql(adapter, sql);
        }
    }

    /**
     * Executes a SQL statement on the given adapter.
     */
    protected static void executeSql(DatabaseAdapter adapter, String sql) throws Exception {
        try (java.sql.Connection conn = adapter.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Creates a TableSegment for the given adapter and table.
     */
    protected static TableSegment createSegment(DatabaseAdapter adapter, String schema, String tableName) {
        return TableSegment.builder()
                .database(adapter)
                .tablePath(TablePath.of(schema, tableName))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();
    }

    /**
     * Creates a TableSegment for MySQL/PostgreSQL (lowercase column names).
     */
    protected static TableSegment createSegmentLowercase(DatabaseAdapter adapter, String schema, String tableName) {
        return TableSegment.builder()
                .database(adapter)
                .tablePath(TablePath.of(schema, tableName))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();
    }

    /**
     * Runs a checksum diff between two segments and returns the result.
     */
    protected static DiffResult runChecksumDiff(TableSegment seg1, TableSegment seg2) throws Exception {
        // fixture 的 id 列在部分方言里是可空的；数据本身没有 NULL 键，按生产测试定义
        // 显式允许可空键列，避免严格档把整个 fixture 拒绝掉。
        DifferConfig config = new DifferConfig(4, 100, ChecksumAlgorithm.CONCAT,
                LocalCompareMode.FULL, ConcurrencyConfig.defaultConfig(), 1_000_000L, false,
                ValueNormalizer.DEFAULT_DECIMAL_SCALE, true);
        try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
            return differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);
        }
    }

    /**
     * Gets the schema name for the given connector type.
     */
    protected static String getSchemaName(String connectorType) {
        switch (connectorType.toLowerCase()) {
            case "oracle":
                return "SYSTEM";
            case "postgresql":
                return "public";
            case "sqlserver":
                return "dbo";
            case "clickhouse":
                return "default";
            case "trino":
            case "presto":
                return "memory";
            default:
                return "consilens_demo";
        }
    }

    /**
     * Gets the database name for the given connector type.
     */
    protected static String getDatabaseName(String connectorType) {
        switch (connectorType.toLowerCase()) {
            case "oracle":
                return "CONSILENS_DEMO";
            case "postgresql":
                return "consilens_demo";
            case "sqlserver":
                return "consilens_demo";
            case "clickhouse":
                return "default";
            case "trino":
            case "presto":
                return "memory";
            default:
                return "consilens_demo";
        }
    }
}
