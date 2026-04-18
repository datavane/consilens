package com.consilens.core.compare.executor;

import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.core.algorithm.JoinDiffer;
import com.consilens.core.compare.CompareExecutionSettings;
import com.consilens.core.compare.ComparePlan;
import com.consilens.core.compare.PlanExecutor;
import com.consilens.core.compare.plan.ServerJoinPlan;
import com.consilens.core.compare.relational.RelationalCompareSegmentAdapter;
import com.consilens.core.diff.DiffResult;

public class JoinPlanExecutor implements PlanExecutor<ComparePlan> {

    @Override
    public boolean supports(ComparePlan plan) {
        return plan instanceof ServerJoinPlan;
    }

    @Override
    public DiffResult execute(ComparePlan plan, CompareRequest request, CompareSegment source, CompareSegment target) throws Exception {
        CompareExecutionSettings executionSettings = plan instanceof ServerJoinPlan
                ? ((ServerJoinPlan) plan).getExecutionSettings()
                : CompareExecutionSettings.fromRequest(request);
        try (RelationalCompareSegmentAdapter.PreparedTableSegment sourceSegment =
                     RelationalCompareSegmentAdapter.toTableSegment(source, executionSettings);
             RelationalCompareSegmentAdapter.PreparedTableSegment targetSegment =
                     RelationalCompareSegmentAdapter.toTableSegment(target, executionSettings);
             JoinDiffer differ = new JoinDiffer(
                     executionSettings.toDifferConfig(),
                     new JoinDiffer.JoinDifferOptions(executionSettings.isValidateUniqueKeys()))) {
            return differ.diffTables(sourceSegment.getTableSegment(), targetSegment.getTableSegment()).get();
        }
    }
}
