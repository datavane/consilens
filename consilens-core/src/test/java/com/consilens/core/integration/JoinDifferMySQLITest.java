package com.consilens.core.integration;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.TablePath;
import com.consilens.core.algorithm.JoinDiffer;
import com.consilens.core.algorithm.JoinDiffer.JoinDifferOptions;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for JoinDiffer in same-database (MySQL) mode.
 *
 * <p>JoinDiffer requires source and target tables to be in the same database instance
 * and performs comparison via SQL JOIN directly on the database side.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("JoinDiffer MySQL 集成测试")
class JoinDifferMySQLITest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("consilens_join_test")
            .withUsername("test")
            .withPassword("test123")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    private static DatabaseAdapter adapter;

    @BeforeAll
    static void setUp() throws Exception {
        adapter = TestDatabaseHelper.createAdapter(
                "join-test", MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword(), DatabaseType.MYSQL, ChecksumAlgorithm.CONCAT);
    }

    @AfterAll
    static void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
    }

    @Test
    @DisplayName("同库相同数据 JOIN 对比应无差异")
    void identicalTablesJoinShouldHaveNoDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(adapter, "join_source");
        TestDatabaseHelper.createTestTable(adapter, "join_target");
        TestDatabaseHelper.insertStandardData(adapter, "join_source");
        TestDatabaseHelper.insertStandardData(adapter, "join_target");

        DifferConfig config = new DifferConfig(4, 100, false,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(adapter)
                .tablePath(TablePath.of("consilens_join_test", "join_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(adapter)
                .tablePath(TablePath.of("consilens_join_test", "join_target"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        try (JoinDiffer differ = new JoinDiffer(config, JoinDifferOptions.defaultOptions())) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isFalse();
        }
    }

    @Test
    @DisplayName("JOIN 模式应检测到增删改差异")
    void joinShouldDetectMixedDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(adapter, "join_mix_source");
        TestDatabaseHelper.createTestTable(adapter, "join_mix_target");
        TestDatabaseHelper.insertStandardData(adapter, "join_mix_source");
        TestDatabaseHelper.insertStandardData(adapter, "join_mix_target");

        // Modify target table row
        TestDatabaseHelper.updateRow(adapter, "join_mix_target", 3, "join_modified", 333.33);
        // Delete from target table
        TestDatabaseHelper.deleteRow(adapter, "join_mix_target", 6);
        // Insert into target table
        TestDatabaseHelper.insertExtraRow(adapter, "join_mix_target", 11, "join_extra", 110.00);

        DifferConfig config = new DifferConfig(4, 100, false,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(adapter)
                .tablePath(TablePath.of("consilens_join_test", "join_mix_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(adapter)
                .tablePath(TablePath.of("consilens_join_test", "join_mix_target"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        try (JoinDiffer differ = new JoinDiffer(config, JoinDifferOptions.defaultOptions())) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isTrue();

            Map<DiffOperation, List<DiffRow>> byOp = result.getDifferencesByOperation();

            // id=3 data modified → MISMATCH
            assertThat(byOp.getOrDefault(DiffOperation.MISMATCH, List.of())).hasSize(1);

            // id=6 target missing → TARGET_MISSING
            assertThat(byOp.getOrDefault(DiffOperation.TARGET_MISSING, List.of())).hasSize(1);

            // id=11 source missing → SOURCE_MISSING
            assertThat(byOp.getOrDefault(DiffOperation.SOURCE_MISSING, List.of())).hasSize(1);
        }
    }

    @Test
    @DisplayName("JOIN 模式空表对比应无差异")
    void joinEmptyTablesShouldHaveNoDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(adapter, "join_empty_source");
        TestDatabaseHelper.createTestTable(adapter, "join_empty_target");

        DifferConfig config = new DifferConfig(4, 100, false,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(adapter)
                .tablePath(TablePath.of("consilens_join_test", "join_empty_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(adapter)
                .tablePath(TablePath.of("consilens_join_test", "join_empty_target"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .build();

        try (JoinDiffer differ = new JoinDiffer(config, JoinDifferOptions.defaultOptions())) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isFalse();
        }
    }

    @Test
    @DisplayName("JOIN 模式单行表对比")
    void joinSingleRowTableComparison() throws Exception {
        TestDatabaseHelper.createTestTable(adapter, "join_single_source");
        TestDatabaseHelper.createTestTable(adapter, "join_single_target");
        TestDatabaseHelper.insertExtraRow(adapter, "join_single_source", 1, "only_row", 100.00);
        TestDatabaseHelper.insertExtraRow(adapter, "join_single_target", 1, "only_row", 100.00);

        DifferConfig config = new DifferConfig(4, 100, false,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(adapter)
                .tablePath(TablePath.of("consilens_join_test", "join_single_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(2)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(adapter)
                .tablePath(TablePath.of("consilens_join_test", "join_single_target"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(2)))
                .build();

        try (JoinDiffer differ = new JoinDiffer(config, JoinDifferOptions.defaultOptions())) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isFalse();
        }
    }
}
