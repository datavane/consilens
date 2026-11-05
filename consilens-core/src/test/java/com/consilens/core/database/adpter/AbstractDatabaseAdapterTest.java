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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    /**
     * 采样 SQL 必须按键的**原生顺序**排序（offset/keyset 采样的前提是"第 N 行"与键区间一一对应）。
     * 若误用 getSelectSQL 的字符串化 ORDER BY（本地归并比对的跨方言对齐口径），字典序会把边界键
     * 取成完全不同的行：实测 dq_cmp_scale_100k 把 [54994,100010] 切成 [100000,100010]+[100010,100010]，
     * (54994,100000) 整段从未参与比对（SC_D7 漏报）。
     */
    @Test
    void shouldOrderSamplingQueriesByNativeKeyOrder() {
        SqlQueryGenerator queryGenerator = mock(SqlQueryGenerator.class);
        CapabilityProvider capabilityProvider = mock(CapabilityProvider.class);
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ConnectionPool connectionPool = mock(ConnectionPool.class);

        when(dialect.getSqlQueryGenerator()).thenReturn(queryGenerator);
        when(dialect.getCapabilityProvider()).thenReturn(capabilityProvider);
        when(capabilityProvider.quote(anyString())).thenAnswer(invocation -> "`" + invocation.getArgument(0) + "`");
        when(queryGenerator.getLimitClause(anyLong())).thenReturn("LIMIT 1");
        when(queryGenerator.getLimitClause(anyLong(), anyLong())).thenReturn("LIMIT 100");
        when(queryGenerator.getSelectSQL(any(), anyString(), any(), any(), any()))
                .thenReturn("SELECT `pk_id` FROM `t` WHERE pk_id >= 1");

        CapturingAdapter adapter = new CapturingAdapter(connectionPool, dialect);
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("t"))
                .keyColumns(List.of("pk_id"))
                .minKey(Optional.of(List.of(1L)))
                .maxKey(Optional.of(List.of(100010L)))
                .build();

        String offsetSampling = adapter.buildKeySamplingQuery(segment, 0L, 1L);
        String keysetSampling = adapter.buildKeysetSamplingQuery(segment, List.of(1L), 100L);

        assertTrue(offsetSampling.contains("ORDER BY `pk_id`"), offsetSampling);
        assertTrue(keysetSampling.contains("ORDER BY `pk_id`"), keysetSampling);
        assertTrue(!offsetSampling.contains("CAST("), offsetSampling);
        assertTrue(!keysetSampling.contains("CAST("), keysetSampling);
    }

    /**
     * 无参查询的语句形态由方言能力决定：支持服务端 prepare 的方言保持 PreparedStatement
     * （改动前所有方言的行为），Presto 等不支持的方言改用普通 Statement；有参查询恒用
     * PreparedStatement。
     */
    @Test
    void shouldSelectStatementTypeByDialectServerSidePrepareCapability() throws Exception {
        ConnectionPool connectionPool = mock(ConnectionPool.class);
        Connection connection = mock(Connection.class);
        when(connectionPool.getConnectorType()).thenReturn("mysql");
        when(connectionPool.getConnection()).thenReturn(connection);
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);

        DatabaseDialect serverSideDialect = mock(DatabaseDialect.class);
        when(serverSideDialect.supportsServerSidePrepare()).thenReturn(true);
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(connection.prepareStatement("select 1")).thenReturn(prepared);
        when(prepared.executeQuery()).thenReturn(empty);

        new QueryExecutingAdapter(connectionPool, serverSideDialect)
                .query("select 1", (DatabaseAdapter.RowMapper<Object>) (rs, rowNum) -> null);

        verify(connection).prepareStatement("select 1");
        verify(connection, never()).createStatement();

        ConnectionPool prestoPool = mock(ConnectionPool.class);
        Connection prestoConnection = mock(Connection.class);
        when(prestoPool.getConnectorType()).thenReturn("presto");
        when(prestoPool.getConnection()).thenReturn(prestoConnection);
        DatabaseDialect prestoDialect = mock(DatabaseDialect.class);
        when(prestoDialect.supportsServerSidePrepare()).thenReturn(false);
        Statement plain = mock(Statement.class);
        when(prestoConnection.createStatement()).thenReturn(plain);
        when(plain.executeQuery("select 1")).thenReturn(empty);

        new QueryExecutingAdapter(prestoPool, prestoDialect)
                .query("select 1", (DatabaseAdapter.RowMapper<Object>) (rs, rowNum) -> null);

        verify(prestoConnection, never()).prepareStatement(anyString());
        verify(plain).executeQuery("select 1");
    }

    @Test
    void shouldAlwaysPrepareParameterizedQueriesEvenWithoutServerSidePrepare() throws Exception {
        ConnectionPool connectionPool = mock(ConnectionPool.class);
        Connection connection = mock(Connection.class);
        when(connectionPool.getConnectorType()).thenReturn("presto");
        when(connectionPool.getConnection()).thenReturn(connection);
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        when(dialect.supportsServerSidePrepare()).thenReturn(false);
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(connection.prepareStatement("select ?")).thenReturn(prepared);
        when(prepared.executeQuery()).thenReturn(empty);

        new QueryExecutingAdapter(connectionPool, dialect)
                .query("select ?", (DatabaseAdapter.RowMapper<Object>) (rs, rowNum) -> null, "value");

        verify(prepared).setObject(1, "value");
        verify(connection, never()).createStatement();
    }

    private static final class QueryExecutingAdapter extends AbstractDatabaseAdapter {

        private QueryExecutingAdapter(ConnectionPool connectionPool, DatabaseDialect dialect) {
            super("query-executing", connectionPool, dialect, ChecksumAlgorithm.CONCAT);
        }

        @Override
        public TableSchema getTableSchema(List<String> tablePath) {
            throw new UnsupportedOperationException("Schema lookup not needed for this test");
        }

        @Override
        public long count(TableSegment segment) {
            return 0L;
        }
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
