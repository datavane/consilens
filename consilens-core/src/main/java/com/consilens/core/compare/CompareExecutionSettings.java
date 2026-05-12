package com.consilens.core.compare;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.LocalCompareMode;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
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
    private static final long DEFAULT_MAX_DIFFERENCES = 1_000_000L;

    private final int bisectionFactor;
    private final long bisectionThreshold;
    private final boolean enableProfiling;
    private final ChecksumAlgorithm checksumAlgorithm;
    private final LocalCompareMode localCompareMode;
    private final ConcurrencyConfig concurrencyConfig;
    private final boolean validateUniqueKeys;
    @Builder.Default
    private final long maxDifferences = DEFAULT_MAX_DIFFERENCES;

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
                .validateUniqueKeys(executionOptions == null || !Boolean.FALSE.equals(executionOptions.getValidateUniqueKeys()))
                .maxDifferences(resolveMaxDifferences(executionOptions, attributes))
                .build();
    }

    public TableDiffer.DifferConfig toDifferConfig() {
        return new TableDiffer.DifferConfig(
                bisectionFactor,
                bisectionThreshold,
                enableProfiling,
                checksumAlgorithm,
                localCompareMode,
                concurrencyConfig,
                maxDifferences);
    }

    public CompareExecutionSettings withChecksumAlgorithm(ChecksumAlgorithm checksumAlgorithm) {
        if (this.checksumAlgorithm == checksumAlgorithm) {
            return this;
        }
        return CompareExecutionSettings.builder()
                .bisectionFactor(bisectionFactor)
                .bisectionThreshold(bisectionThreshold)
                .enableProfiling(enableProfiling)
                .checksumAlgorithm(checksumAlgorithm)
                .localCompareMode(localCompareMode)
                .concurrencyConfig(concurrencyConfig)
                .validateUniqueKeys(validateUniqueKeys)
                .maxDifferences(maxDifferences)
                .build();
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

    private static long resolveMaxDifferences(CompareExecutionOptions executionOptions, Map<String, Object> attributes) {
        if (executionOptions != null && executionOptions.getMaxDifferences() != null) {
            return Math.max(1L, executionOptions.getMaxDifferences());
        }
        if (attributes == null) {
            return DEFAULT_MAX_DIFFERENCES;
        }
        Object value = attributes.get("maxDifferences");
        if (value instanceof Number) {
            return Math.max(1L, ((Number) value).longValue());
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Math.max(1L, Long.parseLong(((String) value).trim()));
        }
        return DEFAULT_MAX_DIFFERENCES;
    }
}
