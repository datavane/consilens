package com.consilens.connector.api.dataset;

import com.consilens.connector.api.planner.CompareSegment;

public interface HashProvider {

    SegmentDigest digest(CompareSegment segment, HashOptions options);
}
