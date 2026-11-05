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
 * Integration test for ChecksumDiffer in same-database (Oracle) mode.
 *
 * <p>Uses Testcontainers to launch a real Oracle container and verifies
 * ChecksumDiffer correctness in a real Oracle database environment.
 *
 * <p>Note: Oracle stores unquoted identifiers as uppercase. All table names
 * must be passed in uppercase to match Oracle's data dictionary.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("ChecksumDiffer Oracle 集成测试")
class ChecksumDifferOracleITest {

    private static final String SCHEMA = "SYSTEM";

    // Oracle stores unquoted identifiers as uppercase
    private static final String TBL_SOURCE_IDENTICAL = "SRC_IDENTICAL";
    private static final String TBL_TARGET_IDENTICAL = "TGT_IDENTICAL";
    private static final String TBL_SOURCE_ADDED = "SRC_ADDED";
    private static final String TBL_TARGET_ADDED = "TGT_ADDED";
    private static final String TBL_SOURCE_REMOVED = "SRC_REMOVED";
    private static final String TBL_TARGET_REMOVED = "TGT_REMOVED";
    private static final String TBL_SOURCE_MODIFIED = "SRC_MODIFIED";
    private static final String TBL_TARGET_MODIFIED = "TGT_MODIFIED";
    private static final String TBL_SOURCE_MIXED = "SRC_MIXED";
    private static final String TBL_TARGET_MIXED = "TGT_MIXED";
    private static final String TBL_SOURCE_EMPTY = "SRC_EMPTY";
    private static final String TBL_TARGET_EMPTY = "TGT_EMPTY";

    @Container
    private static final GenericContainer<?> ORACLE = new GenericContainer<>(
            DockerImageName.parse("gvenzl/oracle-xe:21-slim-faststart"))
            .withEnv("ORACLE_PASSWORD", "test123")
            .withExposedPorts(1521)
            .waitingFor(new LogMessageWaitStrategy()
                    .withRegEx(".*DATABASE IS READY TO USE!.*\\s")
                    .withStartupTimeout(Duration.ofMinutes(5)));

    private static DatabaseAdapter sourceAdapter;
    private static DatabaseAdapter targetAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        String oracleHost = ORACLE.getHost();
        Integer oraclePort = ORACLE.getMappedPort(1521);
        String oracleUrl = "jdbc:oracle:thin:@" + oracleHost + ":" + oraclePort + ":XE";

