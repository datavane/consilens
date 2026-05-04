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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Comparison target and field configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class ComparisonConfig {

    @JsonProperty("keys")
    private ListPairConfig keys;

    @JsonProperty("fields")
    private ListPairConfig fields;

    @JsonProperty("mappings")
    private List<CompareMappingConfig> mappings;

    @JsonProperty("extraColumns")
    private List<String> extraColumns;

    @JsonProperty("filters")
    @JsonAlias("where")
    private StringPairConfig filters;

    @JsonProperty("updateColumn")
    private String updateColumn;

    public void validate() throws ValidationException {
        ValidationFramework.forContext("comparison")
                .validate(keys, "comparison.keys", Objects::nonNull, "comparison.keys 配置不能为空")
                .throwIfInvalid();

        keys.validate("comparison.keys");
        if (keys.hasMismatchedSize()) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", "comparison.keys 两侧数量必须一致");
        }

        if (fields != null) {
            fields.validate("comparison.fields");
            if (!fields.isBothEmpty() && fields.hasMismatchedSize()) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION", "comparison.fields 两侧数量必须一致");
            }
        }

        if (fields != null && !fields.isBothEmpty() && mappings != null && !mappings.isEmpty()) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", "comparison.fields 与 comparison.mappings 不能同时配置");
        }

        validateMappings();

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

    public StringPairConfig getWhere() {
        return filters;
    }

    public void setWhere(StringPairConfig where) {
        this.filters = where;
    }

    private void validateMappings() {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < mappings.size(); i++) {
            CompareMappingConfig mapping = mappings.get(i);
            if (mapping == null || mapping.getName() == null || mapping.getName().isBlank()) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        "comparison.mappings[" + i + "].name 不能为空");
            }
            String normalizedName = mapping.getName().trim().toLowerCase(Locale.ROOT);
            if (!names.add(normalizedName)) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        "comparison.mappings 存在重复 name: " + mapping.getName());
            }
            if (mapping.getSource() == null || mapping.getTarget() == null) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        "comparison.mappings[" + i + "] 需要同时配置 source 和 target");
            }
            validateFieldExpression("comparison.mappings[" + i + "].source", mapping.getSource());
            validateFieldExpression("comparison.mappings[" + i + "].target", mapping.getTarget());
        }
    }

    private void validateFieldExpression(String fieldName, FieldExpressionConfig expression) {
        int configured = 0;
        configured += blank(expression.getColumn()) ? 0 : 1;
        configured += blank(expression.getExpression()) ? 0 : 1;
        configured += expression.getLiteral() == null ? 0 : 1;
        if (configured != 1) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION",
                    fieldName + " 的 column/expression/literal 必须且只能配置一个");
        }
        validateTrustedSql(fieldName + ".expression", expression.getExpression());
    }

    private void validateTrustedSql(String fieldName, String sql) {
        if (blank(sql)) {
            return;
        }
        String value = sql.trim();
        if (value.contains(";") || value.contains("--") || value.contains("/*") || value.contains("*/")) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", fieldName + " 包含不允许的 SQL 片段");
        }
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
