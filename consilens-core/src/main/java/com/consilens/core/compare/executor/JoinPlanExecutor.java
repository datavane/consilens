package com.consilens.core.compare.executor;

import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.core.algorithm.JoinDiffer;
import com.consilens.core.algorithm.TableDiffer;
import com.consilens.core.compare.CompareExecutionSettings;
import com.consilens.core.compare.ComparePlan;
import com.consilens.core.compare.PlanExecutor;
import com.consilens.core.compare.jdbc.support.JdbcCompareSegmentAdapter;
import com.consilens.core.compare.plan.ServerJoinPlan;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.segment.TableSegment;

public class JoinPlanExecutor implements PlanExecutor<ComparePlan> {

    @Override
    public boolean supports(ComparePlan plan) {
        return plan instanceof ServerJoinPlan;
    }

    @Override
    public DiffResult execute(ComparePlan plan, CompareRequest request, CompareSegment source, CompareSegment target) throws Exception {
        try (JdbcCompareSegmentAdapter.LegacyTableSegment sourceTable = JdbcCompareSegmentAdapter.toTableSegment(source);
             JdbcCompareSegmentAdapter.LegacyTableSegment targetTable = JdbcCompareSegmentAdapter.toTableSegment(target)) {
            CompareExecutionSettings executionSettings = plan instanceof ServerJoinPlan
                    ? ((ServerJoinPlan) plan).getExecutionSettings()
                    : CompareExecutionSettings.fromRequest(request);
            TableDiffer.DifferConfig config = executionSettings.toDifferConfig();

            try (JoinDiffer differ = new JoinDiffer(config, executionSettings.toJoinOptions())) {
                return differ.diffTables(sourceTable.getTableSegment(), targetTable.getTableSegment()).get();
            }
        }
    }
}
