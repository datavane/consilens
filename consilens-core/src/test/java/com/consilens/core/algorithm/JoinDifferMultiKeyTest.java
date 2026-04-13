package com.consilens.core.algorithm;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.database.connection.ConnectionPool;
import com.consilens.core.diff.DiffResult;
import com.consilens.connector.api.model.TablePath;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JoinDiffer Multi-Key Support Tests")
class JoinDifferMultiKeyTest {

    @Mock
    private DatabaseAdapter mockAdapter1;

    @Mock
    private DatabaseAdapter mockAdapter2;

    private TableDiffer.DifferConfig config;
    private JoinDiffer differ;
    private SqlQueryGenerator mockSqlQueryGenerator;

    @BeforeEach
    void setUp() {
        config = new TableDiffer.DifferConfig(1000, 16384, false, ChecksumAlgorithm.CONCAT);
        differ = new JoinDiffer(config, JoinDiffer.JoinDifferOptions.defaultOptions());
        mockSqlQueryGenerator = mock(SqlQueryGenerator.class);

        ConnectionPool pool1 = mock(ConnectionPool.class);
        ConnectionPool pool2 = mock(ConnectionPool.class);
        PoolConfiguration cfg1 = mock(PoolConfiguration.class);
        PoolConfiguration cfg2 = mock(PoolConfiguration.class);

        lenient().when(mockAdapter1.getConnectionPool()).thenReturn(pool1);
        lenient().when(mockAdapter2.getConnectionPool()).thenReturn(pool2);
        lenient().when(mockAdapter1.getName()).thenReturn("mock-db");
        lenient().when(mockAdapter2.getName()).thenReturn("mock-db");
        lenient().when(pool1.getConfiguration()).thenReturn(cfg1);
        lenient().when(pool2.getConfiguration()).thenReturn(cfg2);
        lenient().when(cfg1.getJdbcUrl()).thenReturn("jdbc:mock:test");
        lenient().when(cfg2.getJdbcUrl()).thenReturn("jdbc:mock:test");

        lenient().when(mockAdapter1.getSqlQueryGenerator()).thenReturn(mockSqlQueryGenerator);
        lenient().when(mockSqlQueryGenerator.getJoinDiffStatsSQL(
                any(), any(), any(), anyList(), anyList(), any(),
                any(), any(), any(), anyList(), anyList(), any()))
                .thenReturn("SELECT COUNT(*) FROM table1");
        lenient().when(mockSqlQueryGenerator.getJoinDiffDetailSQL(
                any(), any(), any(), anyList(), anyList(), anyList(), any(),
                any(), any(), any(), anyList(), anyList(), anyList(), any()))
                .thenReturn("SELECT * FROM table1 JOIN table2 ON table1.id = table2.id");
        lenient().when(mockAdapter1.count(any(TableSegment.class))).thenReturn(100L);
    }

    @Test
    @DisplayName("Test Multi-Column Primary Key Join")
    void testMultiColumnPrimaryKeyJoin() throws Exception {
        // Given
        List<String> keyColumns = Arrays.asList("id", "version");
        TableSegment segment1 = createMockSegmentWithKeys(TablePath.of("table1"), mockAdapter1, keyColumns);
        TableSegment segment2 = createMockSegmentWithKeys(TablePath.of("table2"), mockAdapter2, keyColumns);

        mockMultiColumnJoinResults();

        // When
        CompletableFuture<DiffResult> future = differ.diffTables(segment1, segment2);
        DiffResult result = future.get();

        // Then
        assertNotNull(result);
        verify(mockSqlQueryGenerator, atLeastOnce()).getJoinDiffDetailSQL(
                any(), any(), any(), eq(keyColumns), anyList(), anyList(), any(),
                any(), any(), any(), eq(keyColumns), anyList(), anyList(), any());
        verify(mockSqlQueryGenerator, atLeastOnce()).getJoinDiffStatsSQL(
                any(), any(), any(), eq(keyColumns), anyList(), any(),
                any(), any(), any(), eq(keyColumns), anyList(), any());

        // Verify JOIN queries are executed
        verify(mockAdapter1, atLeastOnce()).query(contains("JOIN"), eq(Object[].class));

        // Verify results
        assertEquals(100, result.getStatistics().getTotalDifferences());
        assertEquals(100, result.getStatistics().getMismatchCount());
        assertEquals(0, result.getStatistics().getSourceMissingCount());
        assertEquals(0, result.getStatistics().getTargetMissingCount());
    }

    private TableSegment createMockSegmentWithKeys(TablePath path, DatabaseAdapter adapter, List<String> keyColumns) {
        TableSegment segment = mock(TableSegment.class);
        when(segment.getTablePath()).thenReturn(path);
        when(segment.getDatabase()).thenReturn(adapter);
        when(segment.getKeyColumns()).thenReturn(keyColumns);
        when(segment.getRelevantColumns()).thenReturn(Arrays.asList("id", "version", "name", "value"));
        when(segment.getExtraColumns()).thenReturn(Arrays.asList("name", "value"));
        return segment;
    }

    private void mockMultiColumnJoinResults() {
        List<Object[]> results = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            results.add(new Object[] {
                    "mismatch",
                    "[value]",
                    "[value]",
                    i,
                    1,
                    "name_" + i,
                    "value_v1",
                    i,
                    1,
                    "name_" + i,
                    "value_v2"
            });
        }

        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("COUNT")) {
                return Collections.singletonList(new Object[] { 100L, 100L, 0L, 0L, 100L });
            }
            if (sql.contains("JOIN")) {
                return results;
            }
            return Collections.emptyList();
        }).when(mockAdapter1).query(anyString(), eq(Object[].class));
    }
}
