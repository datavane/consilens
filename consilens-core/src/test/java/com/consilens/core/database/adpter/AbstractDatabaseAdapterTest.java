package com.consilens.core.database.adpter;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.core.database.connection.ConnectionPool;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractDatabaseAdapterTest {

    @Test
    void shouldUseOrderedBoundaryQueryForCompositeKeysFromSqlRelation() {
        SqlQueryGenerator queryGenerator = mock(SqlQueryGenerator.class);
        CapabilityProvider capabilityProvider = mock(CapabilityProvider.class);
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ConnectionPool connectionPool = mock(ConnectionPool.class);

        when(dialect.getSqlQueryGenerator()).thenReturn(queryGenerator);
        when(dialect.getCapabilityProvider()).thenReturn(capabilityProvider);
        when(connectionPool.getConnectorType()).thenReturn("mysql");
        when(queryGenerator.getCountSQLFromSql(anyString(), eq("biz_date >= '2026-05-01'")))
                .thenReturn("COUNT_SQL");
        when(queryGenerator.getLimitClause(anyLong())).thenReturn("LIMIT 1");
        when(capabilityProvider.quote(anyString())).thenAnswer(invocation -> "`" + invocation.getArgument(0) + "`");

        CapturingAdapter adapter = new CapturingAdapter(connectionPool, dialect);
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("agg_sql"))
                .relationSource(new TableSegment.RelationSource(
                        "SELECT biz_date, status FROM daily_order_summary",
                        "agg_sql"))
                .keyColumns(List.of("biz_date", "status"))
                .whereClause(Optional.of("biz_date >= '2026-05-01'"))
                .build();

        TableSegment.ChecksumResult result = adapter.countAndBounds(segment);

        assertEquals(List.of("2026-05-01", "active"), result.getMinKey());
        assertEquals(List.of("2026-05-01", "pending"), result.getMaxKey());
        assertTrue(adapter.executedSql.stream().anyMatch(sql ->
                sql.contains("FROM (SELECT biz_date, status FROM daily_order_summary) consilens_sql_source")
                        && sql.contains("ORDER BY `biz_date` ASC, `status` ASC LIMIT 1")));
        assertTrue(adapter.executedSql.stream().anyMatch(sql ->
                sql.contains("FROM (SELECT biz_date, status FROM daily_order_summary) consilens_sql_source")
                        && sql.contains("ORDER BY `biz_date` DESC, `status` DESC LIMIT 1")));
    }

    private static final class CapturingAdapter extends AbstractDatabaseAdapter {

        private final List<String> executedSql = new ArrayList<>();

        private CapturingAdapter(ConnectionPool connectionPool, DatabaseDialect dialect) {
            super("capturing", connectionPool, dialect, ChecksumAlgorithm.CONCAT);
        }

        @Override
        public TableSchema getTableSchema(List<String> tablePath) {
            throw new UnsupportedOperationException("Schema lookup not needed for this test");
        }

        @Override
        public long count(TableSegment segment) {
            return 4L;
        }

        @Override
        public <T> List<T> query(String sql, Class<T> resultType) {
            executedSql.add(sql);
            @SuppressWarnings("unchecked")
            List<T> result = (List<T>) java.util.Collections.singletonList(
                    sql.contains("ASC")
                            ? new Object[]{"2026-05-01", "active"}
                            : new Object[]{"2026-05-01", "pending"});
            return result;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            executedSql.add(sql);
            @SuppressWarnings("unchecked")
            List<T> result = (List<T>) List.of(Map.of("row_count", 4L));
            return result;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... parameters) {
            return query(sql, rowMapper);
        }

        @Override
        public <T> List<T> query(String sql, Class<T> resultType, Object... parameters) {
            return query(sql, resultType);
        }

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("Not used in this test");
        }
    }
}
