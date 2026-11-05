package com.consilens.cli.model;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.ComparisonStrategy;
import com.consilens.core.validation.ValidationException;
import com.consilens.core.validation.ValidationFramework;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Comparison strategy configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StrategyConfig {

    @Builder.Default
    @JsonProperty("mode")
    private String mode = "checksum";

    @Builder.Default
    @JsonProperty("algorithm")
    private String algorithm = "concat";

    @Builder.Default
    @JsonProperty("bisectionFactor")
    private Integer bisectionFactor = 4;

    @JsonProperty("bisectionThreshold")
    private Long bisectionThreshold;

    @Builder.Default
    @JsonProperty("batchSize")
    private Integer batchSize = 1000;

    @Builder.Default
    @JsonProperty("localCompare")
    private LocalCompareConfig localCompare = LocalCompareConfig.builder().mode("full").build();

    @Builder.Default
    @JsonProperty("maxDifferences")
    private Long maxDifferences = 1_000_000L;

    /**
     * 是否要求键唯一（严格档）。false（默认）按多重集语义比较：重复键中值相等的行互相抵消；
     * true 时比对开始前做唯一键探针，命中重复键直接失败。
     */
    @Builder.Default
    @JsonProperty("validateUniqueKeys")
    private Boolean validateUniqueKeys = false;

    /**
     * 是否允许可空键列。false（默认）在键列 nullable 时直接拒绝，避免 NULL 键行
     * 因 SQL 三值逻辑落在分段之外被静默漏报；true 时允许可空键，NULL 键按
     * 引擎的 IS NULL/归一化语义参与比较。
     */
    @Builder.Default
    @JsonProperty("allowNullableKeys")
    private Boolean allowNullableKeys = false;

    /** 墙钟预算（毫秒）；null 表示不限。超限抛 BudgetExceededException 并保留部分结果。 */
    @JsonProperty("maxDurationMs")
    private Long maxDurationMs;

    /** 累计扫描行数预算；null 表示不限。 */
    @JsonProperty("maxRowsScanned")
    private Long maxRowsScanned;

    /** 累计取回字节预算；null 表示不限。 */
    @JsonProperty("maxBytesFetched")
    private Long maxBytesFetched;

    /**
     * 显式计划偏好（可选）。为空时按 {@link #mode} 推导：{@code join} → server-join 且禁止回退，
     * 其余 → pushdown-checksum / streaming-merge 且允许回退。
     *
     * <p>用于表达"只允许某条计划、不允许回退"的场景（例如专门校验 pushdown-checksum 本身）。
     */
    @JsonProperty("preferredPlans")
    private List<String> preferredPlans;

    /** 与 {@link #preferredPlans} 配套；显式给了计划但没给该值时，默认不允许回退。 */
    @JsonProperty("allowFallback")
    private Boolean allowFallback;

    @JsonIgnore
    public ComparisonStrategy getModeEnum() {
        return ComparisonStrategy.fromString(mode);
    }

    @JsonIgnore
    public ChecksumAlgorithm getAlgorithmEnum() {
        return ChecksumAlgorithm.fromString(algorithm);
    }

    public void validate() throws ValidationException {
        ValidationFramework.forContext("strategy")
                .notEmpty(mode, "strategy.mode")
                .notEmpty(algorithm, "strategy.algorithm")
                .positive(batchSize, "strategy.batchSize")
                .positive(bisectionFactor, "strategy.bisectionFactor")
                .positive(maxDifferences, "strategy.maxDifferences")
                .throwIfInvalid();

        if (maxDurationMs != null && maxDurationMs <= 0) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", "strategy.maxDurationMs 必须为正数");
        }
        if (maxRowsScanned != null && maxRowsScanned <= 0) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", "strategy.maxRowsScanned 必须为正数");
        }
        if (maxBytesFetched != null && maxBytesFetched <= 0) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", "strategy.maxBytesFetched 必须为正数");
        }

        String normalizedMode = mode == null ? null : mode.trim().toLowerCase();
        if ("local".equals(normalizedMode)) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", "strategy.mode=local 尚未实现，请使用 checksum 或 join");
        }
        if (!"checksum".equals(normalizedMode) && !"join".equals(normalizedMode)) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", "strategy.mode 必须是 checksum 或 join");
        }

        String normalizedAlgorithm = algorithm == null ? null : algorithm.trim().toLowerCase();
        if (!"concat".equals(normalizedAlgorithm)
                && !"xor".equals(normalizedAlgorithm)) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", "strategy.algorithm 必须是 concat 或 xor");
        }

        if (localCompare != null) {
            localCompare.validate();
        }
    }
}
