package com.consilens.core.segment;

import com.consilens.connector.api.model.TablePath;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.thread.ExecutorProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TableSegmenterTest {

    @Test
    void shouldHonorBinarySplitRequestForThresholdSizedBoundedRange() {
        DatabaseAdapter adapter = mock(DatabaseAdapter.class);
        when(adapter.count(any(TableSegment.class))).thenReturn(5001L);

        TableSegment table = TableSegment.builder()
                .database(adapter)
                .tablePath(TablePath.of("users"))
                .keyColumns(List.of("id"))
                .minKey(Optional.of(List.of(1L)))
                .maxKey(Optional.of(List.of(5001L)))
                .upperBoundInclusive(true)
                .build();

        ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "table-segmenter-test");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorProvider executorProvider = new ExecutorProvider(executor, executor, false);

        TableSegmenter segmenter = new TableSegmenter(
                adapter,
                new TableSegmenter.SegmenterConfig(5000, 100, 16),
                executorProvider);

        List<TableSegment> segments = segmenter.createOptimalSegments(table, 2, 5000).join();

        assertEquals(2, segments.size());
        assertEquals(Optional.of(List.of(1L)), segments.get(0).getMinKey());
        assertEquals(Optional.of(List.of(5001L)), segments.get(1).getMaxKey());
        assertNotEquals(Optional.of(List.of(5001L)), segments.get(0).getMaxKey());
    }
}
