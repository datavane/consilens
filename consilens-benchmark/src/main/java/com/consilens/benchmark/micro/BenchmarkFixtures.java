package com.consilens.benchmark.micro;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 确定性构造行集与差异集，供微基准与单元测试复用。
 *
 * 行布局与 {@code LocalDiffEngine.findDifferences} 入参一致：
 * 前 {@code keyColumns} 列为主键，其后 {@code extraColumns} 列为比对列。
 */
public final class BenchmarkFixtures {

    private BenchmarkFixtures() {
    }

    /**
     * 构造 {@code count} 行、确定性随机的行集。每列为 long 值。
     *
     * @param count        行数
     * @param seed         随机种子，相同种子产出相同行集
     * @param keyColumns   主键列数（置于行首）
     * @param extraColumns 比对列数（置于主键之后）
     * @return 行集
     */
    public static List<Object[]> buildRows(int count, long seed, int keyColumns, int extraColumns) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        if (keyColumns < 0 || extraColumns < 0) {
            throw new IllegalArgumentException("column counts must be >= 0");
        }
        Random rnd = new Random(seed);
        int width = keyColumns + extraColumns;
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object[] row = new Object[width];
            // 主键列：单调递增，保证唯一，避免触发 LocalDiffEngine 的重复 PK 冲突分支
            for (int k = 0; k < keyColumns; k++) {
                row[k] = (k == 0) ? (long) i : (long) (i * 31 + k);
            }
            // extra 列：确定性随机
            for (int e = 0; e < extraColumns; e++) {
                row[keyColumns + e] = rnd.nextLong();
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 基于 {@code source} 产出一份修改了 extra 列的副本，按 {@code ratio} 比例改变行。
     * 主键保持不变，保证与 source 行一一对应。
     *
     * @param source 原始行集
     * @param ratio  被修改的行占比，区间 [0,1]
     * @param seed   选择被修改行与新值的随机种子
     * @return 修改后的行集，与 source 等长、同主键
     */
    public static List<Object[]> withDifferences(List<Object[]> source, double ratio, long seed) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (ratio < 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException("ratio must be in [0,1]");
        }
        Random rnd = new Random(seed);
        int width = source.isEmpty() ? 0 : source.get(0).length;
        List<Object[]> out = new ArrayList<>(source.size());
        for (Object[] row : source) {
            Object[] copy = new Object[width];
            System.arraycopy(row, 0, copy, 0, width);
            out.add(copy);
        }
        for (Object[] row : out) {
            if (width > 0 && rnd.nextDouble() < ratio) {
                // 修改最后一个 extra 列，保证至少一列变化
                int last = width - 1;
                row[last] = rnd.nextLong();
            }
        }
        return out;
    }
}
