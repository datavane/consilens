package com.consilens.core.integration;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.segment.TableSegment;
import com.consilens.core.segment.TableSegment.ChecksumResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-database integration test for DatabaseAdapter.
 *
 * <p>Verifies core DatabaseAdapter operations in MySQL and PostgreSQL:
 * connection, query, count, checksum, and schema retrieval.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("DatabaseAdapter 集成测试")
class DatabaseAdapterITest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("adapter_test")
            .withUsername("test")
            .withPassword("test123")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("adapter_test")
            .withUsername("test")
            .withPassword("test123");

    private static DatabaseAdapter mysqlAdapter;
    private static DatabaseAdapter pgAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        mysqlAdapter = TestDatabaseHelper.createAdapter(
                "mysql-adapter", MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword(), DatabaseType.MYSQL, ChecksumAlgorithm.CONCAT);
        pgAdapter = TestDatabaseHelper.createAdapter(
                "pg-adapter", POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), DatabaseType.POSTGRESQL, ChecksumAlgorithm.CONCAT);

        // Prepare test data
        TestDatabaseHelper.createTestTable(mysqlAdapter, "adapter_data");
        TestDatabaseHelper.insertStandardData(mysqlAdapter, "adapter_data");
        TestDatabaseHelper.createTestTable(pgAdapter, "adapter_data");
        TestDatabaseHelper.insertStandardData(pgAdapter, "adapter_data");
    }

    @AfterAll
    static void tearDown() {
        if (mysqlAdapter != null) {
            mysqlAdapter.close();
        }
        if (pgAdapter != null) {
            pgAdapter.close();
        }
    }

    @Nested
    @DisplayName("MySQL Adapter 测试")
    class MySQLAdapterTests {

        @Test
        @DisplayName("MySQL 连接健康检查")
        void mysqlShouldBeHealthy() {
            assertThat(mysqlAdapter.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("MySQL 获取连接")
        void mysqlShouldProvideConnection() throws Exception {
            try (Connection conn = mysqlAdapter.getConnection()) {
                assertThat(conn).isNotNull();
                assertThat(conn.isClosed()).isFalse();
            }
        }

        @Test
        @DisplayName("MySQL COUNT 查询")
        void mysqlCountShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(mysqlAdapter)
                    .tablePath(TablePath.of("adapter_test", "adapter_data"))
                    .keyColumns(Arrays.asList("id"))
                    .extraColumns(Arrays.asList("name", "value", "status"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(11)))
                    .build();

            long count = mysqlAdapter.count(seg);
            assertThat(count).isEqualTo(10);
        }

        @Test
        @DisplayName("MySQL countAndChecksum 查询")
        void mysqlCountAndChecksumShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(mysqlAdapter)
                    .tablePath(TablePath.of("adapter_test", "adapter_data"))
                    .keyColumns(Arrays.asList("id"))
                    .extraColumns(Arrays.asList("name", "value", "status"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(11)))
                    .build();

            ChecksumResult result = mysqlAdapter.countAndChecksum(seg);
            assertThat(result).isNotNull();
            assertThat(result.getCount()).isEqualTo(10);
            assertThat(result.getChecksum()).isNotNull();
            assertThat(result.getChecksum()).isNotEmpty();
        }

        @Test
        @DisplayName("MySQL countAndBounds 查询")
        void mysqlCountAndBoundsShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(mysqlAdapter)
                    .tablePath(TablePath.of("adapter_test", "adapter_data"))
                    .keyColumns(Arrays.asList("id"))
                    .extraColumns(Arrays.asList("name", "value", "status"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(11)))
                    .build();

            ChecksumResult result = mysqlAdapter.countAndBounds(seg);
            assertThat(result).isNotNull();
            assertThat(result.getCount()).isEqualTo(10);
            assertThat(result.getMinKey()).isNotNull();
            assertThat(result.getMaxKey()).isNotNull();
        }

        @Test
        @DisplayName("MySQL 查询段数据")
        void mysqlQuerySegmentShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(mysqlAdapter)
                    .tablePath(TablePath.of("adapter_test", "adapter_data"))
                    .keyColumns(Arrays.asList("id"))
                    .extraColumns(Arrays.asList("name", "value", "status"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(5)))
                    .build();

            List<Object[]> rows = mysqlAdapter.querySegment(seg);
            assertThat(rows).isNotNull();
            assertThat(rows).hasSizeGreaterThanOrEqualTo(4);
        }

        @Test
        @DisplayName("MySQL 获取表结构")
        void mysqlGetTableSchemaShouldWork() {
            TableSchema schema = mysqlAdapter.getTableSchema(Arrays.asList("adapter_test", "adapter_data"));
            assertThat(schema).isNotNull();
        }

        @Test
        @DisplayName("MySQL 数据库类型正确")
        void mysqlTypeShouldBeCorrect() {
            assertThat(mysqlAdapter.getType()).isEqualTo(DatabaseType.MYSQL);
        }

        @Test
        @DisplayName("MySQL 元数据不为空")
        void mysqlMetadataShouldNotBeEmpty() {
            Map<String, Object> metadata = mysqlAdapter.getMetadata();
            assertThat(metadata).isNotNull();
        }
    }

    @Nested
    @DisplayName("PostgreSQL Adapter 测试")
    class PostgreSQLAdapterTests {

        @Test
        @DisplayName("PostgreSQL 连接健康检查")
        void pgShouldBeHealthy() {
            assertThat(pgAdapter.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("PostgreSQL 获取连接")
        void pgShouldProvideConnection() throws Exception {
            try (Connection conn = pgAdapter.getConnection()) {
                assertThat(conn).isNotNull();
                assertThat(conn.isClosed()).isFalse();
            }
        }

        @Test
        @DisplayName("PostgreSQL COUNT 查询")
        void pgCountShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(pgAdapter)
                    .tablePath(TablePath.of("public", "adapter_data"))
                    .keyColumns(Arrays.asList("id"))
                    .extraColumns(Arrays.asList("name", "value", "status"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(11)))
                    .build();

            long count = pgAdapter.count(seg);
            assertThat(count).isEqualTo(10);
        }

        @Test
        @DisplayName("PostgreSQL countAndChecksum 查询")
        void pgCountAndChecksumShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(pgAdapter)
                    .tablePath(TablePath.of("public", "adapter_data"))
                    .keyColumns(Arrays.asList("id"))
                    .extraColumns(Arrays.asList("name", "value", "status"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(11)))
                    .build();

            ChecksumResult result = pgAdapter.countAndChecksum(seg);
            assertThat(result).isNotNull();
            assertThat(result.getCount()).isEqualTo(10);
            assertThat(result.getChecksum()).isNotNull();
        }

        @Test
        @DisplayName("PostgreSQL 查询段数据")
        void pgQuerySegmentShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(pgAdapter)
                    .tablePath(TablePath.of("public", "adapter_data"))
                    .keyColumns(Arrays.asList("id"))
                    .extraColumns(Arrays.asList("name", "value", "status"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(5)))
                    .build();

            List<Object[]> rows = pgAdapter.querySegment(seg);
            assertThat(rows).isNotNull();
            assertThat(rows).hasSizeGreaterThanOrEqualTo(4);
        }

        @Test
        @DisplayName("PostgreSQL 数据库类型正确")
        void pgTypeShouldBeCorrect() {
            assertThat(pgAdapter.getType()).isEqualTo(DatabaseType.POSTGRESQL);
        }

        @Test
        @DisplayName("PostgreSQL 行哈希查询")
        void pgRowHashesShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(pgAdapter)
                    .tablePath(TablePath.of("public", "adapter_data"))
                    .keyColumns(Arrays.asList("id"))
                    .extraColumns(Arrays.asList("name", "value", "status"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(11)))
                    .build();

            Map<List<Object>, String> hashes = pgAdapter.querySegmentRowHashes(seg);
            assertThat(hashes).isNotNull();
            assertThat(hashes).hasSize(10);
        }
    }
}
