package com.consilens.core.algorithm;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.diff.InfoTreeRecorder;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("JoinDiffer 大表分段（P2-2）")
class JoinDifferSegmentationTest {

    private static final long ROWS = 1000L;
    private static final long THRESHOLD = 400L;

    @Mock
    private DatabaseAdapter adapter1;

    @Mock
    private DatabaseAdapter adapter2;

    @Mock
    private SqlQueryGenerator generator;

    private JoinDiffer differ;

    @BeforeEach
    void setUp() {
        TableDiffer.DifferConfig config = new TableDiffer.DifferConfig(2, THRESHOLD, ChecksumAlgorithm.CONCAT);
        differ = new JoinDiffer(config, new JoinDiffer.JoinDifferOptions(false));

        lenient().when(adapter1.getSqlQueryGenerator()).thenReturn(generator);
        lenient().when(adapter1.countAndBounds(any())).thenReturn(
                new TableSegment.ChecksumResult(ROWS, "sum", List.of(1), List.of(ROWS)));
        lenient().when(adapter2.countAndBounds(any())).thenReturn(
                new TableSegment.ChecksumResult(ROWS, "sum", List.of(1), List.of(ROWS)));
        lenient().when(generator.getJoinDiffStatsSQL(any(), any(), any(), anyList(), anyList(), any(),
                anyMap(), any(), any(), any(), anyList(), anyList(), any(), anyMap()))
                .thenReturn("SELECT COUNT(*) FROM t1");
        lenient().when(generator.getJoinDiffDetailSQL(any(), any(), any(), anyList(), anyList(), anyList(),
                any(), anyMap(), any(), any(), any(), anyList(), anyList(), anyList(), any(), anyMap()))
                .thenReturn("SELECT * FROM t1 JOIN t2 ON t1.id = t2.id");
        lenient().when(adapter1.query(anyString(), eq(Object[].class))).thenReturn(List.of());
        lenient().when(adapter2.query(anyString(), eq(Object[].class))).thenReturn(List.of());
    }

    @Test
    @DisplayName("超过阈值时按并集键空间切段，每段各发一条 join 查询")
    void shouldSegmentLargeJoinIntoMultipleQueries() {
        TableSegment source = segment(adapter1, TablePath.of("db", "t1"));
        TableSegment target = segment(adapter2, TablePath.of("db", "t2"));

        differ.diffSegments(source, target, new InfoTreeRecorder("root"), 0L, 0).join();

        verify(adapter1, atLeast(2)).query(anyString(), eq(Object[].class));
    }

    @Test
    @DisplayName("低于阈值时保持单条 join 查询")
    void shouldKeepSingleQueryBelowThreshold() {
        TableDiffer.DifferConfig small = new TableDiffer.DifferConfig(2, 100_000L, ChecksumAlgorithm.CONCAT);
        JoinDiffer smallDiffer = new JoinDiffer(small, new JoinDiffer.JoinDifferOptions(false));
        TableSegment source = segment(adapter1, TablePath.of("db", "t1"));
        TableSegment target = segment(adapter2, TablePath.of("db", "t2"));

        smallDiffer.diffSegments(source, target, new InfoTreeRecorder("root"), 0L, 0).join();

        verify(adapter1, atLeast(1)).query(anyString(), eq(Object[].class));
    }

    @Test
    @DisplayName("空表导致 MIN/MAX 边界不可用时回退单段，不再反复建立边界")
    void shouldFallBackToSingleSegmentWhenBoundsUnavailable() {
        lenient().when(adapter1.countAndBounds(any())).thenReturn(
                new TableSegment.ChecksumResult(0L, null, null, null));
        lenient().when(adapter2.countAndBounds(any())).thenReturn(
                new TableSegment.ChecksumResult(0L, null, null, null));
        TableSegment source = segment(adapter1, TablePath.of("db", "t1"));
        TableSegment target = segment(adapter2, TablePath.of("db", "t2"));

        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> differ.diffSegments(source, target, new InfoTreeRecorder("root"), 0L, 0).join());

        verify(adapter1, times(1)).countAndBounds(any());
        verify(adapter2, times(1)).countAndBounds(any());
    }

    private TableSegment segment(DatabaseAdapter adapter, TablePath path) {
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        columns.put("id", column("id", DataType.BIGINT));
        columns.put("name", column("name", DataType.VARCHAR));
        return TableSegment.builder()
                .database(adapter)
                .tablePath(path)
                .keyColumns(List.of("id"))
                .extraColumns(List.of("name"))
                .schema(Optional.of(new TableSchema(path, columns)))
                .build();
    }

    private ColumnInfo column(String name, DataType type) {
        return ColumnInfo.builder()
                .name(name)
                .type(type)
                .nullable(false)
                .ordinalPosition(1)
                .precision(Optional.empty())
                .scale(Optional.empty())
                .maxLength(Optional.empty())
                .defaultValue(Optional.empty())
                .collation(Optional.empty())
                .primaryKey(true)
                .uniqueKey(false)
                .comment(Optional.empty())
                .build();
    }
}
