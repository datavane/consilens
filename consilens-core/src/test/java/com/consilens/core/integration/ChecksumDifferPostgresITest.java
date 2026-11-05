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
 * Integration test for ChecksumDiffer in same-database (PostgreSQL) mode.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("ChecksumDiffer PostgreSQL 集成测试")
class ChecksumDifferPostgresITest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("consilens_test")
            .withUsername("test")
            .withPassword("test123");

    private static DatabaseAdapter sourceAdapter;
    private static DatabaseAdapter targetAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        sourceAdapter = CrossDatabaseITestBase.createAdapter("pg-source", POSTGRES, "postgresql");
        targetAdapter = CrossDatabaseITestBase.createAdapter("pg-target", POSTGRES, "postgresql");
    }

    @AfterAll
    static void tearDown() {
        if (sourceAdapter != null) sourceAdapter.close();
        if (targetAdapter != null) targetAdapter.close();
    }

    @Test
    @DisplayName("PostgreSQL 相同数据的两张表应无差异")
    void identicalTablesShouldHaveNoDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, "source_identical");
        TestDatabaseHelper.createTestTable(targetAdapter, "target_identical");
        TestDatabaseHelper.insertStandardData(sourceAdapter, "source_identical");
        TestDatabaseHelper.insertStandardData(targetAdapter, "target_identical");

        DifferConfig config = new DifferConfig(4, 100, ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of("public", "source_identical"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of("public", "target_identical"))
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
    @DisplayName("PostgreSQL 修改行数据应检测到 MISMATCH")
    void shouldDetectModifiedRows() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, "source_modified");
        TestDatabaseHelper.createTestTable(targetAdapter, "target_modified");
        TestDatabaseHelper.insertStandardData(sourceAdapter, "source_modified");
        TestDatabaseHelper.insertStandardData(targetAdapter, "target_modified");
        TestDatabaseHelper.updateRow(targetAdapter, "target_modified", 3, "modified_item", 999.99);
        TestDatabaseHelper.updateRow(targetAdapter, "target_modified", 7, "another_modified", 888.88);

        DifferConfig config = new DifferConfig(4, 100, ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of("public", "source_modified"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of("public", "target_modified"))
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
    @DisplayName("PostgreSQL 空表对比应无差异")
    void emptyTablesShouldHaveNoDifferences() throws Exception {
        TestDatabaseHelper.createTestTable(sourceAdapter, "source_empty");
        TestDatabaseHelper.createTestTable(targetAdapter, "target_empty");

        DifferConfig config = new DifferConfig(4, 100, ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(sourceAdapter)
                .tablePath(TablePath.of("public", "source_empty"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(targetAdapter)
                .tablePath(TablePath.of("public", "target_empty"))
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
