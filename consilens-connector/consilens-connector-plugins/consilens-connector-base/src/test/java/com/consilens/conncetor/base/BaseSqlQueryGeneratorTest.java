package com.consilens.conncetor.base;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseSqlQueryGeneratorTest {

    private final BaseSqlQueryGenerator generator = new BaseSqlQueryGenerator(new TestCapabilityProvider()) {
        @Override
        public String getRowHashSQL(String schemaName, String tableName, List<String> primaryKeys,
                List<String> columns, Map<String, DataType> columnDataTypes, String whereClause) {
            return "";
        }
    };

    @Test
    void shouldCountMissingRowsInFullJoinFallback() {
        String sql = generator.getJoinDiffStatsSQL(
                "s1", "source_table", "s", List.of("id"), List.of("value"), null,
                "s2", "target_table", "t", List.of("id"), List.of("value"), null);

        // NULL 键三态：两侧键都为 NULL 的匹配对按普通行比较，不得同时计成 SM+TM
        assertTrue(sql.contains("SUM(CASE WHEN ((t.\"id\" IS NULL) AND NOT ((s.\"id\" IS NULL))) "
                + "THEN 1 ELSE 0 END) AS target_missing"));
        assertTrue(sql.contains("SUM(CASE WHEN ((s.\"id\" IS NULL) AND NOT ((t.\"id\" IS NULL))) "
                + "THEN 1 ELSE 0 END) AS source_missing"));
        assertTrue(sql.contains("WHERE ((s.\"id\" IS NULL) AND NOT ((t.\"id\" IS NULL)))"));
    }

    /**
     * 多重性过滤器必须限定外层引用（否则非限定列绑定到子查询自己的别名 → 退化成自比较、
     * 谓词恒真/恒假）。实测：未限定时 dq_cmp_edge_dup 的 join 仍按 N×M 计数（15 而非 6）。
     */
    @Test
    void shouldQualifyOuterReferenceInKeyMultiplicityFilter() {
        String duplicates = generator.getKeyMultiplicityFilterSQL(
                "s1", "dup_table", List.of("k1"), null, null, true, "dup_table");
        String unique = generator.getKeyMultiplicityFilterSQL(
                "s1", "dup_table", List.of("k1"), null, "k1 > 0", false, "dup_table");

        assertTrue(duplicates.contains("FROM \"s1\".\"dup_table\" nl_dup_k"), duplicates);
        assertTrue(duplicates.contains("nl_dup_k.\"k1\"") && duplicates.contains("\"dup_table\".\"k1\""),
                duplicates);
        assertTrue(duplicates.contains("HAVING COUNT(*) > 1"), duplicates);
        assertTrue(unique.contains("HAVING COUNT(*) = 1"), unique);
        assertTrue(unique.contains("k1 > 0"), unique);
    }

    /**
     * 统计查询的列形状契约：调用方（JoinDiffer.collectStatistics /
     * JoinPerformanceMonitor.recordStatistics）按五列读取，少一列会被整行丢弃使统计静默为空。
     */
    @Test
    void shouldReturnFiveColumnContractWithoutFullOuterJoin() {
        String sql = generator.getJoinDiffStatsSQL(
                "s1", "source_table", "s", List.of("id"), List.of("value"), null, null,
                "s2", "target_table", "t", List.of("id"), List.of("value"), null, null);

        assertTrue(sql.contains("AS source_count"), sql);
        assertTrue(sql.contains("AS target_count"), sql);
        assertTrue(sql.contains("AS source_missing"), sql);
        assertTrue(sql.contains("AS target_missing"), sql);
        assertTrue(sql.contains("AS mismatch"), sql);
        // 无 FULL OUTER JOIN 的方言必须走 LEFT JOIN UNION ALL，不能把 FULL OUTER JOIN 包在括号里返回
        assertTrue(!sql.contains("FULL OUTER JOIN"), sql);
        assertTrue(sql.contains("LEFT OUTER JOIN") && sql.contains("UNION ALL"), sql);
    }

    @Test
    void shouldReturnFiveColumnContractWithFullOuterJoin() {
        BaseSqlQueryGenerator fullJoinGenerator = new BaseSqlQueryGenerator(new FullJoinCapabilityProvider()) {
            @Override
            public String getRowHashSQL(String schemaName, String tableName, List<String> primaryKeys,
                    List<String> columns, Map<String, DataType> columnDataTypes, String whereClause) {
                return "";
            }
        };

        String sql = fullJoinGenerator.getJoinDiffStatsSQL(
                "s1", "source_table", "s", List.of("id"), List.of("value"), null, null,
                "s2", "target_table", "t", List.of("id"), List.of("value"), null, null);

        assertTrue(sql.contains("AS source_count"), sql);
        assertTrue(sql.contains("AS target_count"), sql);
        assertTrue(sql.contains("AS source_missing"), sql);
        assertTrue(sql.contains("AS target_missing"), sql);
        assertTrue(sql.contains("AS mismatch"), sql);
        assertTrue(sql.contains("FULL OUTER JOIN"), sql);
    }

    private static class FullJoinCapabilityProvider extends TestCapabilityProvider {
        @Override
        public boolean supportsFeature(DatabaseFeature feature) {
            return feature == DatabaseFeature.FULL_OUTER_JOIN;
        }

        @Override
        public Set<DatabaseFeature> getSupportedFeatures() {
            return Set.of(DatabaseFeature.FULL_OUTER_JOIN);
        }
    }

    private static class TestCapabilityProvider implements CapabilityProvider {
        @Override
        public boolean supportsFeature(DatabaseFeature feature) {
            return false;
        }

        @Override
        public Set<DatabaseFeature> getSupportedFeatures() {
            return Collections.emptySet();
        }

        @Override
        public String getOpenQuote() {
            return "\"";
        }

        @Override
        public String getCloseQuote() {
            return "\"";
        }
    }
}
