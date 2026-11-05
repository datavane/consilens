package com.consilens.core.algorithm;

import com.consilens.connector.api.model.DataType;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两侧键列物理类型不同（INT vs VARCHAR）时，主键必须按归一化值配对。
 *
 * <p>背景：dq_cmp_typediff_* 场景（A 侧 id INT / B 侧 id VARCHAR）在 checksum 模式下降级到
 * 本地比较后，曾把 86~284 行全部误报为单侧缺失——原始键值 HashMap 中 Integer(21) 与
 * String("21") 不相等。
 */
public class LocalDiffEngineKeyTypeTest {

    private static Object[] row(Object key, Object... extra) {
        List<Object> values = new ArrayList<>();
        values.add(key);
        values.addAll(Arrays.asList(extra));
        return values.toArray();
    }

    @Test
    public void intKeyMatchesStringKey() {
        List<Object[]> rows1 = List.<Object[]>of(row(1, 10, "a"), row(2, 20, "b"), row(21, 30, "c"));
        List<Object[]> rows2 = List.<Object[]>of(row("1", 10, "a"), row("2", 20, "b"), row("21", 30, "c"));

        Map<String, DataType> types1 = new HashMap<>();
        types1.put("id", DataType.INTEGER);
        types1.put("v_int", DataType.INTEGER);
        types1.put("v_str", DataType.VARCHAR);
        Map<String, DataType> types2 = new HashMap<>();
        types2.put("id", DataType.VARCHAR);
        types2.put("v_int", DataType.INTEGER);
        types2.put("v_str", DataType.VARCHAR);

        List<DiffRow> differences = LocalDiffEngine.findDifferences(
                rows1, rows2,
                List.of("id"), List.of("v_int", "v_str"),
                List.of("id"), List.of("v_int", "v_str"),
                types1, types2);

        assertTrue(differences.isEmpty(), "类型不同但取值相同的键不应产生差异: " + differences);
    }

    @Test
    public void intKeyWithRealValueDifferenceStillReported() {
        List<Object[]> rows1 = List.<Object[]>of(row(1, 10, "a"), row(60, 111, "x"));
        List<Object[]> rows2 = List.<Object[]>of(row("1", 10, "a"), row("60", 112, "x"));

        Map<String, DataType> types1 = new HashMap<>();
        types1.put("id", DataType.INTEGER);
        types1.put("v_int", DataType.INTEGER);
        types1.put("v_str", DataType.VARCHAR);
        Map<String, DataType> types2 = new HashMap<>();
        types2.put("id", DataType.VARCHAR);
        types2.put("v_int", DataType.INTEGER);
        types2.put("v_str", DataType.VARCHAR);

        List<DiffRow> differences = LocalDiffEngine.findDifferences(
                rows1, rows2,
                List.of("id"), List.of("v_int", "v_str"),
                List.of("id"), List.of("v_int", "v_str"),
                types1, types2);

        assertEquals(1, differences.size(), "真实值差异必须且只能报一条: " + differences);
        assertEquals(DiffOperation.MISMATCH, differences.get(0).getOperation());
        assertEquals("60", String.valueOf(differences.get(0).getPrimaryKey().get(0)));
    }

    @Test
    public void oneSidedKeysStillReported() {
        List<Object[]> rows1 = List.<Object[]>of(row(1000, 10, "a"));
        List<Object[]> rows2 = List.<Object[]>of(row("1001", 10, "a"));

        Map<String, DataType> types1 = new HashMap<>();
        types1.put("id", DataType.INTEGER);
        Map<String, DataType> types2 = new HashMap<>();
        types2.put("id", DataType.VARCHAR);

        List<DiffRow> differences = LocalDiffEngine.findDifferences(
                rows1, rows2,
                List.of("id"), List.of("v_int", "v_str"),
                List.of("id"), List.of("v_int", "v_str"),
                types1, types2);

        assertEquals(2, differences.size(), "真正的单侧缺失仍要报出: " + differences);
        assertTrue(differences.stream().anyMatch(d -> d.getOperation() == DiffOperation.TARGET_MISSING));
        assertTrue(differences.stream().anyMatch(d -> d.getOperation() == DiffOperation.SOURCE_MISSING));
    }
}
