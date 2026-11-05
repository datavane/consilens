package com.consilens.core.algorithm;

import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.normalization.MatchSpec;
import com.consilens.connector.api.normalization.NormalizationRule;
import com.consilens.connector.api.normalization.NormalizationSpec;
import com.consilens.connector.api.normalization.NormalizationSpecs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnToleranceResolverTest {

    @Test
    void shouldResolveTypeLevelToleranceForFloatColumns() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.01)), null, null);

        Map<String, ColumnTolerance> tolerances = ColumnToleranceResolver.resolve(
                spec,
                List.of("id", "price"),
                List.of("id", "price"),
                Map.of("id", DataType.BIGINT, "price", DataType.DOUBLE),
                Map.of("id", DataType.BIGINT, "price", DataType.DOUBLE));

        assertNotNull(tolerances.get("price"));
        assertTrue(tolerances.get("price").isEqual(1.000, 1.005));
        assertFalse(tolerances.get("price").isEqual(1.000, 1.1));
        assertNull(tolerances.get("id"), "整数键列不解析容差");
    }

    @Test
    void shouldResolveColumnLevelToleranceOverTypeLevel() {
        NormalizationSpec typeLevel = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.01)), null, null);
        NormalizationSpec spec = NormalizationSpecs.withColumnRules(typeLevel,
                Map.of("price", Map.of("tolerance", 0.5)));

        Map<String, ColumnTolerance> tolerances = ColumnToleranceResolver.resolve(
                spec,
                List.of("price"),
                List.of("price"),
                Map.of("price", DataType.DOUBLE),
                Map.of("price", DataType.DOUBLE));

        assertTrue(tolerances.get("price").isEqual(1.0, 1.4), "列级容差覆盖类型级");
        assertFalse(tolerances.get("price").isEqual(1.0, 1.6));
    }

    @Test
    void shouldRegisterBothSideNamesForMappedColumns() {
        NormalizationSpec spec = NormalizationSpecs.withColumnRules(null,
                Map.of("price", Map.of("tolerance", 0.1)));

        Map<String, ColumnTolerance> tolerances = ColumnToleranceResolver.resolve(
                spec,
                List.of("price"),
                List.of("amt"),
                Map.of("price", DataType.DOUBLE),
                Map.of("amt", DataType.DOUBLE));

        assertNotNull(tolerances.get("price"));
        assertNotNull(tolerances.get("amt"));
    }

    @Test
    void shouldReturnEmptyWithoutSpecOrRules() {
        assertTrue(ColumnToleranceResolver.resolve(null,
                List.of("a"), List.of("a"),
                Map.of("a", DataType.DOUBLE), Map.of("a", DataType.DOUBLE)).isEmpty());

        NormalizationSpec noTolerance = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("precision", 6)), null, null);
        assertTrue(ColumnToleranceResolver.resolve(noTolerance,
                List.of("a"), List.of("a"),
                Map.of("a", DataType.DOUBLE), Map.of("a", DataType.DOUBLE)).isEmpty());
    }

    @Test
    void shouldCompareWithRelativeTolerance() {
        ColumnTolerance relative = ColumnTolerance.builder().relative(0.01).build();

        assertTrue(relative.isEqual(100.0, 100.5), "0.5% 相对偏差在 1% 内");
        assertFalse(relative.isEqual(100.0, 102.0));
    }

    @Test
    void compiledColumnRulesUsePathMatch() {
        List<NormalizationRule> rules = NormalizationSpecs.toColumnRules(
                Map.of("price", Map.of("tolerance", 0.01, "precision", 4)));

        assertEquals(1, rules.size());
        assertEquals("price", rules.get(0).getMatch().getPath());
        assertNull(rules.get(0).getMatch().getType());
        assertEquals(0.01, ((Number) rules.get(0).getParams().get("tolerance")).doubleValue());
    }
}
