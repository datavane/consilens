package com.consilens.core.integration;

import com.consilens.common.enums.ChecksumAlgorithm;

import com.consilens.connector.api.model.TablePath;
import com.consilens.core.algorithm.ChecksumDiffer;
import com.consilens.core.algorithm.TableDiffer.DifferConfig;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-database (MySQL → Oracle) Checksum diff integration test.
 *
 * <p>Verifies ChecksumDiffer correctness when source and target use different
 * database engines (MySQL vs Oracle), which is the key advantage of Checksum over Join mode.
 *
 * <p>Note: Oracle stores unquoted identifiers as uppercase. All table names
 * must be passed in uppercase to match Oracle's data dictionary.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("跨数据库 MySQL vs Oracle Checksum Diff 集成测试")
class CrossDatabaseMysqlOracleITest {

    private static final String ORACLE_SCHEMA = "SYSTEM";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("consilens_source")
            .withUsername("test")
            .withPassword("test123")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Container
    private static final GenericContainer<?> ORACLE = new GenericContainer<>(
            DockerImageName.parse("gvenzl/oracle-xe:21-slim-faststart"))
            .withEnv("ORACLE_PASSWORD", "test123")
            .withExposedPorts(1521)
            .waitingFor(new LogMessageWaitStrategy()
                    .withRegEx(".*DATABASE IS READY TO USE!.*\\s")
                    .withStartupTimeout(Duration.ofMinutes(5)));

    private static DatabaseAdapter mysqlAdapter;
    private static DatabaseAdapter oracleAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        mysqlAdapter = TestDatabaseHelper.createAdapter(
                "mysql-source", MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword(), "mysql", ChecksumAlgorithm.CONCAT);

        String oracleHost = ORACLE.getHost();
        Integer oraclePort = ORACLE.getMappedPort(1521);
        String oracleUrl = "jdbc:oracle:thin:@" + oracleHost + ":" + oraclePort + ":XE";

        oracleAdapter = TestDatabaseHelper.createAdapter(
                "oracle-target", oracleUrl, "system",
                "test123", "oracle", ChecksumAlgorithm.CONCAT);
    }

    @AfterAll
    static void tearDown() {
        if (mysqlAdapter != null) {
            mysqlAdapter.close();
        }
        if (oracleAdapter != null) {
            oracleAdapter.close();
        }
    }

    @Test
    @DisplayName("MySQL 和 Oracle 中相同数据应无差异")
    void identicalDataAcrossDatabasesShouldHaveNoDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(mysqlAdapter, "cross_source");
        TestDatabaseHelper.createTestTable(oracleAdapter, "CROSS_TARGET");
        TestDatabaseHelper.insertStandardData(mysqlAdapter, "cross_source");
        TestDatabaseHelper.insertStandardData(oracleAdapter, "CROSS_TARGET");

        DifferConfig config = new DifferConfig(4, 100,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_source", "cross_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(oracleAdapter)
                .tablePath(TablePath.of(ORACLE_SCHEMA, "CROSS_TARGET"))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isFalse();
            assertThat(result.getStatistics().getSourceRowCount()).isEqualTo(10);
            assertThat(result.getStatistics().getTargetRowCount()).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("MySQL vs Oracle 应检测到数据差异")
    void shouldDetectDifferencesAcrossDatabases() throws Exception {
        TestDatabaseHelper.createTestTable(mysqlAdapter, "cross_diff_source");
        TestDatabaseHelper.createTestTable(oracleAdapter, "CROSS_DIFF_TARGET");
        TestDatabaseHelper.insertStandardData(mysqlAdapter, "cross_diff_source");
        TestDatabaseHelper.insertStandardData(oracleAdapter, "CROSS_DIFF_TARGET");

        // Modify row 4 on the Oracle side
        TestDatabaseHelper.updateRow(oracleAdapter, "CROSS_DIFF_TARGET", 4, "oracle_modified", 444.44);
        // Insert row 11 on the Oracle side
        TestDatabaseHelper.insertExtraRow(oracleAdapter, "CROSS_DIFF_TARGET", 11, "oracle_extra", 111.11);

        DifferConfig config = new DifferConfig(4, 100,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_source", "cross_diff_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(oracleAdapter)
                .tablePath(TablePath.of(ORACLE_SCHEMA, "CROSS_DIFF_TARGET"))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isTrue();

            Map<DiffOperation, List<DiffRow>> byOp = result.getDifferencesByOperation();

            // id=4 should be MISMATCH
            List<DiffRow> mismatches = byOp.getOrDefault(DiffOperation.MISMATCH, List.of());
            assertThat(mismatches).hasSizeGreaterThanOrEqualTo(1);

            // id=11 should be SOURCE_MISSING
            List<DiffRow> sourceMissing = byOp.getOrDefault(DiffOperation.SOURCE_MISSING, List.of());
            assertThat(sourceMissing).hasSizeGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("MySQL 源端多数据应检测到 TARGET_MISSING")
    void shouldDetectTargetMissingAcrossDatabases() throws Exception {
        TestDatabaseHelper.createTestTable(mysqlAdapter, "cross_target_miss_src");
        TestDatabaseHelper.createTestTable(oracleAdapter, "CROSS_TARGET_MISS_TGT");
        TestDatabaseHelper.insertStandardData(mysqlAdapter, "cross_target_miss_src");
        TestDatabaseHelper.insertStandardData(oracleAdapter, "CROSS_TARGET_MISS_TGT");

        // Insert an extra row on the source (MySQL) side
        TestDatabaseHelper.insertExtraRow(mysqlAdapter, "cross_target_miss_src", 11, "mysql_only", 222.22);

        DifferConfig config = new DifferConfig(4, 100,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_source", "cross_target_miss_src"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(oracleAdapter)
                .tablePath(TablePath.of(ORACLE_SCHEMA, "CROSS_TARGET_MISS_TGT"))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isTrue();

            List<DiffRow> targetMissing = result.getDifferences().stream()
                    .filter(r -> r.getOperation() == DiffOperation.TARGET_MISSING)
                    .collect(Collectors.toList());
            assertThat(targetMissing).hasSizeGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("MySQL 与 Oracle 应对所有公共类型和四类结果给出精确结论")
    void shouldVerifyPublicTypeFamiliesAndEveryDiffDirection() throws Exception {
        CrossDatabaseAccuracyFixture.verify(
                mysqlAdapter, TablePath.of("consilens_source", "placeholder"),
                oracleAdapter, TablePath.of(ORACLE_SCHEMA, "PLACEHOLDER"));
    }
}
