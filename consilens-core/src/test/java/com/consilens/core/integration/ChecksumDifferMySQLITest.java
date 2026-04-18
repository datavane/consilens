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
import org.testcontainers.containers.MySQLContainer;
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
 * Integration test for ChecksumDiffer in same-database (MySQL) mode.
 *
 * <p>Uses Testcontainers to launch a real MySQL container and verifies
 * ChecksumDiffer correctness in a real database environment.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("ChecksumDiffer MySQL 集成测试")
class ChecksumDifferMySQLITest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("consilens_test")
            .withUsername("test")
            .withPassword("test123")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    private static DatabaseAdapter sourceAdapter;
    private static DatabaseAdapter targetAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        sourceAdapter = TestDatabaseHelper.createAdapter(
                "source", MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword(), "mysql", ChecksumAlgorithm.CONCAT);
        targetAdapter = TestDatabaseHelper.createAdapter(
                "target", MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword(), "mysql", ChecksumAlgorithm.CONCAT);
    }

    @AfterAll
    static void tearDown() {
        if (sourceAdapter != null) {
            sourceAdapter.close();
        }
        if (targetAdapter != null) {
            targetAdapter.close();
        }
    }

    @Test
    @DisplayName("相同数据的两张表应无差异")
    void identicalTablesShouldHaveNoDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, "source_identical");
        TestDatabaseHelper.createTestTable(targetAdapter, "target_identical");
        TestDatabaseHelper.insertStandardData(sourceAdapter, "source_identical");
        TestDatabaseHelper.insertStandardData(targetAdapter, "target_identical");

        DifferConfig config = new DifferConfig(4, 100, false,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of("consilens_test", "source_identical"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of("consilens_test", "target_identical"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isFalse();
            assertThat(result.getDifferences()).isEmpty();
            assertThat(result.getStatistics().getSourceRowCount()).isEqualTo(10);
            assertThat(result.getStatistics().getTargetRowCount()).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("目标表多一行应检测到 SOURCE_MISSING")
    void shouldDetectAddedRows() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, "source_added");
        TestDatabaseHelper.createTestTable(targetAdapter, "target_added");
        TestDatabaseHelper.insertStandardData(sourceAdapter, "source_added");
        TestDatabaseHelper.insertStandardData(targetAdapter, "target_added");
        TestDatabaseHelper.insertExtraRow(targetAdapter, "target_added", 11, "extra_item", 999.99);

        DifferConfig config = new DifferConfig(4, 100, false,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of("consilens_test", "source_added"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of("consilens_test", "target_added"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isTrue();

            // Detect source-side missing records (target has an extra row)
            List<DiffRow> sourceMissing = result.getDifferences().stream()
                    .filter(r -> r.getOperation() == DiffOperation.SOURCE_MISSING)
                    .collect(Collectors.toList());
            assertThat(sourceMissing).isNotEmpty();
        }
    }

    @Test
    @DisplayName("源表删除一行应检测到 TARGET_MISSING")
    void shouldDetectRemovedRows() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, "source_removed");
        TestDatabaseHelper.createTestTable(targetAdapter, "target_removed");
        TestDatabaseHelper.insertStandardData(sourceAdapter, "source_removed");
        TestDatabaseHelper.insertStandardData(targetAdapter, "target_removed");
        TestDatabaseHelper.deleteRow(targetAdapter, "target_removed", 5);

        DifferConfig config = new DifferConfig(4, 100, false,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of("consilens_test", "source_removed"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of("consilens_test", "target_removed"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isTrue();

            List<DiffRow> targetMissing = result.getDifferences().stream()
                    .filter(r -> r.getOperation() == DiffOperation.TARGET_MISSING)
                    .collect(Collectors.toList());
            assertThat(targetMissing).hasSize(1);
            assertThat(targetMissing.get(0).getPrimaryKey().get(0).toString()).isEqualTo("5");
        }
    }

    @Test
    @DisplayName("修改行数据应检测到 MISMATCH")
    void shouldDetectModifiedRows() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, "source_modified");
        TestDatabaseHelper.createTestTable(targetAdapter, "target_modified");
        TestDatabaseHelper.insertStandardData(sourceAdapter, "source_modified");
        TestDatabaseHelper.insertStandardData(targetAdapter, "target_modified");
        TestDatabaseHelper.updateRow(targetAdapter, "target_modified", 3, "modified_item", 999.99);
        TestDatabaseHelper.updateRow(targetAdapter, "target_modified", 7, "another_modified", 888.88);

        DifferConfig config = new DifferConfig(4, 100, false,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of("consilens_test", "source_modified"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of("consilens_test", "target_modified"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isTrue();

            List<DiffRow> mismatches = result.getDifferences().stream()
                    .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                    .collect(Collectors.toList());
            assertThat(mismatches).hasSize(2);

            List<Object> mismatchKeys = mismatches.stream()
                    .map(r -> r.getPrimaryKey().get(0).toString())
                    .collect(Collectors.toList());
            assertThat(mismatchKeys).containsExactlyInAnyOrder("3", "7");
        }
    }

    @Test
    @DisplayName("混合差异（增删改）应全部检测到")
    void shouldDetectMixedDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, "source_mixed");
        TestDatabaseHelper.createTestTable(targetAdapter, "target_mixed");
        TestDatabaseHelper.insertStandardData(sourceAdapter, "source_mixed");
        TestDatabaseHelper.insertStandardData(targetAdapter, "target_mixed");

        // Modify row 2 in target table
        TestDatabaseHelper.updateRow(targetAdapter, "target_mixed", 2, "changed", 0.01);
        // Delete row 8 from target table
        TestDatabaseHelper.deleteRow(targetAdapter, "target_mixed", 8);
        // Insert row 11 into target table
        TestDatabaseHelper.insertExtraRow(targetAdapter, "target_mixed", 11, "new_item", 110.00);

        DifferConfig config = new DifferConfig(4, 100, false,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of("consilens_test", "source_mixed"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of("consilens_test", "target_mixed"))
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
            assertThat(byOp.getOrDefault(DiffOperation.MISMATCH, List.of())).hasSizeGreaterThanOrEqualTo(1);
            assertThat(byOp.getOrDefault(DiffOperation.TARGET_MISSING, List.of())).hasSizeGreaterThanOrEqualTo(1);
            assertThat(byOp.getOrDefault(DiffOperation.SOURCE_MISSING, List.of())).hasSizeGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("空表对比应无差异")
    void emptyTablesShouldHaveNoDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, "source_empty");
        TestDatabaseHelper.createTestTable(targetAdapter, "target_empty");

        DifferConfig config = new DifferConfig(4, 100, false,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of("consilens_test", "source_empty"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of("consilens_test", "target_empty"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .build();

        try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isFalse();
        }
    }
}
