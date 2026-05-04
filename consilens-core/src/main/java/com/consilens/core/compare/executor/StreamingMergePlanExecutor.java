package com.consilens.core.compare.executor;

import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.core.compare.CompareExecutionSettings;
import com.consilens.core.compare.ComparePlan;
import com.consilens.core.compare.PlanExecutor;
import com.consilens.core.compare.plan.StreamingMergePlan;
import com.consilens.core.diff.DiffResult;

public class StreamingMergePlanExecutor implements PlanExecutor<ComparePlan> {

    @Override
    public boolean supports(ComparePlan plan) {
        return plan instanceof StreamingMergePlan;
    }

    @Override
    public DiffResult execute(ComparePlan plan, CompareRequest request, CompareSegment source, CompareSegment target) {
        CompareExecutionSettings executionSettings = plan instanceof StreamingMergePlan
                ? ((StreamingMergePlan) plan).getExecutionSettings()
                : CompareExecutionSettings.fromRequest(request);
        return new ConnectorRecordDiffer().diff(source, target, executionSettings);
    }
}