        sourceAdapter = TestDatabaseHelper.createAdapter(
                "oracle-source", oracleUrl, "system",
                "test123", "oracle", ChecksumAlgorithm.CONCAT);
        targetAdapter = TestDatabaseHelper.createAdapter(
                "oracle-target", oracleUrl, "system",
                "test123", "oracle", ChecksumAlgorithm.CONCAT);
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
    @DisplayName("Oracle 相同数据的两张表应无差异")
    void identicalTablesShouldHaveNoDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, TBL_SOURCE_IDENTICAL);
        TestDatabaseHelper.createTestTable(targetAdapter, TBL_TARGET_IDENTICAL);
        TestDatabaseHelper.insertStandardData(sourceAdapter, TBL_SOURCE_IDENTICAL);
        TestDatabaseHelper.insertStandardData(targetAdapter, TBL_TARGET_IDENTICAL);

        DifferConfig config = new DifferConfig(4, 100,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_SOURCE_IDENTICAL))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_TARGET_IDENTICAL))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
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
    @DisplayName("Oracle 目标表多一行应检测到 SOURCE_MISSING")
    void shouldDetectAddedRows() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, TBL_SOURCE_ADDED);
        TestDatabaseHelper.createTestTable(targetAdapter, TBL_TARGET_ADDED);
        TestDatabaseHelper.insertStandardData(sourceAdapter, TBL_SOURCE_ADDED);
        TestDatabaseHelper.insertStandardData(targetAdapter, TBL_TARGET_ADDED);
        TestDatabaseHelper.insertExtraRow(targetAdapter, TBL_TARGET_ADDED, 11, "extra_item", 999.99);

        DifferConfig config = new DifferConfig(4, 100,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_SOURCE_ADDED))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_TARGET_ADDED))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
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
    @DisplayName("Oracle 源表删除一行应检测到 TARGET_MISSING")
    void shouldDetectRemovedRows() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, TBL_SOURCE_REMOVED);
        TestDatabaseHelper.createTestTable(targetAdapter, TBL_TARGET_REMOVED);
        TestDatabaseHelper.insertStandardData(sourceAdapter, TBL_SOURCE_REMOVED);
        TestDatabaseHelper.insertStandardData(targetAdapter, TBL_TARGET_REMOVED);
        TestDatabaseHelper.deleteRow(targetAdapter, TBL_TARGET_REMOVED, 5);

        DifferConfig config = new DifferConfig(4, 100,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_SOURCE_REMOVED))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_TARGET_REMOVED))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
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
    @DisplayName("Oracle 修改行数据应检测到 MISMATCH")
    void shouldDetectModifiedRows() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, TBL_SOURCE_MODIFIED);
        TestDatabaseHelper.createTestTable(targetAdapter, TBL_TARGET_MODIFIED);
        TestDatabaseHelper.insertStandardData(sourceAdapter, TBL_SOURCE_MODIFIED);
        TestDatabaseHelper.insertStandardData(targetAdapter, TBL_TARGET_MODIFIED);
        TestDatabaseHelper.updateRow(targetAdapter, TBL_TARGET_MODIFIED, 3, "modified_item", 999.99);
        TestDatabaseHelper.updateRow(targetAdapter, TBL_TARGET_MODIFIED, 7, "another_modified", 888.88);

        DifferConfig config = new DifferConfig(4, 100,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_SOURCE_MODIFIED))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_TARGET_MODIFIED))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
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
    @DisplayName("Oracle 混合差异（增删改）应全部检测到")
    void shouldDetectMixedDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, TBL_SOURCE_MIXED);
        TestDatabaseHelper.createTestTable(targetAdapter, TBL_TARGET_MIXED);
        TestDatabaseHelper.insertStandardData(sourceAdapter, TBL_SOURCE_MIXED);
        TestDatabaseHelper.insertStandardData(targetAdapter, TBL_TARGET_MIXED);

        // Modify row 2 in target table
        TestDatabaseHelper.updateRow(targetAdapter, TBL_TARGET_MIXED, 2, "changed", 0.01);
        // Delete row 8 from target table
        TestDatabaseHelper.deleteRow(targetAdapter, TBL_TARGET_MIXED, 8);
        // Insert row 11 into target table
        TestDatabaseHelper.insertExtraRow(targetAdapter, TBL_TARGET_MIXED, 11, "new_item", 110.00);

        DifferConfig config = new DifferConfig(4, 100,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_SOURCE_MIXED))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_TARGET_MIXED))
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
            assertThat(byOp.getOrDefault(DiffOperation.MISMATCH, List.of())).hasSizeGreaterThanOrEqualTo(1);
            assertThat(byOp.getOrDefault(DiffOperation.TARGET_MISSING, List.of())).hasSizeGreaterThanOrEqualTo(1);
            assertThat(byOp.getOrDefault(DiffOperation.SOURCE_MISSING, List.of())).hasSizeGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("Oracle 空表对比应无差异")
    void emptyTablesShouldHaveNoDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, TBL_SOURCE_EMPTY);
        TestDatabaseHelper.createTestTable(targetAdapter, TBL_TARGET_EMPTY);

        DifferConfig config = new DifferConfig(4, 100,
                ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_SOURCE_EMPTY))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of(SCHEMA, TBL_TARGET_EMPTY))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .build();

        try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
            DiffResult result = differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);

            assertThat(result).isNotNull();
            assertThat(result.hasDifferences()).isFalse();
        }
    }
}
