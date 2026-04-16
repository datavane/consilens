package com.consilens.core.compare;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.LocalCompareMode;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.algorithm.JoinDiffer;
import com.consilens.core.algorithm.TableDiffer;
import com.consilens.core.thread.ConcurrencyConfig;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class CompareExecutionSettings {

    private static final int DEFAULT_BISECTION_FACTOR = 32;
    private static final long DEFAULT_BISECTION_THRESHOLD = 16384L;

    private final int bisectionFactor;
    private final long bisectionThreshold;
    private final boolean enableProfiling;
    private final ChecksumAlgorithm checksumAlgorithm;
    private final LocalCompareMode localCompareMode;
    private final ConcurrencyConfig concurrencyConfig;
    private final boolean validateUniqueKeys;

    public static CompareExecutionSettings fromRequest(CompareRequest request) {
        CompareExecutionOptions executionOptions = request != null ? request.getExecutionOptions() : null;
        Map<String, Object> attributes = executionOptions != null ? executionOptions.getAttributes() : null;

        return CompareExecutionSettings.builder()
                .bisectionFactor(executionOptions != null && executionOptions.getBisectionFactor() != null
                        ? executionOptions.getBisectionFactor()
                        : DEFAULT_BISECTION_FACTOR)
                .bisectionThreshold(executionOptions != null && executionOptions.getBisectionThreshold() != null
                        ? executionOptions.getBisectionThreshold()
                        : DEFAULT_BISECTION_THRESHOLD)
                .enableProfiling(executionOptions != null && Boolean.TRUE.equals(executionOptions.getEnableProfiling()))
                .checksumAlgorithm(executionOptions != null
                        ? ChecksumAlgorithm.fromString(executionOptions.getChecksumAlgorithm())
                        : ChecksumAlgorithm.CONCAT)
                .localCompareMode(executionOptions != null
                        ? LocalCompareMode.fromString(executionOptions.getLocalCompareMode())
                        : LocalCompareMode.FULL)
                .concurrencyConfig(resolveConcurrencyConfig(attributes))
                .validateUniqueKeys(executionOptions != null && Boolean.TRUE.equals(executionOptions.getValidateUniqueKeys()))
                .build();
    }

    public TableDiffer.DifferConfig toDifferConfig() {
        return toDifferConfig(null);
    }

    public TableDiffer.DifferConfig toDifferConfig(LocalCompareMode localCompareOverride) {
        return new TableDiffer.DifferConfig(
                bisectionFactor,
                bisectionThreshold,
                enableProfiling,
                checksumAlgorithm,
                localCompareOverride != null ? localCompareOverride : localCompareMode,
                concurrencyConfig);
    }

    public JoinDiffer.JoinDifferOptions toJoinOptions() {
        return new JoinDiffer.JoinDifferOptions(validateUniqueKeys);
    }

    private static ConcurrencyConfig resolveConcurrencyConfig(Map<String, Object> attributes) {
        if (attributes != null) {
            Object value = attributes.get("concurrencyConfig");
            if (value instanceof ConcurrencyConfig) {
                return (ConcurrencyConfig) value;
            }
        }
        return ConcurrencyConfig.defaultConfig();
    }
}
