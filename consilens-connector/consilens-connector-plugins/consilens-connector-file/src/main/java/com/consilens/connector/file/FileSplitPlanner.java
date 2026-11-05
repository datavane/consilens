package com.consilens.connector.file;

import com.consilens.connector.api.dataset.SplitOptions;
import com.consilens.connector.api.dataset.SplitPlanner;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.planner.OffsetLimitSplit;
import com.consilens.connector.api.planner.SegmentSplit;

import java.util.List;

/**
 * File-level split planner. A single local file is one ordered stream; the
 * split is an offset/limit over the key-sorted records. The current
 * StreamingMergePlan consumes the whole segment, but exposing the split keeps
 * the SPI usable for future parallel file scanning.
 */
public class FileSplitPlanner implements SplitPlanner {

    @Override
    public List<SegmentSplit> split(CompareSegment segment, SplitOptions options) {
        return List.of(OffsetLimitSplit.builder().offset(0L).limit(Long.MAX_VALUE).build());
    }
}
