package com.consilens.core.compare.executor;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.dataset.RelationalDatasetSupport;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.core.algorithm.ChecksumDiffer;
import com.consilens.core.compare.CompareExecutionSettings;
import com.consilens.core.compare.ComparePlan;
import com.consilens.core.compare.PlanExecutor;
import com.consilens.core.compare.plan.PushdownChecksumPlan;
import com.consilens.core.compare.relational.RelationalCompareSegmentAdapter;
import com.consilens.core.diff.DiffResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class ChecksumPlanExecutor implements PlanExecutor<ComparePlan> {

    @Override
    public boolean supports(ComparePlan plan) {
        return plan instanceof PushdownChecksumPlan;
    }

    @Override
    public DiffResult execute(ComparePlan plan, CompareRequest request, CompareSegment source, CompareSegment target) throws Exception {
        CompareExecutionSettings executionSettings = resolveEffectiveExecutionSettings(
                resolveExecutionSettings(plan, request), source, target);
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

    static CompareExecutionSettings resolveEffectiveExecutionSettings(CompareExecutionSettings executionSettings,
                                                                     CompareSegment source,
                                                                     CompareSegment target) {
        if (executionSettings == null || executionSettings.getChecksumAlgorithm() == null
                || !executionSettings.getChecksumAlgorithm().isXor()) {
            return executionSettings;
        }

        Optional<RelationalDatasetSupport> sourceSupport = resolveRelationalSupport(source);
        Optional<RelationalDatasetSupport> targetSupport = resolveRelationalSupport(target);
        if (sourceSupport.isEmpty() || targetSupport.isEmpty()) {
            return executionSettings;
        }

        SqlQueryGenerator sourceGenerator = sourceSupport.get().getDialect().getSqlQueryGenerator();
        SqlQueryGenerator targetGenerator = targetSupport.get().getDialect().getSqlQueryGenerator();
        boolean sourceSupportsRequested = sourceGenerator.supportsChecksumAlgorithm(executionSettings.getChecksumAlgorithm());
        boolean targetSupportsRequested = targetGenerator.supportsChecksumAlgorithm(executionSettings.getChecksumAlgorithm());
        if (sourceSupportsRequested && targetSupportsRequested) {
            return executionSettings;
        }

        log.warn("Checksum algorithm {} is not supported by both sides (source: {} via {}, target: {} via {}). "
                        + "Downgrading to CONCAT for a consistent pushdown checksum comparison.",
                executionSettings.getChecksumAlgorithm(),
                sourceSupport.get().getName(),
                sourceGenerator.getClass().getSimpleName(),
                targetSupport.get().getName(),
                targetGenerator.getClass().getSimpleName());
        return executionSettings.withChecksumAlgorithm(ChecksumAlgorithm.CONCAT);
    }

    private static Optional<RelationalDatasetSupport> resolveRelationalSupport(CompareSegment segment) {
        return segment != null && segment.getDataset() != null
                ? segment.getDataset().getSupport(RelationalDatasetSupport.class)
                : Optional.empty();
    }
}
