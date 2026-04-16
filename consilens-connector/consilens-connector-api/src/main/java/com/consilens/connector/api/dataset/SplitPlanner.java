package com.consilens.connector.api.dataset;

import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.planner.SegmentSplit;

import java.util.List;

public interface SplitPlanner {

    List<SegmentSplit> split(CompareSegment segment, SplitOptions options);
}
