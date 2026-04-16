package com.consilens.core.compare;

import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.core.diff.DiffResult;

public interface PlanExecutor<P extends ComparePlan> {

    boolean supports(ComparePlan plan);

    DiffResult execute(P plan, CompareRequest request, CompareSegment source, CompareSegment target) throws Exception;
}
