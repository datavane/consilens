package com.consilens.cli.service;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CompareMappingConfig;
import com.consilens.cli.model.ComparisonConfig;
import com.consilens.cli.model.ConnectionConfig;
import com.consilens.cli.model.FieldExpressionConfig;
import com.consilens.cli.model.normalization.NormalizationConfig;
import com.consilens.cli.model.normalization.TolerancePolicyConfig;
import com.consilens.cli.model.normalization.TypeNormalizationRule;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.normalization.NormalizationSpec;
import com.consilens.connector.api.normalization.NormalizationSpecs;
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
        return create(config, null);
    }

    /**
     * @param window optional incremental window; compiled into the existing
     *               {@link PredicateSpec} filters. File sources are rejected
     *               because the file scanner does not apply SQL predicates.
     */
    public CompareRequest create(CliConfiguration config, WindowSpec window) {
        ComparisonConfig comparison = config.getComparison();
        ConnectorConfig source = ConnectorConfigMapper.toConnectorConfig(config.getSource());
        ConnectorConfig target = ConnectorConfigMapper.toConnectorConfig(config.getTarget());
        if (window != null) {
            window.validate();
            if (hasMappings(comparison)) {
                throw new IllegalArgumentException("incremental window does not support comparison.mappings");
            }
            if (isFile(config.getSource()) || isFile(config.getTarget())) {
                throw new IllegalArgumentException("incremental window requires a JDBC source;"
                        + " file sources do not apply SQL filters");
            }
        }
        boolean mappings = hasMappings(comparison);
        // mappings 只对 table 资源编译成带 WHERE 的 SQL；SQL 资源原样透传，
        // 此时 filter 必须作为 PredicateSpec 传给引擎，否则会被静默丢弃。
        boolean sourceFilterEmbedded = mappings && isTableResource(source);
        boolean targetFilterEmbedded = mappings && isTableResource(target);
        BuildResult buildResult = buildComparison(comparison, sourceFilterEmbedded, targetFilterEmbedded);
        if (mappings) {
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
                .sourceFilter(mergeFilters(buildResult.sourceFilter, windowPredicate(window, true)))
                .targetFilter(mergeFilters(buildResult.targetFilter, windowPredicate(window, false)))
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
        return logicalSortKeys(config, true);
    }

    private List<String> logicalSortKeys(CliConfiguration config, boolean sourceSide) {
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
        if (comparison.getKeys() == null) {
            return List.of();
        }
        // keys.source / keys.target 可以不同名：目标侧必须用 keys.target，
        // 否则目标列清单里会混进源侧的列名，生成引用不存在列的 SQL（或整列全是 NULL）。
        List<String> sideKeys = sourceSide ? comparison.getKeys().getSource()
                : comparison.getKeys().getTarget();
        if (sideKeys != null && !sideKeys.isEmpty()) {
            return sideKeys;
        }
        return comparison.getKeys().getSource() != null
                ? comparison.getKeys().getSource()
                : (comparison.getKeys().getTarget() != null ? comparison.getKeys().getTarget() : List.of());
    }

    private List<String> logicalColumns(CliConfiguration config, boolean sourceSide) {
        ComparisonConfig comparison = config.getComparison();
        List<String> columns = new ArrayList<>(logicalSortKeys(config, sourceSide));
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

    private BuildResult buildComparison(ComparisonConfig comparison,
                                        boolean sourceFilterEmbedded,
                                        boolean targetFilterEmbedded) {
        if (hasMappings(comparison)) {
            List<String> keys = mappedKeys(comparison);
            List<String> fields = mappedCompareFields(comparison);
            return BuildResult.builder()
                    .sourceKeySpec(toKeySpec(keys))
                    .targetKeySpec(toKeySpec(keys))
                    .sourceComparisons(toComparisonSpec(fields, mappingExcludeFields(comparison)))
                    .targetComparisons(toComparisonSpec(fields, mappingExcludeFields(comparison)))
                    .sourceFilter(sourceFilterEmbedded ? null
                            : toPredicateSpec(comparison.getFilters() != null
                                    ? comparison.getFilters().getSource() : null))
                    .targetFilter(targetFilterEmbedded ? null
                            : toPredicateSpec(comparison.getFilters() != null
                                    ? comparison.getFilters().getTarget() : null))
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

    private boolean isTableResource(ConnectorConfig config) {
        return config != null && config.getResource() != null
                && "table".equalsIgnoreCase(config.getResource().getType());
    }

    private boolean isFile(ConnectionConfig config) {
        return config != null && config.getType() != null
                && "file".equalsIgnoreCase(config.getType().trim());
    }

    /**
     * Compiles the window into a SQL predicate. Values are quoted as SQL string
     * literals so timestamps/dates work across JDBC dialects; column names are
     * passed through as configured.
     */
    private PredicateSpec windowPredicate(WindowSpec window, boolean sourceSide) {
        if (window == null) {
            return null;
        }
        String column = sourceSide ? window.sourceColumn() : window.targetColumn();
        List<String> parts = new ArrayList<>();
        if (!WindowSpec.blank(window.getFrom())) {
            parts.add(column + " >= " + sqlLiteral(window.getFrom()));
        }
        if (!WindowSpec.blank(window.getTo())) {
            parts.add(column + " < " + sqlLiteral(window.getTo()));
        }
        if (parts.isEmpty()) {
            return null;
        }
        String expression = String.join(" AND ", parts);
        if (parts.size() > 1) {
            expression = "(" + expression + ")";
        }
        return PredicateSpec.builder().type("sql").expression(expression).build();
    }

    private PredicateSpec mergeFilters(PredicateSpec existing, PredicateSpec window) {
        if (existing == null || existing.getExpression() == null
                || existing.getExpression().isBlank()) {
            return window;
        }
        if (window == null) {
            return existing;
        }
        return PredicateSpec.builder()
                .type("sql")
                .expression("(" + existing.getExpression() + ") AND (" + window.getExpression() + ")")
                .build();
    }

    private String sqlLiteral(String value) {
        return "'" + value.trim().replace("'", "''") + "'";
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
        List<String> physicalKeys = comparison.getKeys() == null ? null
                : (sourceSide ? comparison.getKeys().getSource() : comparison.getKeys().getTarget());
        List<String> logicalKeys = mappedKeys(comparison);
        Map<String, CompareMappingConfig> keyMappings = new LinkedHashMap<>();
        for (CompareMappingConfig mapping : comparison.getMappings()) {
            if (Boolean.TRUE.equals(mapping.getKey())) {
                keyMappings.put(mapping.getName(), mapping);
            }
        }
        if (physicalKeys != null) {
            for (int i = 0; i < physicalKeys.size(); i++) {
                String physicalKey = physicalKeys.get(i);
                String logicalKey = i < logicalKeys.size() ? logicalKeys.get(i) : physicalKey;
                CompareMappingConfig keyMapping = keyMappings.get(logicalKey);
                String expression = keyMapping == null
                        ? physicalKey
                        : toSqlExpression(sourceSide ? keyMapping.getSource() : keyMapping.getTarget());
                selectExpressions.add(expressionAlias(expression, logicalKey));
            }
        } else {
            // 只提供 mappings 且用 key=true 声明主键时，keys 段可以省略。
            for (CompareMappingConfig mapping : comparison.getMappings()) {
                if (Boolean.TRUE.equals(mapping.getKey())) {
                    selectExpressions.add(expressionAlias(
                            toSqlExpression(sourceSide ? mapping.getSource() : mapping.getTarget()),
                            mapping.getName()));
                }
            }
        }
        for (CompareMappingConfig mapping : comparison.getMappings()) {
            // key 列已在上面按 physical/logical 对齐处理，不能重复 select。
            if (Boolean.TRUE.equals(mapping.getKey())
                    || isMappingExcluded(comparison, mapping.getName())) {
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
        List<String> explicitPlans = config.getStrategy() == null
                ? null : config.getStrategy().getPreferredPlans();
        if (explicitPlans != null && !explicitPlans.isEmpty()) {
            // 显式计划优先：支持"只跑 pushdown-checksum / 只跑 server-join 且不许回退"。
            Boolean allowFallback = config.getStrategy().getAllowFallback();
            return CompareStrategyPreference.builder()
                    .preferredPlans(List.copyOf(explicitPlans))
                    .allowFallback(allowFallback != null && allowFallback)
                    .build();
        }

        List<String> preferredPlans;
        boolean allowFallback;
        if ("join".equalsIgnoreCase(config.getStrategyMode())) {
            preferredPlans = List.of(ComparePlanTypes.SERVER_JOIN);
            allowFallback = false;
        } else {
            preferredPlans = List.of(
                    ComparePlanTypes.PUSHDOWN_CHECKSUM,
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
        if (config.getStrategy().getMaxDifferences() != null) {
            attributes.put("maxDifferences", config.getStrategy().getMaxDifferences());
        }

        return CompareExecutionOptions.builder()
                .bisectionFactor(config.getStrategy().getBisectionFactor())
                .bisectionThreshold(resolveBisectionThreshold(config))
                .checksumAlgorithm(config.getAlgorithm())
                .localCompareMode(config.getStrategy().getLocalCompare() != null
                        ? config.getStrategy().getLocalCompare().getMode()
                        : null)
                // 键唯一档位走配置：默认开放档（重复键按多重集语义比较），严格档在 strategy 里显式打开
                .validateUniqueKeys(Boolean.TRUE.equals(config.getStrategy().getValidateUniqueKeys()))
                // 可空键档位走配置：默认关闭（键列可空即拒绝），true 时允许 NULL 键参与比较
                .allowNullableKeys(Boolean.TRUE.equals(config.getStrategy().getAllowNullableKeys()))
                .maxDifferences(config.getStrategy().getMaxDifferences())
                .maxDurationMs(config.getStrategy().getMaxDurationMs())
                .maxRowsScanned(config.getStrategy().getMaxRowsScanned())
                .maxBytesFetched(config.getStrategy().getMaxBytesFetched())
                // 容差内行怎么处置：默认 ignore（判等、不计差异、报告单列）；
                // report 是审计档，回到"容差内也报差异"的旧口径。这里显式写出生效值，
                // 让 artifact / 集群任务里能直接看到本次执行用的是哪个口径。
                .withinTolerance(tolerancePolicy(config).normalizedMode())
                .withinToleranceSamples(tolerancePolicy(config).effectiveReportSamples())
                .attributes(attributes.isEmpty() ? null : attributes)
                .build();
    }

    private TolerancePolicyConfig tolerancePolicy(CliConfiguration config) {
        return config.getNormalization() != null && config.getNormalization().getTolerance() != null
                ? config.getNormalization().getTolerance()
                : new TolerancePolicyConfig();
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
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                toRuleMaps(normalizationConfig.getGlobal()),
                toRuleMaps(normalizationConfig.getSource()),
                toRuleMaps(normalizationConfig.getTarget()));
        return NormalizationSpecs.withColumnRules(spec, toColumnRuleMaps(normalizationConfig.getColumns()));
    }

    private Map<String, Map<String, Object>> toColumnRuleMaps(Map<String, TypeNormalizationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, TypeNormalizationRule> entry : rules.entrySet()) {
            TypeNormalizationRule rule = entry.getValue();
            if (rule == null) {
                continue;
            }
            // 列级规则当前只开放数值容差（precision 等需要 SQL 侧同步实现）
            Map<String, Object> fields = new LinkedHashMap<>();
            putIfNotNull(fields, "tolerance", rule.getTolerance());
            putIfNotNull(fields, "relativeTolerance", rule.getRelativeTolerance());
            if (!fields.isEmpty()) {
                result.put(entry.getKey(), fields);
            }
        }
        return result;
    }

    private Map<String, Map<String, Object>> toRuleMaps(Map<String, TypeNormalizationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, TypeNormalizationRule> entry : rules.entrySet()) {
            TypeNormalizationRule rule = entry.getValue();
            if (rule == null) {
                continue;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            putIfNotNull(fields, "precision", rule.getPrecision());
            putIfNotNull(fields, "rounding", rule.getRounding());
            putIfNotNull(fields, "format", rule.getFormat());
            putIfNotNull(fields, "timezone", rule.getTimezone());
            putIfNotNull(fields, "comparisonMode", rule.getComparisonMode());
            putIfNotNull(fields, "encoding", rule.getEncoding());
            putIfNotNull(fields, "uppercase", rule.getUppercase());
            putIfNotNull(fields, "trueValue", rule.getTrueValue());
            putIfNotNull(fields, "falseValue", rule.getFalseValue());
            putIfNotNull(fields, "nullValue", rule.getNullValue());
            putIfNotNull(fields, "tolerance", rule.getTolerance());
            putIfNotNull(fields, "relativeTolerance", rule.getRelativeTolerance());
            putIfNotNull(fields, "sessionTimeZone", rule.getSessionTimeZone());
            result.put(entry.getKey(), fields);
        }
        return result;
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
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
