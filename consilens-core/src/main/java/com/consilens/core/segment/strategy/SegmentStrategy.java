package com.consilens.core.segment.strategy;

import com.consilens.core.segment.TableSegment;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public interface SegmentStrategy {

    SegmentStrategyType getType();

    boolean supports(TableSegment table);

    CompletableFuture<List<TableSegment>> segment(TableSegment table, int maxSegments, Executor executor);
}
