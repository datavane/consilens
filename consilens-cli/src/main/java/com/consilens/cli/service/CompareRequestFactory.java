package com.consilens.cli.service;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CompareMappingConfig;
import com.consilens.cli.model.ComparisonConfig;
import com.consilens.cli.model.ConnectionConfig;
import com.consilens.cli.model.FieldExpressionConfig;
import com.consilens.cli.model.normalization.NormalizationConfig;
import com.consilens.cli.model.normalization.TypeNormalizationRule;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.normalization.MatchSpec;
import com.consilens.connector.api.normalization.NormalizationRule;
import com.consilens.connector.api.normalization.NormalizationSpec;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.ComparePlanTypes;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareStrategyPreference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class CompareRequestFactory {

    public CompareRequest create(CliConfiguration config) {
        ComparisonConfig comparison = config.getComparison();
        BuildResult buildResult = buildComparison(comparison);
        ConnectorConfig source = ConnectorConfigMapper.toConnectorConfig(config.getSource());
        ConnectorConfig target = ConnectorConfigMapper.toConnectorConfig(config.getTarget());
        if (hasMappings(comparison)) {
            source = compileMappingsToSqlResource(source, comparison, true);
            target = compileMappingsToSqlResource(target, comparison, false);
        }

        return CompareRequest.builder()
                .source(source)
                .target(target)
                .sourceKeySpec(buildResult.sourceKeySpec)
                .targetKeySpec(buildResult.targetKeySpec)
                .sourceComparisons(buildResult.sourceComparisons)
                .targetComparisons(buildResult.targetComparisons)
                .sourceFilter(buildResult.sourceFilter)
                .targetFilter(buildResult.targetFilter)
                .normalizationSpec(toNormalizationSpec(config.getNormalization()))
                .strategyPreference(toStrategyPreference(config))
                .executionOptions(toExecutionOptions(config))
                .build();
    }

    public List<String> sourceColumns(CliConfiguration config) {
        return logicalColumns(config, true);
    }

    public List<String> targetColumns(CliConfiguration config) {
        return logicalColumns(config, false);
    }

    public List<String> logicalSortKeys(CliConfiguration config) {
        ComparisonConfig comparison = config.getComparison();
        if (hasMappings(comparison)) {
            List<String> mappedKeys = new ArrayList<>();
            for (CompareMappingConfig mapping : comparison.getMappings()) {
                if (Boolean.TRUE.equals(mapping.getKey())) {
                    mappedKeys.add(mapping.getName());
                }
            }
            if (!mappedKeys.isEmpty()) {
                return mappedKeys;
            }
        }
        return comparison.getKeys() != null && comparison.getKeys().getSource() != null
                ? comparison.getKeys().getSource()
                : List.of();
    }

    private List<String> logicalColumns(CliConfiguration config, boolean sourceSide) {
        ComparisonConfig comparison = config.getComparison();
        List<String> columns = new ArrayList<>(logicalSortKeys(config));
        if (hasMappings(comparison)) {
            for (CompareMappingConfig mapping : comparison.getMappings()) {
                if (!Boolean.TRUE.equals(mapping.getKey())
                        && !Boolean.FALSE.equals(mapping.getCompare())
                        && !isMappingExcluded(comparison, mapping.getName())
                        && !columns.contains(mapping.getName())) {
                    columns.add(mapping.getName());
                }
            }
            return columns;
        }
        List<String> sideFields = comparison.getFields() != null
                ? (sourceSide ? comparison.getFields().getSource() : comparison.getFields().getTarget())
                : null;
        if (sideFields != null) {
            for (String field : sideFields) {
                if (!isExcluded(excludeFields(comparison, sourceSide), field) && !columns.contains(field)) {
                    columns.add(field);
                }
            }
        }
        if (comparison.getExtraColumns() != null) {
            for (String extraColumn : comparison.getExtraColumns()) {
                if (!columns.contains(extraColumn)) {
                    columns.add(extraColumn);
                }
            }
        }
        return columns;
    }

    private BuildResult buildComparison(ComparisonConfig comparison) {
        if (hasMappings(comparison)) {
            List<String> keys = mappedKeys(comparison);
            List<String> fields = mappedCompareFields(comparison);
            return BuildResult.builder()
                    .sourceKeySpec(toKeySpec(keys))
                    .targetKeySpec(toKeySpec(keys))
                    .sourceComparisons(toComparisonSpec(fields, mappingExcludeFields(comparison)))
                    .targetComparisons(toComparisonSpec(fields, mappingExcludeFields(comparison)))
                    .sourceFilter(null)
                    .targetFilter(null)
                    .build();
        }
        return BuildResult.builder()
                .sourceKeySpec(toKeySpec(comparison.getKeys().getSource()))
                .targetKeySpec(toKeySpec(comparison.getKeys().getTarget()))
                .sourceComparisons(toComparisonSpec(comparison.getFields() != null
                        ? comparison.getFields().getSource()
                        : null, excludeFields(comparison, true)))
                .targetComparisons(toComparisonSpec(comparison.getFields() != null
                        ? comparison.getFields().getTarget()
                        : null, excludeFields(comparison, false)))
                .sourceFilter(toPredicateSpec(comparison.getFilters() != null ? comparison.getFilters().getSource() : null))
                .targetFilter(toPredicateSpec(comparison.getFilters() != null ? comparison.getFilters().getTarget() : null))
                .build();
    }

    private boolean hasMappings(ComparisonConfig comparison) {
        return comparison != null && comparison.getMappings() != null && !comparison.getMappings().isEmpty();
    }

    private List<String> mappedKeys(ComparisonConfig comparison) {
        List<String> result = new ArrayList<>();
        for (CompareMappingConfig mapping : comparison.getMappings()) {
            if (Boolean.TRUE.equals(mapping.getKey())) {
                result.add(mapping.getName());
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        return comparison.getKeys() != null && comparison.getKeys().getSource() != null
                ? List.copyOf(comparison.getKeys().getSource())
                : List.of();
    }

    private List<String> mappedCompareFields(ComparisonConfig comparison) {
        List<String> result = new ArrayList<>();
        for (CompareMappingConfig mapping : comparison.getMappings()) {
            if (!Boolean.TRUE.equals(mapping.getKey())
                    && !Boolean.FALSE.equals(mapping.getCompare())
                    && !isMappingExcluded(comparison, mapping.getName())) {
                result.add(mapping.getName());
            }
        }
        return result;
    }

    private ConnectorConfig compileMappingsToSqlResource(ConnectorConfig config,
                                                         ComparisonConfig comparison,
                                                         boolean sourceSide) {
        ResourceLocator resource = config.getResource();
        if (resource == null || !"table".equalsIgnoreCase(resource.getType())) {
            return config;
        }
        String sql = buildMappedSql(resource, comparison, sourceSide);
        ResourceLocator sqlResource = ResourceLocator.builder()
                .type("sql")
                .name(resource.getName())
                .path(sql)
                .options(resource.getOptions())
                .build();
        return ConnectorConfig.builder()
                .type(config.getType())
                .name(config.getName())
                .connection(config.getConnection())
                .resource(sqlResource)
                .readOptions(config.getReadOptions())
                .build();
    }

    private String buildMappedSql(ResourceLocator resource,
                                  ComparisonConfig comparison,
                                  boolean sourceSide) {
        LinkedHashSet<String> selectExpressions = new LinkedHashSet<>();
        List<String> physicalKeys = sourceSide
                ? comparison.getKeys().getSource()
                : comparison.getKeys().getTarget();
        List<String> logicalKeys = mappedKeys(comparison);
        for (int i = 0; i < physicalKeys.size(); i++) {
            String physicalKey = physicalKeys.get(i);
            String logicalKey = i < logicalKeys.size() ? logicalKeys.get(i) : physicalKey;
            selectExpressions.add(expressionAlias(physicalKey, logicalKey));
        }
        for (CompareMappingConfig mapping : comparison.getMappings()) {
            if (!Boolean.TRUE.equals(mapping.getKey()) && isMappingExcluded(comparison, mapping.getName())) {
                continue;
            }
            FieldExpressionConfig expression = sourceSide ? mapping.getSource() : mapping.getTarget();
            selectExpressions.add(expressionAlias(toSqlExpression(expression), mapping.getName()));
        }
        String filter = comparison.getFilters() != null
                ? (sourceSide ? comparison.getFilters().getSource() : comparison.getFilters().getTarget())
                : null;
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(String.join(", ", selectExpressions));
        sql.append(" FROM ").append(tableRef(resource));
        if (filter != null && !filter.trim().isEmpty()) {
            sql.append(" WHERE ").append(filter.trim());
        }
        return sql.toString();
    }

    private String expressionAlias(String expression, String alias) {
        if (expression.equals(alias)) {
            return expression;
        }
        return expression + " AS " + alias;
    }

    private String toSqlExpression(FieldExpressionConfig expression) {
        if (expression.getColumn() != null && !expression.getColumn().trim().isEmpty()) {
            return expression.getColumn().trim();
        }
        if (expression.getExpression() != null && !expression.getExpression().trim().isEmpty()) {
            return expression.getExpression().trim();
        }
        Object literal = expression.getLiteral();
        if (literal instanceof Number || literal instanceof Boolean) {
            return String.valueOf(literal);
        }
        return "'" + String.valueOf(literal).replace("'", "''") + "'";
    }

    private String tableRef(ResourceLocator resource) {
        return resource.getName().trim();
    }

    private KeySpec toKeySpec(List<String> fields) {
        return KeySpec.builder()
                .fields(fields != null ? List.copyOf(fields) : Collections.emptyList())
                .build();
    }

    private ComparisonSpec toComparisonSpec(List<String> fields, List<String> exclude) {
        return ComparisonSpec.builder()
                .fields(fields != null ? List.copyOf(fields) : Collections.emptyList())
                .exclude(normalizeList(exclude))
                .build();
    }

    private List<String> excludeFields(ComparisonConfig comparison, boolean sourceSide) {
        if (comparison == null || comparison.getExclude() == null) {
            return Collections.emptyList();
        }
        List<String> fields = sourceSide ? comparison.getExclude().getSource() : comparison.getExclude().getTarget();
        return normalizeList(fields);
    }

    private List<String> mappingExcludeFields(ComparisonConfig comparison) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.addAll(excludeFields(comparison, true));
        result.addAll(excludeFields(comparison, false));
        return List.copyOf(result);
    }

    private boolean isMappingExcluded(ComparisonConfig comparison, String field) {
        return isExcluded(mappingExcludeFields(comparison), field);
    }

    private boolean isExcluded(List<String> exclude, String field) {
        if (exclude == null || field == null) {
            return false;
        }
        return exclude.stream()
                .filter(value -> value != null)
                .map(String::trim)
                .anyMatch(field::equals);
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private PredicateSpec toPredicateSpec(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }
        return PredicateSpec.builder()
                .type("sql")
                .expression(expression)
                .build();
    }

    private CompareStrategyPreference toStrategyPreference(CliConfiguration config) {
        List<String> preferredPlans;
        boolean allowFallback;
        if ("join".equalsIgnoreCase(config.getStrategyMode())) {
            preferredPlans = List.of(ComparePlanTypes.SERVER_JOIN);
            allowFallback = false;
        } else {
            preferredPlans = List.of(
                    ComparePlanTypes.PUSHDOWN_CHECKSUM,
                    ComparePlanTypes.KEY_HASH,
                    ComparePlanTypes.STREAMING_MERGE);
            allowFallback = true;
        }

        return CompareStrategyPreference.builder()
                .preferredPlans(preferredPlans)
                .allowFallback(allowFallback)
                .build();
    }

    private CompareExecutionOptions toExecutionOptions(CliConfiguration config) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (config.getConcurrency() != null) {
            attributes.put("concurrencyConfig", config.getConcurrency());
        }

        return CompareExecutionOptions.builder()
                .bisectionFactor(config.getStrategy().getBisectionFactor())
                .bisectionThreshold(resolveBisectionThreshold(config))
                .enableProfiling(Boolean.TRUE.equals(config.getStrategy().getEnableProfiling()))
                .checksumAlgorithm(config.getAlgorithm())
                .localCompareMode(config.getStrategy().getLocalCompare() != null
                        ? config.getStrategy().getLocalCompare().getMode()
                        : null)
                .validateUniqueKeys(false)
                .attributes(attributes.isEmpty() ? null : attributes)
                .build();
    }

    private long resolveBisectionThreshold(CliConfiguration config) {
        if (config.getStrategy().getBisectionThreshold() != null) {
            return config.getStrategy().getBisectionThreshold();
        }
        if (config.getStrategy().getBatchSize() != null) {
            return config.getStrategy().getBatchSize() * 10L;
        }
        return 10000L;
    }

    private NormalizationSpec toNormalizationSpec(NormalizationConfig normalizationConfig) {
        if (normalizationConfig == null) {
            return null;
        }
        return NormalizationSpec.builder()
                .global(toNormalizationRules(normalizationConfig.getGlobal()))
                .source(toNormalizationRules(normalizationConfig.getSource()))
                .target(toNormalizationRules(normalizationConfig.getTarget()))
                .build();
    }

    private List<NormalizationRule> toNormalizationRules(Map<String, TypeNormalizationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        List<NormalizationRule> result = new ArrayList<>();
        for (Map.Entry<String, TypeNormalizationRule> entry : rules.entrySet()) {
            String canonicalType = NormalizationTypeMapper.toMatchType(entry.getKey());
            TypeNormalizationRule rule = entry.getValue();
            if (canonicalType == null || rule == null) {
                continue;
            }
            result.addAll(toNormalizationRules(canonicalType, rule));
        }
        return result.isEmpty() ? null : result;
    }

    private List<NormalizationRule> toNormalizationRules(String type, TypeNormalizationRule rule) {
        List<NormalizationRule> result = new ArrayList<>();
        if (rule.getPrecision() != null || rule.getRounding() != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (rule.getPrecision() != null) {
                params.put("precision", rule.getPrecision());
            }
            if (rule.getRounding() != null) {
                params.put("rounding", rule.getRounding());
            }
            result.add(normalizationRule(type, "format_number", params));
        }
        if (rule.getFormat() != null || rule.getTimezone() != null || rule.getComparisonMode() != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (rule.getFormat() != null) {
                params.put("format", rule.getFormat());
            }
            if (rule.getTimezone() != null) {
                params.put("timezone", rule.getTimezone());
            }
            if (rule.getComparisonMode() != null) {
                params.put("comparisonMode", rule.getComparisonMode());
            }
            result.add(normalizationRule(type, "format_datetime", params));
        }
        if (rule.getEncoding() != null || rule.getUppercase() != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (rule.getEncoding() != null) {
                params.put("encoding", rule.getEncoding());
            }
            if (rule.getUppercase() != null) {
                params.put("uppercase", rule.getUppercase());
            }
            result.add(normalizationRule(type, "encode", params));
        }
        if ("boolean".equals(type)
                && (rule.getTrueValue() != null || rule.getFalseValue() != null || rule.getNullValue() != null)) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (rule.getTrueValue() != null) {
                params.put("trueValue", rule.getTrueValue());
            }
            if (rule.getFalseValue() != null) {
                params.put("falseValue", rule.getFalseValue());
            }
            if (rule.getNullValue() != null) {
                params.put("nullValue", rule.getNullValue());
            }
            result.add(normalizationRule(type, "map_boolean", params));
        } else if ("string".equals(type) && rule.getNullValue() != null) {
            result.add(normalizationRule(type, "normalize_string", Map.of("nullValue", rule.getNullValue())));
        }
        return result;
    }

    private NormalizationRule normalizationRule(String type, String operation, Map<String, Object> params) {
        return NormalizationRule.builder()
                .match(MatchSpec.builder().type(type).build())
                .operation(operation)
                .params(params)
                .build();
    }

    @lombok.Builder
    @lombok.Getter
    private static class BuildResult {
        private KeySpec sourceKeySpec;
        private KeySpec targetKeySpec;
        private ComparisonSpec sourceComparisons;
        private ComparisonSpec targetComparisons;
        private PredicateSpec sourceFilter;
        private PredicateSpec targetFilter;
    }
}
