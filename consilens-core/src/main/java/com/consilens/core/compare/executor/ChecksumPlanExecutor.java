package com.consilens.core.compare.executor;

import com.consilens.common.enums.LocalCompareMode;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.core.compare.CompareExecutionSettings;
import com.consilens.core.algorithm.ChecksumDiffer;
import com.consilens.core.algorithm.TableDiffer;
import com.consilens.core.compare.ComparePlan;
import com.consilens.core.compare.PlanExecutor;
import com.consilens.core.compare.jdbc.support.JdbcCompareSegmentAdapter;
import com.consilens.core.compare.plan.KeyHashPlan;
import com.consilens.core.compare.plan.PushdownChecksumPlan;
import com.consilens.core.compare.plan.StreamingMergePlan;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.segment.TableSegment;

public class ChecksumPlanExecutor implements PlanExecutor<ComparePlan> {

    @Override
    public boolean supports(ComparePlan plan) {
        return plan instanceof PushdownChecksumPlan
                || plan instanceof KeyHashPlan
                || plan instanceof StreamingMergePlan;
    }

    @Override
    public DiffResult execute(ComparePlan plan, CompareRequest request, CompareSegment source, CompareSegment target) throws Exception {
        try (JdbcCompareSegmentAdapter.LegacyTableSegment sourceTable = JdbcCompareSegmentAdapter.toTableSegment(source);
             JdbcCompareSegmentAdapter.LegacyTableSegment targetTable = JdbcCompareSegmentAdapter.toTableSegment(target)) {
            CompareExecutionSettings executionSettings = resolveExecutionSettings(plan, request);
            LocalCompareMode localCompareMode = plan instanceof KeyHashPlan ? LocalCompareMode.ROW_HASH : null;
            TableDiffer.DifferConfig config = executionSettings.toDifferConfig(localCompareMode);

            try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
                return differ.diffTables(sourceTable.getTableSegment(), targetTable.getTableSegment()).get();
            }
        }
    }

    private CompareExecutionSettings resolveExecutionSettings(ComparePlan plan, CompareRequest request) {
        if (plan instanceof PushdownChecksumPlan) {
            return ((PushdownChecksumPlan) plan).getExecutionSettings();
        }
        if (plan instanceof KeyHashPlan) {
            return ((KeyHashPlan) plan).getExecutionSettings();
        }
        if (plan instanceof StreamingMergePlan) {
            return ((StreamingMergePlan) plan).getExecutionSettings();
        }
        return CompareExecutionSettings.fromRequest(request);
    }
}
