package com.consilens.core.algorithm;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.core.compare.WithinToleranceMode;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容差内行的计数口径：默认不计入差异，只在报告里单列；REPORT 口径原样计入。
 */
class ChecksumDifferWithinToleranceTest {

    private static final List<String> COLUMNS = List.of("id", "amount");

    @Test
    void ignoreModeExcludesWithinToleranceRowsFromDifferences() {
        try (ChecksumDiffer differ = new ChecksumDiffer(config(WithinToleranceMode.IGNORE, 100))) {
            List<DiffRow> partitioned = differ.partitionWithinTolerance(List.of(realDiff(), withinRow()));

            assertEquals(1, partitioned.size(), "只有真差异留在差异列表里");
            assertEquals(DiffOperation.MISMATCH, partitioned.get(0).getOperation());
            assertFalse(partitioned.get(0).isWithinTolerance());
            assertEquals(1, differ.withinToleranceCount());
            assertEquals(1, differ.withinToleranceRows().size());
        }
    }

    @Test
    void reportModeKeepsWithinToleranceRowsAsDifferences() {
        try (ChecksumDiffer differ = new ChecksumDiffer(config(WithinToleranceMode.REPORT, 100))) {
            List<DiffRow> differences = new java.util.ArrayList<>();
            differ.storeWithinToleranceRows(new com.consilens.core.diff.InfoTreeRecorder("root"),
                    differences, List.of(withinRow()), "seg-1");
            // 并入后仍会走 storeDifferences 的计数，这里只验证"并入"这一步
            List<DiffRow> partitioned = differ.partitionWithinTolerance(differences);

            assertEquals(1, partitioned.size(), "REPORT 是审计档：容差内仍按差异计数");
            assertEquals(1, differ.withinToleranceCount(), "仍要能统计出这几行是容差吸收的");
            assertEquals(1, differ.withinToleranceRows().size());
        }
    }

    @Test
    void ignoreModeDoesNotAddWithinToleranceRowsToDifferences() {
        try (ChecksumDiffer differ = new ChecksumDiffer(config(WithinToleranceMode.IGNORE, 100))) {
            List<DiffRow> differences = new java.util.ArrayList<>();
            differ.storeWithinToleranceRows(new com.consilens.core.diff.InfoTreeRecorder("root"),
                    differences, List.of(withinRow()), "seg-1");

            assertTrue(differences.isEmpty(), "默认口径下容差内行不能进入差异列表");
            assertEquals(1, differ.withinToleranceCount());
        }
    }

    @Test
    void samplesAreCappedButCountKeepsAccumulating() {
        try (ChecksumDiffer differ = new ChecksumDiffer(config(WithinToleranceMode.IGNORE, 2))) {
            for (int i = 0; i < 5; i++) {
                differ.partitionWithinTolerance(List.of(withinRow(i)));
            }

            assertEquals(5, differ.withinToleranceCount(), "计数不受样本上限影响");
            assertEquals(2, differ.withinToleranceRows().size(), "行样本按上限截断");
        }
    }

    @Test
    void zeroSamplesKeepsCountOnly() {
        try (ChecksumDiffer differ = new ChecksumDiffer(config(WithinToleranceMode.IGNORE, 0))) {
            differ.partitionWithinTolerance(List.of(withinRow()));

            assertEquals(1, differ.withinToleranceCount());
            assertTrue(differ.withinToleranceRows().isEmpty());
        }
    }

    @Test
    void duplicateWithinToleranceRowsAreCountedOnce() {
        try (ChecksumDiffer differ = new ChecksumDiffer(config(WithinToleranceMode.IGNORE, 100))) {
            differ.partitionWithinTolerance(List.of(withinRow()));
            differ.partitionWithinTolerance(List.of(withinRow()));

            assertEquals(1, differ.withinToleranceCount(), "同一行被两个分段各报一次时只计一次");
        }
    }

    private static TableDiffer.DifferConfig config(WithinToleranceMode mode, int samples) {
        return new TableDiffer.DifferConfig(4, 1000, ChecksumAlgorithm.CONCAT,
                com.consilens.common.enums.LocalCompareMode.FULL,
                com.consilens.core.thread.ConcurrencyConfig.defaultConfig(),
                1_000_000L, false, 4, false,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, null, mode, samples);
    }

    private static DiffRow realDiff() {
        return DiffRow.modified(List.of("1"), List.of("1", "10.500000"), List.of("1", "10.900000"),
                COLUMNS, COLUMNS);
    }

    private static DiffRow withinRow() {
        return withinRow(0);
    }

    private static DiffRow withinRow(int occurrence) {
        DiffRow row = DiffRow.withinTolerance(List.of("1"),
                List.of("1", "10.500000"), List.of("1", "10.504000"),
                COLUMNS, COLUMNS, List.of("amount"), List.of("amount"), Map.of("amount", 0.004));
        if (occurrence == 0) {
            return row;
        }
        return DiffRow.withExtraMetadata(row, Map.of("occurrence", occurrence + 1));
    }
}
