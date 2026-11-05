package com.consilens.core.algorithm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 统计行的数值解析：join 的行数预算与"匹配分"的分母都取自这里。
 *
 * <p>真实 MySQL 上 `SUM()` 经驱动/适配层拿到的是**字符串**形态的数字，早先只认
 * {@code instanceof Number}，整行被丢弃 → 行数恒为 0 → join 模式下 maxRowsScanned 完全不生效，
 * 且匹配分分母为 0 会给出"100 分 / 无差异"。
 */
class JoinDifferStatisticsParseTest {

    @Test
    void parsesNumbersInEveryShapeTheDriverHandsBack() {
        assertEquals(2000L, JoinDiffer.toLong(2000));
        assertEquals(2000L, JoinDiffer.toLong(2000L));
        assertEquals(2000L, JoinDiffer.toLong(new BigDecimal("2000")));
        assertEquals(2000L, JoinDiffer.toLong("2000"));
        assertEquals(2000L, JoinDiffer.toLong(" 2000 "));
    }

    @Test
    void nullCountsAsZeroButOnlyForStatistics() {
        // 空表时 SUM() 返回 NULL：这是"没有行"，不是"读不懂"，统计按 0 处理，
        // 否则"两侧都是空表"的比对会失败（ConcurrentHashMap 也不接受 null）。
        assertEquals(0L, JoinDiffer.toCount(null));
        assertEquals(2000L, JoinDiffer.toCount("2000"));
        // 有值但读不懂仍然返回 null，让调用方按"统计不可用"失败
        assertNull(JoinDiffer.toCount("n/a"));
        // toLong 保持严格语义（null 仍是 null），供非统计场景使用
        assertNull(JoinDiffer.toLong(null));
    }

    @Test
    void emptyTextIsZeroButGarbageIsUnreadable() {
        // 空串在 SQL 里等价于"没有值"，按 0 处理
        assertEquals(0L, JoinDiffer.toLong(""));
        // 无法解析时必须返回 null，让调用方按"统计不可用"失败，而不是悄悄当成 0
        assertNull(JoinDiffer.toLong("n/a"));
        assertNull(JoinDiffer.toLong(null));
        assertNull(JoinDiffer.toLong(new Object()));
    }
}
