package com.consilens.cli.model;

import com.consilens.core.validation.ValidationException;
import com.consilens.core.validation.ValidationFramework;
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

    @JsonProperty("compareColumns")
    private ListPairConfig compareColumns;

    @JsonProperty("extraColumns")
    private List<String> extraColumns;

    @JsonProperty("where")
    private StringPairConfig where;

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

        if (compareColumns != null) {
            compareColumns.validate("comparison.compareColumns");
            if (!compareColumns.isBothEmpty() && compareColumns.hasMismatchedSize()) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION", "comparison.compareColumns 两侧数量必须一致");
            }
        }

        if (where != null) {
            boolean hasSourceWhere = where.getSource() != null && !where.getSource().trim().isEmpty();
            boolean hasTargetWhere = where.getTarget() != null && !where.getTarget().trim().isEmpty();
            if (hasSourceWhere != hasTargetWhere) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION", "comparison.where 需要同时配置 source 和 target");
            }
            if (hasSourceWhere) {
                where.validate("comparison.where");
            }
        }
    }
}
