package com.consilens.cli.model;

import com.consilens.core.validation.ValidationException;
import com.consilens.core.validation.ValidationFramework;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

/**
 * Comparison target and column configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class ComparisonConfig {

    @JsonProperty("tables")
    private StringPairConfig tables;

    @JsonProperty("keys")
    private ListPairConfig keys;

    @JsonProperty("comparisons")
    @JsonAlias("compareColumns")
    private ListPairConfig comparisons;

    @JsonProperty("extraColumns")
    private List<String> extraColumns;

    @JsonProperty("filters")
    @JsonAlias("where")
    private StringPairConfig filters;

    @JsonProperty("updateColumn")
    private String updateColumn;

    public void validate() throws ValidationException {
        ValidationFramework.forContext("comparison")
                .validate(tables, "comparison.tables", Objects::nonNull, "comparison.tables 配置不能为空")
                .validate(keys, "comparison.keys", Objects::nonNull, "comparison.keys 配置不能为空")
                .throwIfInvalid();

        tables.validate("comparison.tables");
        keys.validate("comparison.keys");
        if (keys.hasMismatchedSize()) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", "comparison.keys 两侧数量必须一致");
        }

        if (comparisons != null) {
            comparisons.validate("comparison.comparisons");
            if (!comparisons.isBothEmpty() && comparisons.hasMismatchedSize()) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION", "comparison.comparisons 两侧数量必须一致");
            }
        }

        if (filters != null) {
            boolean hasSourceWhere = filters.getSource() != null && !filters.getSource().trim().isEmpty();
            boolean hasTargetWhere = filters.getTarget() != null && !filters.getTarget().trim().isEmpty();
            if (hasSourceWhere != hasTargetWhere) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION", "comparison.filters 需要同时配置 source 和 target");
            }
            if (hasSourceWhere) {
                filters.validate("comparison.filters");
            }
        }
    }

    public ListPairConfig getCompareColumns() {
        return comparisons;
    }

    public void setCompareColumns(ListPairConfig compareColumns) {
        this.comparisons = compareColumns;
    }

    public StringPairConfig getWhere() {
        return filters;
    }

    public void setWhere(StringPairConfig where) {
        this.filters = where;
    }
}
