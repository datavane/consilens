package com.consilens.core.algorithm;

import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.normalization.NormalizationSpec;
import com.consilens.connector.api.normalization.NormalizationSpecs;
import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDiffEngineToleranceTest {

    @Test
    void shouldTreatValuesWithinConfiguredToleranceAsEqual() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.6)), null, null);

        List<DiffRow> differences = diff(spec, 100.0, 100.5);

        assertTrue(differences.isEmpty(), "0.5 差异在 0.6 绝对容差内应判等");
    }

    @Test
    void shouldReportDifferenceBeyondConfiguredTolerance() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.1)), null, null);

        List<DiffRow> differences = diff(spec, 100.0, 100.5);

        assertEquals(1, differences.size());
        assertEquals(DiffRow.class, differences.get(0).getClass());
        assertTrue(differences.get(0).getChangedColumns1().contains("amount"));
    }

    @Test
    void shouldApplyColumnLevelToleranceOverTypeLevel() {
        NormalizationSpec typeLevel = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.1)), null, null);
        NormalizationSpec spec = NormalizationSpecs.withColumnRules(typeLevel,
                Map.of("amount", Map.of("tolerance", 0.6)));

        assertTrue(diff(spec, 100.0, 100.5).isEmpty());
    }

    @Test
    void shouldKeepIntegerColumnsExactEvenWithToleranceRule() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("integer", Map.of("tolerance", 10)), null, null);

        List<DiffRow> differences = diff(spec, 100.0, 101.0);

        assertEquals(1, differences.size(), "整数列必须精确比较");
    }

    @Test
    void shouldApplyToleranceToSqlRenderedNumericText() {
        // 根因回归（P1-5）：checksum 路径的行值是 SQL 归一化后的文本（如 "10.500000"），
        // 原实现只认 Number，容差配置解析成功却永远不参与判定。
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.01)), null, null);

        List<DiffRow> differences = diffStrings(spec, "10.500000", "10.504000");

        assertTrue(differences.isEmpty(), "字符串形态的 0.004 差异在 0.01 容差内应判等");
    }

    @Test
    void shouldStillReportDifferenceBeyondToleranceForNumericText() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.01)), null, null);

        List<DiffRow> differences = diffStrings(spec, "10.500000", "10.530000");

        assertEquals(1, differences.size(), "超容差必须仍然报差异，不能修成假一致");
        assertTrue(differences.get(0).getChangedColumns1().contains("amount"));
    }

    @Test
    void shouldApplyRelativeToleranceToNumericText() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("relativeTolerance", 0.001)), null, null);

        // 相对差 0.0004 <= 0.001
        assertTrue(diffStrings(spec, "1000.0", "1000.4").isEmpty());
        // 相对差 0.002 > 0.001
        assertEquals(1, diffStrings(spec, "1000.0", "1002.0").size());
    }

    @Test
    void shouldKeepIntegerColumnsExactForNumericText() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("integer", Map.of("tolerance", 10)), null, null);

        List<DiffRow> differences = LocalDiffEngine.findDifferencesWithTolerance(
                List.<Object[]>of(new Object[]{"1", "100"}),
                List.<Object[]>of(new Object[]{"1", "101"}),
                List.of("id"), List.of("amount"),
                List.of("id"), List.of("amount"),
                Map.of("id", DataType.BIGINT, "amount", DataType.BIGINT),
                Map.of("id", DataType.BIGINT, "amount", DataType.BIGINT),
                null, null, spec);

        assertEquals(1, differences.size(), "整数列即使配了容差也必须精确比较");
    }

    @Test
    void shouldFallBackToTextComparisonWhenValuesAreNotNumeric() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.01)), null, null);

        List<DiffRow> differences = diffStrings(spec, "n/a", "10.504000");

        assertEquals(1, differences.size(), "读不成数值时回落普通比较，不能吞掉差异，也不能抛异常");
    }

    @Test
    void shouldCaptureWithinToleranceRowsWithoutCountingThemAsDifferences() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.01)), null, null);

        LocalDiffEngine.ToleranceOutcome outcome = LocalDiffEngine.findDifferencesWithToleranceOutcome(
                List.<Object[]>of(new Object[]{"1", "10.500000"}),
                List.<Object[]>of(new Object[]{"1", "10.504000"}),
                List.of("id"), List.of("amount"),
                List.of("id"), List.of("amount"),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                null, null, spec, true);

        assertTrue(outcome.getDifferences().isEmpty());
        assertEquals(1, outcome.getWithinTolerance().size());
        DiffRow row = outcome.getWithinTolerance().get(0);
        assertTrue(row.isWithinTolerance());
        assertEquals(List.of("amount"), row.getMetadata().get(DiffRow.METADATA_TOLERANCE_COLUMNS));
        assertEquals(0.004, (Double) ((Map<?, ?>) row.getMetadata()
                .get(DiffRow.METADATA_TOLERANCE_DELTAS)).get("amount"), 1e-9);
    }

    @Test
    void shouldNotCaptureWhenCallerDoesNotAskForWithinToleranceRows() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.01)), null, null);

        LocalDiffEngine.ToleranceOutcome outcome = LocalDiffEngine.findDifferencesWithToleranceOutcome(
                List.<Object[]>of(new Object[]{"1", "10.500000"}),
                List.<Object[]>of(new Object[]{"1", "10.504000"}),
                List.of("id"), List.of("amount"),
                List.of("id"), List.of("amount"),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                null, null, spec, false);

        assertTrue(outcome.getDifferences().isEmpty());
        assertTrue(outcome.getWithinTolerance().isEmpty(), "未要求采集时不应额外构造行");
    }

    @Test
    void shouldNotFlagRenderingOnlyDifferencesAsWithinTolerance() {
        // 10.5 与 10.500 数值相同，只是渲染位数不同：不是"被容差吸收"，不该出现在审计样本里
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.01)), null, null);

        LocalDiffEngine.ToleranceOutcome outcome = LocalDiffEngine.findDifferencesWithToleranceOutcome(
                List.<Object[]>of(new Object[]{"1", "10.5"}),
                List.<Object[]>of(new Object[]{"1", "10.500"}),
                List.of("id"), List.of("amount"),
                List.of("id"), List.of("amount"),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                null, null, spec, true);

        assertTrue(outcome.getDifferences().isEmpty());
        assertTrue(outcome.getWithinTolerance().isEmpty());
    }

    @Test
    void shouldNotRelaxDecimalTextWithTolerance() {
        // DECIMAL 语义要求精确比较：即使配了容差规则，文本形态也不能按浮点放宽
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("decimal", Map.of("tolerance", 1000)), null, null);

        List<DiffRow> differences = LocalDiffEngine.findDifferencesWithTolerance(
                List.<Object[]>of(new Object[]{"1", "10.50"}),
                List.<Object[]>of(new Object[]{"1", "10.504"}),
                List.of("id"), List.of("amount"),
                List.of("id"), List.of("amount"),
                Map.of("id", DataType.BIGINT, "amount", DataType.DECIMAL),
                Map.of("id", DataType.BIGINT, "amount", DataType.DECIMAL),
                null, null, spec);

        assertEquals(1, differences.size(), "DECIMAL 列不能因为容差配置被放宽");
    }

    @Test
    void shouldNotTreatUnknownTypeTextAsNumericForTolerance() {
        // schema 不可用时类型是 UNKNOWN：文本不按数值比，否则会顺带放宽 DECIMAL 列
        NormalizationSpec spec = NormalizationSpecs.withColumnRules(
                NormalizationSpecs.fromTypeRules(null, null, null),
                Map.of("amount", Map.of("tolerance", 100)));

        List<DiffRow> differences = LocalDiffEngine.findDifferencesWithTolerance(
                List.<Object[]>of(new Object[]{"1", "10.50"}),
                List.<Object[]>of(new Object[]{"1", "10.504"}),
                List.of("id"), List.of("amount"),
                List.of("id"), List.of("amount"),
                Map.of("id", DataType.BIGINT, "amount", DataType.UNKNOWN),
                Map.of("id", DataType.BIGINT, "amount", DataType.UNKNOWN),
                null, null, spec);

        assertEquals(1, differences.size(), "类型未知的文本不能按数值参与容差");
    }

    @Test
    void shouldRecordTargetColumnNamesSeparatelyForRenamedMappings() {
        NormalizationSpec spec = NormalizationSpecs.withColumnRules(
                NormalizationSpecs.fromTypeRules(null, null, null),
                Map.of("amount_src", Map.of("tolerance", 0.01)));

        LocalDiffEngine.ToleranceOutcome outcome = LocalDiffEngine.findDifferencesWithToleranceOutcome(
                List.<Object[]>of(new Object[]{"1", "10.500000"}),
                List.<Object[]>of(new Object[]{"1", "10.504000"}),
                List.of("id"), List.of("amount_src"),
                List.of("id"), List.of("amount_tgt"),
                Map.of("id", DataType.BIGINT, "amount_src", DataType.DOUBLE),
                Map.of("id", DataType.BIGINT, "amount_tgt", DataType.DOUBLE),
                null, null, spec, true);

        assertEquals(1, outcome.getWithinTolerance().size());
        DiffRow row = outcome.getWithinTolerance().get(0);
        assertEquals(List.of("amount_src"), row.getChangedColumns1());
        assertEquals(List.of("amount_tgt"), row.getChangedColumns2(), "改名映射下两侧列名必须各写各的");
    }

    private static List<DiffRow> diffStrings(NormalizationSpec spec, String sourceValue, String targetValue) {
        return LocalDiffEngine.findDifferencesWithTolerance(
                List.<Object[]>of(new Object[]{"1", sourceValue}),
                List.<Object[]>of(new Object[]{"1", targetValue}),
                List.of("id"), List.of("amount"),
                List.of("id"), List.of("amount"),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                null, null, spec);
    }

    private static List<DiffRow> diff(NormalizationSpec spec, double sourceValue, double targetValue) {
        List<Object[]> rows1 = List.<Object[]>of(new Object[]{1L, sourceValue});
        List<Object[]> rows2 = List.<Object[]>of(new Object[]{1L, targetValue});
        return LocalDiffEngine.findDifferencesWithTolerance(
                rows1, rows2,
                List.of("id"), List.of("amount"),
                List.of("id"), List.of("amount"),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                null, null, spec);
    }
}
