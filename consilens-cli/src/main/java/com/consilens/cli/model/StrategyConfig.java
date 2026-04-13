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
    @JsonProperty("enableProfiling")
    private Boolean enableProfiling = false;

    @Builder.Default
    @JsonProperty("localCompare")
    private LocalCompareConfig localCompare = LocalCompareConfig.builder().mode("full").build();

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
                .throwIfInvalid();

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
