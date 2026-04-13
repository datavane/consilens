package com.consilens.core.integration;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.enums.DatabaseType;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-database (MySQL → PostgreSQL) Checksum diff integration test.
 *
 * <p>Verifies ChecksumDiffer correctness when source and target use different
 * database engines, which is the key advantage of Checksum over Join mode.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("跨数据库 Checksum Diff 集成测试")
class CrossDatabaseDiffITest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("consilens_source")
            .withUsername("test")
            .withPassword("test123")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("consilens_target")
            .withUsername("test")
            .withPassword("test123");

    private static DatabaseAdapter mysqlAdapter;
    private static DatabaseAdapter pgAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        mysqlAdapter = TestDatabaseHelper.createAdapter(
                "mysql-source", MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword(), DatabaseType.MYSQL, ChecksumAlgorithm.CONCAT);
        pgAdapter = TestDatabaseHelper.createAdapter(
                "pg-target", POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), DatabaseType.POSTGRESQL, ChecksumAlgorithm.CONCAT);
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

    @Test
    @DisplayName("MySQL 和 PostgreSQL 中相同数据应无差异")
    void identicalDataAcrossDatabasesShouldHaveNoDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(mysqlAdapter, "cross_source");
        TestDatabaseHelper.createTestTable(pgAdapter, "cross_target");
        TestDatabaseHelper.insertStandardData(mysqlAdapter, "cross_source");
        TestDatabaseHelper.insertStandardData(pgAdapter, "cross_target");

        DifferConfig config = new DifferConfig(4, 100, false,
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
                .database(pgAdapter)
                .tablePath(TablePath.of("public", "cross_target"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
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
    @DisplayName("跨数据库应检测到数据差异")
    void shouldDetectDifferencesAcrossDatabases() throws Exception {
        TestDatabaseHelper.createTestTable(mysqlAdapter, "cross_diff_source");
        TestDatabaseHelper.createTestTable(pgAdapter, "cross_diff_target");
        TestDatabaseHelper.insertStandardData(mysqlAdapter, "cross_diff_source");
        TestDatabaseHelper.insertStandardData(pgAdapter, "cross_diff_target");

        // Modify row 4 on the PostgreSQL side
        TestDatabaseHelper.updateRow(pgAdapter, "cross_diff_target", 4, "pg_modified", 444.44);
        // Insert row 11 on the PostgreSQL side
        TestDatabaseHelper.insertExtraRow(pgAdapter, "cross_diff_target", 11, "pg_extra", 111.11);

        DifferConfig config = new DifferConfig(4, 100, false,
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
                .database(pgAdapter)
                .tablePath(TablePath.of("public", "cross_diff_target"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
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
    @DisplayName("跨数据库源端多数据应检测到 TARGET_MISSING")
    void shouldDetectTargetMissingAcrossDatabases() throws Exception {
        TestDatabaseHelper.createTestTable(mysqlAdapter, "cross_target_miss_src");
        TestDatabaseHelper.createTestTable(pgAdapter, "cross_target_miss_tgt");
        TestDatabaseHelper.insertStandardData(mysqlAdapter, "cross_target_miss_src");
        TestDatabaseHelper.insertStandardData(pgAdapter, "cross_target_miss_tgt");

        // Insert an extra row on the source (MySQL) side
        TestDatabaseHelper.insertExtraRow(mysqlAdapter, "cross_target_miss_src", 11, "mysql_only", 222.22);

        DifferConfig config = new DifferConfig(4, 100, false,
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
                .database(pgAdapter)
                .tablePath(TablePath.of("public", "cross_target_miss_tgt"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
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
}
