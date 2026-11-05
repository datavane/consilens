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
    /** 容差内行默认保留的样本上限：报告要"看得见"，但不能让大表把被吸收的行全灌进内存。 */
    public static final int DEFAULT_WITHIN_TOLERANCE_SAMPLES = 100;
    /** 样本上限硬顶（与 CLI 校验一致）：防止误配的大数字把被吸收的行全灌进内存。 */
    public static final int MAX_WITHIN_TOLERANCE_SAMPLES = 10_000;

    private final int bisectionFactor;
    private final long bisectionThreshold;
    private final ChecksumAlgorithm checksumAlgorithm;
    private final LocalCompareMode localCompareMode;
    private final ConcurrencyConfig concurrencyConfig;
    private final boolean validateUniqueKeys;
    /**
     * 是否允许可空键列。默认 false（严格档）：键列可空时在比对开始前明确失败；
     * true 时允许可空键，NULL 键按引擎的 IS NULL/归一化语义参与比较。
     */
    @Builder.Default
    private final boolean allowNullableKeys = false;
    @Builder.Default
    private final long maxDifferences = DEFAULT_MAX_DIFFERENCES;
    /** 本地/流式归一化的定点精度；默认 4，可由 normalization spec 的 format_number.precision 覆盖。 */
    @Builder.Default
    private final int decimalScale = com.consilens.core.algorithm.ValueNormalizer.DEFAULT_DECIMAL_SCALE;

    /**
     * JDBC 会话时区（可选）。SQL 侧把无时区的 TIMESTAMP 按会话时区换算到 UTC 渲染，
     * Java 侧要在流式/本地路径同形，必须知道会话时区；未配置时保持墙钟渲染（旧行为）。
     * 通过 normalization spec 的任意规则参数 {@code sessionTimeZone}（或 {@code session_time_zone}）声明。
     */
    @Builder.Default
    private final java.time.ZoneId sessionZone = null;

    /** 墙钟预算（毫秒）；Long.MAX_VALUE 表示不限。分段边界与行批次检查。 */
    @Builder.Default
    private final long maxDurationMs = Long.MAX_VALUE;

    /** 累计扫描行数预算；Long.MAX_VALUE 表示不限。在已知取数点累计。 */
    @Builder.Default
    private final long maxRowsScanned = Long.MAX_VALUE;

    /** 累计取回字节预算；Long.MAX_VALUE 表示不限。流式路径不含字节计数。 */
    @Builder.Default
    private final long maxBytesFetched = Long.MAX_VALUE;

    /** 原始归一化 spec：终局本地比较据此解析列级/类型级数值容差。 */
    private final com.consilens.connector.api.normalization.NormalizationSpec normalizationSpec;

    /**
     * 容差内行的处置口径：默认 IGNORE（判等、不计差异、报告单列）。
     * SQL 侧哈希表达不了容差，因此只在终局本地比较生效。
     */
    @Builder.Default
    private final WithinToleranceMode withinToleranceMode = WithinToleranceMode.IGNORE;

    /** 容差内行随结果保留的样本上限（0 表示只计数不保留行）。 */
    @Builder.Default
    private final int withinToleranceSamples = DEFAULT_WITHIN_TOLERANCE_SAMPLES;

    /** 可选进度回调；流式合并路径会周期性上报。 */
    private final com.consilens.connector.api.planner.ProgressListener progressListener;

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
                .checksumAlgorithm(executionOptions != null
                        ? ChecksumAlgorithm.fromString(executionOptions.getChecksumAlgorithm())
                        : ChecksumAlgorithm.CONCAT)
                .localCompareMode(executionOptions != null
                        ? LocalCompareMode.fromString(executionOptions.getLocalCompareMode())
                        : LocalCompareMode.FULL)
                .concurrencyConfig(resolveConcurrencyConfig(attributes))
                // 默认开放档（false）：重复键按多重集语义比较（值相等的重复行互相抵消）。
                // 严格档（true）需调用方在 executionOptions 里显式开启，命中重复键时直接失败。
                .validateUniqueKeys(executionOptions != null && Boolean.TRUE.equals(executionOptions.getValidateUniqueKeys()))
                .allowNullableKeys(executionOptions != null
                        && Boolean.TRUE.equals(executionOptions.getAllowNullableKeys()))
                .maxDifferences(resolveMaxDifferences(executionOptions, attributes))
                .decimalScale(resolveDecimalScale(request != null ? request.getNormalizationSpec() : null))
                .sessionZone(resolveSessionZone(request != null ? request.getNormalizationSpec() : null))
                .maxDurationMs(resolvePositiveLong(executionOptions != null
                        ? executionOptions.getMaxDurationMs() : null, attributes, "maxDurationMs"))
                .maxRowsScanned(resolvePositiveLong(executionOptions != null
                        ? executionOptions.getMaxRowsScanned() : null, attributes, "maxRowsScanned"))
                .maxBytesFetched(resolvePositiveLong(executionOptions != null
                        ? executionOptions.getMaxBytesFetched() : null, attributes, "maxBytesFetched"))
                .normalizationSpec(request != null ? request.getNormalizationSpec() : null)
                .withinToleranceMode(WithinToleranceMode.fromString(executionOptions != null
                        ? executionOptions.getWithinTolerance() : null))
                .withinToleranceSamples(resolveWithinToleranceSamples(executionOptions))
                .progressListener(executionOptions != null
                        ? executionOptions.getProgressListener() : null)
                .build();
    }

    /**
     * 取 normalization spec 里第一条 {@code format_number} 规则的 precision；没有配置时用默认 4。
     * 这样"SQL 侧可配精度"和"Java 侧归一化精度"来自同一份配置，而不是各写死一个常数。
     */
    /**
     * 从 normalization spec 的规则参数里取 {@code sessionTimeZone}（IANA 名称或 +08:00 偏移）。
     * 解析失败视为未配置（保留旧行为），避免一个笔误让整次比对失败。
     */
    static java.time.ZoneId resolveSessionZone(com.consilens.connector.api.normalization.NormalizationSpec spec) {
        if (spec == null) {
            return null;
        }
        for (java.util.List<com.consilens.connector.api.normalization.NormalizationRule> rules
                : java.util.Arrays.asList(spec.getGlobal(), spec.getSource(), spec.getTarget())) {
            if (rules == null) {
                continue;
            }
            for (com.consilens.connector.api.normalization.NormalizationRule rule : rules) {
                if (rule == null || rule.getParams() == null) {
                    continue;
                }
                Object zone = rule.getParams().get("sessionTimeZone");
                if (zone == null) {
                    zone = rule.getParams().get("session_time_zone");
                }
                if (zone instanceof String && !((String) zone).isBlank()) {
                    try {
                        return java.time.ZoneId.of(((String) zone).trim());
                    } catch (java.time.DateTimeException ignore) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    static int resolveDecimalScale(com.consilens.connector.api.normalization.NormalizationSpec spec) {
        if (spec == null) {
            return com.consilens.core.algorithm.ValueNormalizer.DEFAULT_DECIMAL_SCALE;
        }
        for (java.util.List<com.consilens.connector.api.normalization.NormalizationRule> rules
                : java.util.Arrays.asList(spec.getGlobal(), spec.getSource(), spec.getTarget())) {
            if (rules == null) {
                continue;
            }
            for (com.consilens.connector.api.normalization.NormalizationRule rule : rules) {
                if (rule == null || !"format_number".equals(rule.getOperation()) || rule.getParams() == null) {
                    continue;
                }
                Object precision = rule.getParams().get("precision");
                if (precision instanceof Number) {
                    int value = ((Number) precision).intValue();
                    if (value >= 0 && value <= 18) {
                        return value;
                    }
                }
            }
        }
        return com.consilens.core.algorithm.ValueNormalizer.DEFAULT_DECIMAL_SCALE;
    }

    public TableDiffer.DifferConfig toDifferConfig() {
        return new TableDiffer.DifferConfig(
                bisectionFactor,
                bisectionThreshold,
                checksumAlgorithm,
                localCompareMode,
                concurrencyConfig,
                maxDifferences,
                validateUniqueKeys,
                decimalScale,
                allowNullableKeys,
                maxDurationMs,
                maxRowsScanned,
                maxBytesFetched,
                normalizationSpec,
                withinToleranceMode,
                withinToleranceSamples);
    }

    public CompareExecutionSettings withChecksumAlgorithm(ChecksumAlgorithm checksumAlgorithm) {
        if (this.checksumAlgorithm == checksumAlgorithm) {
            return this;
        }
        return CompareExecutionSettings.builder()
                .bisectionFactor(bisectionFactor)
                .bisectionThreshold(bisectionThreshold)
                .checksumAlgorithm(checksumAlgorithm)
                .localCompareMode(localCompareMode)
                .concurrencyConfig(concurrencyConfig)
                .validateUniqueKeys(validateUniqueKeys)
                .allowNullableKeys(allowNullableKeys)
                .maxDifferences(maxDifferences)
                // 降级只替换算法；归一化精度/会话时区/预算/容差必须保持原配置，
                // 否则 SQL 与 Java 两侧的数值/时间口径会悄悄分叉。
                .decimalScale(decimalScale)
                .sessionZone(sessionZone)
                .maxDurationMs(maxDurationMs)
                .maxRowsScanned(maxRowsScanned)
                .maxBytesFetched(maxBytesFetched)
                .normalizationSpec(normalizationSpec)
                .withinToleranceMode(withinToleranceMode)
                .withinToleranceSamples(withinToleranceSamples)
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

    /** 样本上限：未配置用默认值，负数按 0（只计数不保留行）处理，避免负上限造成意外截断。 */
    private static int resolveWithinToleranceSamples(CompareExecutionOptions options) {
        if (options == null || options.getWithinToleranceSamples() == null) {
            return DEFAULT_WITHIN_TOLERANCE_SAMPLES;
        }
        return Math.min(MAX_WITHIN_TOLERANCE_SAMPLES, Math.max(0, options.getWithinToleranceSamples()));
    }

    private static long resolvePositiveLong(Long explicit, Map<String, Object> attributes, String attributeName) {
        if (explicit != null && explicit > 0) {
            return explicit;
        }
        if (attributes != null) {
            Object value = attributes.get(attributeName);
            if (value instanceof Number) {
                long parsed = ((Number) value).longValue();
                if (parsed > 0) {
                    return parsed;
                }
                return Long.MAX_VALUE;
            }
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                try {
                    long parsed = Long.parseLong(((String) value).trim());
                    if (parsed > 0) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // 解析失败视为未配置，保持不限；不要让一个笔误让整次比对失败
                }
            }
        }
        return Long.MAX_VALUE;
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
