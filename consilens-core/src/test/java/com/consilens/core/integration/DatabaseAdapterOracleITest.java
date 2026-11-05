package com.consilens.core.integration;

import com.consilens.common.enums.ChecksumAlgorithm;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oracle integration test for DatabaseAdapter.
 *
 * <p>Verifies core DatabaseAdapter operations in Oracle:
 * connection, query, count, checksum, and schema retrieval.
 *
 * <p>Note: Oracle stores unquoted identifiers as uppercase. All table names
 * and schema names must be passed in uppercase to match Oracle's data dictionary.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("DatabaseAdapter Oracle 集成测试")
class DatabaseAdapterOracleITest {

    @Container
    private static final GenericContainer<?> ORACLE = new GenericContainer<>(
            DockerImageName.parse("gvenzl/oracle-xe:21-slim-faststart"))
            .withEnv("ORACLE_PASSWORD", "test123")
            .withExposedPorts(1521)
            .waitingFor(new LogMessageWaitStrategy()
                    .withRegEx(".*DATABASE IS READY TO USE!.*\\s")
                    .withStartupTimeout(Duration.ofMinutes(5)));

    private static DatabaseAdapter oracleAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        String oracleHost = ORACLE.getHost();
        Integer oraclePort = ORACLE.getMappedPort(1521);
        String oracleUrl = "jdbc:oracle:thin:@" + oracleHost + ":" + oraclePort + ":XE";

        oracleAdapter = TestDatabaseHelper.createAdapter(
                "oracle-adapter", oracleUrl, "system",
                "test123", "oracle", ChecksumAlgorithm.CONCAT);

        // Prepare test data
        TestDatabaseHelper.createTestTable(oracleAdapter, "ADAPTER_DATA");
        TestDatabaseHelper.insertStandardData(oracleAdapter, "ADAPTER_DATA");
    }

    @AfterAll
    static void tearDown() {
        if (oracleAdapter != null) {
            oracleAdapter.close();
        }
    }

    @Nested
    @DisplayName("Oracle Adapter 测试")
    class OracleAdapterTests {

        @Test
        @DisplayName("Oracle 连接健康检查")
        void oracleShouldBeHealthy() {
            assertThat(oracleAdapter.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("Oracle 获取连接")
        void oracleShouldProvideConnection() throws Exception {
            try (Connection conn = oracleAdapter.getConnection()) {
                assertThat(conn).isNotNull();
                assertThat(conn.isClosed()).isFalse();
            }
        }

        @Test
        @DisplayName("Oracle COUNT 查询")
        void oracleCountShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(oracleAdapter)
                    .tablePath(TablePath.of("SYSTEM", "ADAPTER_DATA"))
                    .keyColumns(Arrays.asList("ID"))
                    .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(11)))
                    .build();

            long count = oracleAdapter.count(seg);
            assertThat(count).isEqualTo(10);
        }

        @Test
        @DisplayName("Oracle countAndChecksum 查询")
        void oracleCountAndChecksumShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(oracleAdapter)
                    .tablePath(TablePath.of("SYSTEM", "ADAPTER_DATA"))
                    .keyColumns(Arrays.asList("ID"))
                    .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(11)))
                    .build();

            ChecksumResult result = oracleAdapter.countAndChecksum(seg);
            assertThat(result).isNotNull();
            assertThat(result.getCount()).isEqualTo(10);
            assertThat(result.getChecksum()).isNotNull();
            assertThat(result.getChecksum()).isNotEmpty();
        }

        @Test
        @DisplayName("Oracle countAndBounds 查询")
        void oracleCountAndBoundsShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(oracleAdapter)
                    .tablePath(TablePath.of("SYSTEM", "ADAPTER_DATA"))
                    .keyColumns(Arrays.asList("ID"))
                    .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(11)))
                    .build();

            ChecksumResult result = oracleAdapter.countAndBounds(seg);
            assertThat(result).isNotNull();
            assertThat(result.getCount()).isEqualTo(10);
            assertThat(result.getMinKey()).isNotNull();
            assertThat(result.getMaxKey()).isNotNull();
        }

        @Test
        @DisplayName("Oracle 查询段数据")
        void oracleQuerySegmentShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(oracleAdapter)
                    .tablePath(TablePath.of("SYSTEM", "ADAPTER_DATA"))
                    .keyColumns(Arrays.asList("ID"))
                    .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(5)))
                    .build();

            List<Object[]> rows = oracleAdapter.querySegment(seg);
            assertThat(rows).isNotNull();
            assertThat(rows).hasSizeGreaterThanOrEqualTo(4);
        }

        @Test
        @DisplayName("Oracle 获取表结构")
        void oracleGetTableSchemaShouldWork() {
            TableSchema schema = oracleAdapter.getTableSchema(Arrays.asList("SYSTEM", "ADAPTER_DATA"));
            assertThat(schema).isNotNull();
        }

        @Test
        @DisplayName("Oracle 数据库类型正确")
        void oracleTypeShouldBeCorrect() {
            assertThat(oracleAdapter.getConnectorType()).isEqualTo("oracle");
        }

        @Test
        @DisplayName("Oracle 元数据不为空")
        void oracleMetadataShouldNotBeEmpty() {
            Map<String, Object> metadata = oracleAdapter.getMetadata();
            assertThat(metadata).isNotNull();
        }

        @Test
        @DisplayName("Oracle 行哈希查询")
        void oracleRowHashesShouldWork() {
            TableSegment seg = TableSegment.builder()
                    .database(oracleAdapter)
                    .tablePath(TablePath.of("SYSTEM", "ADAPTER_DATA"))
                    .keyColumns(Arrays.asList("ID"))
                    .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                    .minKey(Optional.of(Arrays.asList(1)))
                    .maxKey(Optional.of(Arrays.asList(11)))
                    .build();

            Map<List<Object>, String> hashes = oracleAdapter.querySegmentRowHashes(seg);
            assertThat(hashes).isNotNull();
            assertThat(hashes).hasSize(10);
        }
    }
}
