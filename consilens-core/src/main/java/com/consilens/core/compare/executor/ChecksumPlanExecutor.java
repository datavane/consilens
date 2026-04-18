package com.consilens.core.compare.executor;

import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.core.algorithm.ChecksumDiffer;
import com.consilens.core.compare.CompareExecutionSettings;
import com.consilens.core.compare.ComparePlan;
import com.consilens.core.compare.PlanExecutor;
import com.consilens.core.compare.plan.PushdownChecksumPlan;
import com.consilens.core.compare.relational.RelationalCompareSegmentAdapter;
import com.consilens.core.diff.DiffResult;

public class ChecksumPlanExecutor implements PlanExecutor<ComparePlan> {

    @Override
    public boolean supports(ComparePlan plan) {
        return plan instanceof PushdownChecksumPlan;
    }

    @Override
    public DiffResult execute(ComparePlan plan, CompareRequest request, CompareSegment source, CompareSegment target) throws Exception {
        CompareExecutionSettings executionSettings = resolveExecutionSettings(plan, request);
        try (RelationalCompareSegmentAdapter.PreparedTableSegment sourceSegment =
                     RelationalCompareSegmentAdapter.toTableSegment(source, executionSettings);
             RelationalCompareSegmentAdapter.PreparedTableSegment targetSegment =
                     RelationalCompareSegmentAdapter.toTableSegment(target, executionSettings);
             ChecksumDiffer differ = new ChecksumDiffer(executionSettings.toDifferConfig())) {
            return differ.diffTables(sourceSegment.getTableSegment(), targetSegment.getTableSegment()).get();
        }
    }

    private CompareExecutionSettings resolveExecutionSettings(ComparePlan plan, CompareRequest request) {
        if (plan instanceof PushdownChecksumPlan) {
            return ((PushdownChecksumPlan) plan).getExecutionSettings();
        }
        return CompareExecutionSettings.fromRequest(request);
    }
}
