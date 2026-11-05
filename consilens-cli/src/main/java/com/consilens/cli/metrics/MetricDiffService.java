package com.consilens.cli.metrics;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.ConnectionConfig;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.CanonicalValue;
import com.consilens.connector.api.record.CloseableIterator;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorRegistry;
import com.consilens.core.compare.registry.DefaultConnectorRegistry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Computes aggregate metrics on both sides through the existing
 * {@link RecordScanner} SPI. No new execution backend or SQL dialect logic is
 * introduced; JDBC and file sources both expose CanonicalRecords.
 */
public final class MetricDiffService {

    private static final int AVG_SCALE = 6;
    private static final int PERCENT_SCALE = 4;

    public List<MetricResult> compare(CliConfiguration config, List<MetricDefinition> definitions,
                                      BigDecimal maxAbsoluteDelta, BigDecimal maxRelativePercent,
                                      MetricEngine engine) {
        return compare(config, definitions, maxAbsoluteDelta, maxRelativePercent, engine, List.of());
    }

    public List<MetricResult> compare(CliConfiguration config, List<MetricDefinition> definitions,
                                      BigDecimal maxAbsoluteDelta, BigDecimal maxRelativePercent,
                                      MetricEngine engine, List<String> groupBy) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("at least one metric is required");
        }
        if (maxAbsoluteDelta == null || maxAbsoluteDelta.signum() < 0) {
            throw new IllegalArgumentException("maxAbsoluteDelta must be non-negative");
        }
        if (maxRelativePercent == null || maxRelativePercent.signum() < 0) {
            throw new IllegalArgumentException("maxRelativePercent must be non-negative");
        }
        rejectUnsupportedOptions(config);
        CompareRequest request = new CompareRequestFactory().create(config);

        if (groupBy != null && !groupBy.isEmpty()) {
            return compareGrouped(request, definitions, maxAbsoluteDelta, maxRelativePercent,
                    engine, groupBy);
        }

        List<BigDecimal> sourceValues;
        List<BigDecimal> targetValues;
        if (engine == MetricEngine.DUCKDB) {
            if (!DuckDbMetrics.supports(request.getSource())
                    || !DuckDbMetrics.supports(request.getTarget())) {
                throw new IllegalArgumentException("duckdb metric engine requires local file sources"
                        + " for both sides");
            }
            sourceValues = DuckDbMetrics.compute(request.getSource(), definitions);
            targetValues = DuckDbMetrics.compute(request.getTarget(), definitions);
        } else {
            ConnectorRegistry registry = new DefaultConnectorRegistry();
            ConnectorAdapter sourceConnector = null;
            ConnectorAdapter targetConnector = null;
            DatasetHandle sourceDataset = null;
            DatasetHandle targetDataset = null;
            try {
                sourceConnector = registry.create(request.getSource());
                targetConnector = registry.create(request.getTarget());
                sourceDataset = sourceConnector.openDataset(
                        request.getSource().getResource(), request.getSource().getReadOptions());
                targetDataset = targetConnector.openDataset(
                        request.getTarget().getResource(), request.getTarget().getReadOptions());

                sourceValues = compute(sourceDataset, request.getSource(),
                        request.getSourceKeySpec(), request.getSourceFilter(), "source", definitions);
                targetValues = compute(targetDataset, request.getTarget(),
                        request.getTargetKeySpec(), request.getTargetFilter(), "target", definitions);
            } finally {
                closeQuietly(sourceDataset);
                closeQuietly(targetDataset);
                closeQuietly(sourceConnector);
                closeQuietly(targetConnector);
            }
        }

        List<MetricResult> results = new ArrayList<>();
        for (int index = 0; index < definitions.size(); index++) {
            results.add(compare(definitions.get(index), sourceValues.get(index),
                    targetValues.get(index), maxAbsoluteDelta, maxRelativePercent));
        }
        return results;
    }

    private List<MetricResult> compareGrouped(CompareRequest request,
                                              List<MetricDefinition> definitions,
                                              BigDecimal maxAbsoluteDelta,
                                              BigDecimal maxRelativePercent,
                                              MetricEngine engine, List<String> groupBy) {
        Map<String, GroupValues> sourceGroups;
        Map<String, GroupValues> targetGroups;
        if (engine == MetricEngine.DUCKDB) {
            if (!DuckDbMetrics.supports(request.getSource())
                    || !DuckDbMetrics.supports(request.getTarget())) {
                throw new IllegalArgumentException("duckdb metric engine requires local file sources"
                        + " for both sides");
            }
            sourceGroups = duckDbGroups(DuckDbMetrics.computeGrouped(
                    request.getSource(), definitions, groupBy));
            targetGroups = duckDbGroups(DuckDbMetrics.computeGrouped(
                    request.getTarget(), definitions, groupBy));
        } else {
            ConnectorRegistry registry = new DefaultConnectorRegistry();
            ConnectorAdapter sourceConnector = null;
            ConnectorAdapter targetConnector = null;
            DatasetHandle sourceDataset = null;
            DatasetHandle targetDataset = null;
            try {
                sourceConnector = registry.create(request.getSource());
                targetConnector = registry.create(request.getTarget());
                sourceDataset = sourceConnector.openDataset(
                        request.getSource().getResource(), request.getSource().getReadOptions());
                targetDataset = targetConnector.openDataset(
                        request.getTarget().getResource(), request.getTarget().getReadOptions());
                sourceGroups = computeGrouped(sourceDataset, request.getSource(),
                        request.getSourceKeySpec(), request.getSourceFilter(), "source",
                        definitions, groupBy);
                targetGroups = computeGrouped(targetDataset, request.getTarget(),
                        request.getTargetKeySpec(), request.getTargetFilter(), "target",
                        definitions, groupBy);
            } finally {
                closeQuietly(sourceDataset);
                closeQuietly(targetDataset);
                closeQuietly(sourceConnector);
                closeQuietly(targetConnector);
            }
        }
        return combineGroups(sourceGroups, targetGroups, definitions,
                maxAbsoluteDelta, maxRelativePercent);
    }

    private List<MetricResult> combineGroups(Map<String, GroupValues> sourceGroups,
                                             Map<String, GroupValues> targetGroups,
                                             List<MetricDefinition> definitions,
                                             BigDecimal maxAbsoluteDelta,
                                             BigDecimal maxRelativePercent) {
        Map<String, String> displays = new LinkedHashMap<>();
        sourceGroups.forEach((key, group) -> displays.put(key, group.display));
        targetGroups.forEach((key, group) -> displays.putIfAbsent(key, group.display));

        List<MetricResult> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : displays.entrySet()) {
            GroupValues source = sourceGroups.get(entry.getKey());
            GroupValues target = targetGroups.get(entry.getKey());
            for (int index = 0; index < definitions.size(); index++) {
                BigDecimal sourceValue = source == null ? null : source.values.get(index);
                BigDecimal targetValue = target == null ? null : target.values.get(index);
                results.add(compare(definitions.get(index), sourceValue, targetValue,
                        maxAbsoluteDelta, maxRelativePercent, entry.getValue()));
            }
        }
        return results;
    }

    private Map<String, GroupValues> duckDbGroups(List<DuckDbMetrics.GroupedMetrics> groups) {
        Map<String, GroupValues> result = new LinkedHashMap<>();
        for (DuckDbMetrics.GroupedMetrics group : groups) {
            result.put(group.key(), new GroupValues(group.display(), group.values()));
        }
        return result;
    }

    private Map<String, GroupValues> computeGrouped(DatasetHandle dataset, ConnectorConfig config,
                                                    com.consilens.connector.api.model.KeySpec keySpec,
                                                    com.consilens.connector.api.model.PredicateSpec filter,
                                                    String side, List<MetricDefinition> definitions,
                                                    List<String> groupBy) {
        SchemaDescriptor schema = dataset.getSchema();
        if (schema == null || schema.getFields() == null || schema.getFields().isEmpty()) {
            throw new IllegalArgumentException("Cannot compute metrics: " + side + " schema is empty");
        }
        List<String> columns = new ArrayList<>();
        Map<String, String> actualNames = new LinkedHashMap<>();
        for (FieldDescriptor field : schema.getFields()) {
            columns.add(field.getName());
            actualNames.put(field.getName(), field.getName());
            actualNames.putIfAbsent(field.getName().toLowerCase(Locale.ROOT), field.getName());
        }
        List<String> metricColumns = new ArrayList<>();
        for (MetricDefinition definition : definitions) {
            metricColumns.add(definition.getColumn() == null ? null
                    : actualColumn(actualNames, definition.getColumn(), side));
        }
        List<String> groupColumns = new ArrayList<>();
        for (String group : groupBy) {
            groupColumns.add(actualColumn(actualNames, group, side));
        }

        RecordScanner scanner = dataset.getRecordScanner()
                .orElseThrow(() -> new IllegalArgumentException("Dataset has no RecordScanner: " + side));
        CompareSegment segment = CompareSegment.builder()
                .dataset(dataset)
                .resource(config.getResource())
                .keySpec(keySpec)
                .comparisons(ComparisonSpec.builder().fields(columns).build())
                .filter(filter)
                .schema(schema)
                .side(side)
                .build();

        Map<String, GroupState> groups = new LinkedHashMap<>();
        try (CloseableIterator<CanonicalRecord> iterator = scanner.scan(segment)) {
            while (iterator.hasNext()) {
                CanonicalRecord record = iterator.next();
                Map<String, CanonicalValue> values = record.getValues() == null
                        ? Map.of() : record.getValues();
                List<String> display = new ArrayList<>();
                StringBuilder key = new StringBuilder();
                for (String groupColumn : groupColumns) {
                    Object raw = value(values, groupColumn);
                    display.add(raw == null ? "-" : String.valueOf(raw));
                    key.append(groupKey(raw)).append('\u0001');
                }
                GroupState state = groups.computeIfAbsent(key.toString(),
                        ignored -> new GroupState(String.join(" | ", display),
                                definitions, metricColumns));
                for (int index = 0; index < definitions.size(); index++) {
                    state.accumulators.get(index).accept(value(values, metricColumns.get(index)));
                }
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to scan " + side + ": " + exception.getMessage(), exception);
        }

        Map<String, GroupValues> result = new LinkedHashMap<>();
        groups.forEach((key, state) -> result.put(key, state.values()));
        return result;
    }

    private static String actualColumn(Map<String, String> names, String column, String side) {
        String actual = names.get(column);
        if (actual == null) {
            actual = names.get(column.toLowerCase(Locale.ROOT));
        }
        if (actual == null) {
            throw new IllegalArgumentException("Metric/group column '" + column
                    + "' not found in " + side + " schema");
        }
        return actual;
    }

    private static Object value(Map<String, CanonicalValue> values, String column) {
        CanonicalValue canonical = column == null ? null : values.get(column);
        return canonical == null ? null : canonical.getValue();
    }

    private static String groupKey(Object value) {
        if (value == null) {
            return "\u0000null";
        }
        if (value instanceof Number) {
            try {
                return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private void rejectUnsupportedOptions(CliConfiguration config) {
        if (config.getComparison() != null && config.getComparison().getMappings() != null
                && !config.getComparison().getMappings().isEmpty()) {
            throw new IllegalArgumentException("metrics does not support comparison.mappings;"
                    + " use physical column names or a SQL resource");
        }
        if (isFile(config.getSource()) || isFile(config.getTarget())) {
            boolean hasFilter = config.getComparison() != null
                    && config.getComparison().getFilters() != null
                    && ((config.getComparison().getFilters().getSource() != null
                    && !config.getComparison().getFilters().getSource().isBlank())
                    || (config.getComparison().getFilters().getTarget() != null
                    && !config.getComparison().getFilters().getTarget().isBlank()));
            if (hasFilter) {
                throw new IllegalArgumentException("metrics does not support comparison.filters"
                        + " for file sources; apply the filter in the file or use a SQL resource");
            }
        }
    }

    private List<BigDecimal> compute(DatasetHandle dataset, ConnectorConfig config,
                                     com.consilens.connector.api.model.KeySpec keySpec,
                                     com.consilens.connector.api.model.PredicateSpec filter,
                                     String side, List<MetricDefinition> definitions) {
        SchemaDescriptor schema = dataset.getSchema();
        if (schema == null || schema.getFields() == null || schema.getFields().isEmpty()) {
            throw new IllegalArgumentException("Cannot compute metrics: " + side + " schema is empty");
        }
        List<String> columns = new ArrayList<>();
        Map<String, String> actualNames = new LinkedHashMap<>();
        for (FieldDescriptor field : schema.getFields()) {
            columns.add(field.getName());
            actualNames.put(field.getName(), field.getName());
            actualNames.putIfAbsent(field.getName().toLowerCase(Locale.ROOT), field.getName());
        }
        List<String> metricColumns = new ArrayList<>();
        for (MetricDefinition definition : definitions) {
            if (definition.getColumn() == null) {
                metricColumns.add(null);
                continue;
            }
            String actual = actualNames.get(definition.getColumn());
            if (actual == null) {
                actual = actualNames.get(definition.getColumn().toLowerCase(Locale.ROOT));
            }
            if (actual == null) {
                throw new IllegalArgumentException("Metric column '" + definition.getColumn()
                        + "' not found in " + side + " schema");
            }
            metricColumns.add(actual);
        }

        RecordScanner scanner = dataset.getRecordScanner()
                .orElseThrow(() -> new IllegalArgumentException("Dataset has no RecordScanner: " + side));
        CompareSegment segment = CompareSegment.builder()
                .dataset(dataset)
                .resource(config.getResource())
                .keySpec(keySpec)
                .comparisons(ComparisonSpec.builder().fields(columns).build())
                .filter(filter)
                .schema(schema)
                .side(side)
                .build();

        List<Accumulator> accumulators = new ArrayList<>();
        for (int index = 0; index < definitions.size(); index++) {
            accumulators.add(new Accumulator(definitions.get(index), metricColumns.get(index)));
        }
        try (CloseableIterator<CanonicalRecord> iterator = scanner.scan(segment)) {
            while (iterator.hasNext()) {
                CanonicalRecord record = iterator.next();
                Map<String, CanonicalValue> values = record.getValues() == null
                        ? Map.of() : record.getValues();
                for (int index = 0; index < accumulators.size(); index++) {
                    Accumulator accumulator = accumulators.get(index);
                    CanonicalValue canonical = accumulator.column == null ? null
                            : values.get(accumulator.column);
                    accumulator.accept(canonical == null ? null : canonical.getValue());
                }
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to scan " + side + ": " + exception.getMessage(), exception);
        }

        List<BigDecimal> result = new ArrayList<>();
        for (Accumulator accumulator : accumulators) {
            result.add(accumulator.value());
        }
        return result;
    }

    private MetricResult compare(MetricDefinition definition, BigDecimal sourceValue,
                                 BigDecimal targetValue, BigDecimal maxAbsoluteDelta,
                                 BigDecimal maxRelativePercent) {
        return compare(definition, sourceValue, targetValue, maxAbsoluteDelta,
                maxRelativePercent, null);
    }

    private MetricResult compare(MetricDefinition definition, BigDecimal sourceValue,
                                 BigDecimal targetValue, BigDecimal maxAbsoluteDelta,
                                 BigDecimal maxRelativePercent, String group) {
        if (sourceValue == null || targetValue == null) {
            boolean passed = sourceValue == null && targetValue == null;
            return MetricResult.builder()
                    .metric(definition.label())
                    .group(group)
                    .sourceValue(format(sourceValue))
                    .targetValue(format(targetValue))
                    .delta(null)
                    .relativePercent(null)
                    .passed(passed)
                    .build();
        }
        BigDecimal delta = targetValue.subtract(sourceValue);
        BigDecimal absolute = delta.abs();
        BigDecimal scale = sourceValue.abs().max(targetValue.abs());
        BigDecimal relative = scale.signum() == 0
                ? BigDecimal.ZERO
                : absolute.multiply(BigDecimal.valueOf(100))
                .divide(scale, PERCENT_SCALE, RoundingMode.HALF_UP);
        boolean passed = absolute.compareTo(maxAbsoluteDelta) <= 0
                || relative.compareTo(maxRelativePercent) <= 0;
        return MetricResult.builder()
                .metric(definition.label())
                .group(group)
                .sourceValue(format(sourceValue))
                .targetValue(format(targetValue))
                .delta(format(delta))
                .relativePercent(format(relative))
                .passed(passed)
                .build();
    }

    private static String format(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static boolean isFile(ConnectionConfig config) {
        return config != null && config.getType() != null
                && "file".equalsIgnoreCase(config.getType().trim());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static final class Accumulator {

        private final MetricDefinition definition;
        private final String column;
        private long rows;
        private long nulls;
        private long nonNull;
        private BigDecimal sum;
        private BigDecimal min;
        private BigDecimal max;

        private Accumulator(MetricDefinition definition, String column) {
            this.definition = definition;
            this.column = column;
        }

        private void accept(Object raw) {
            rows++;
            if (definition.getKind() == MetricDefinition.Kind.ROW_COUNT) {
                return;
            }
            if (raw == null) {
                nulls++;
                return;
            }
            nonNull++;
            if (definition.isNumeric()) {
                BigDecimal value = decimal(raw, definition);
                sum = sum == null ? value : sum.add(value);
                min = min == null || value.compareTo(min) < 0 ? value : min;
                max = max == null || value.compareTo(max) > 0 ? value : max;
            }
        }

        private BigDecimal value() {
            switch (definition.getKind()) {
                case ROW_COUNT:
                    return BigDecimal.valueOf(rows);
                case NULL_COUNT:
                    return BigDecimal.valueOf(nulls);
                case NON_NULL_COUNT:
                    return BigDecimal.valueOf(nonNull);
                case SUM:
                    return sum;
                case MIN:
                    return min;
                case MAX:
                    return max;
                case AVG:
                    return sum == null || nonNull == 0 ? null
                            : sum.divide(BigDecimal.valueOf(nonNull), AVG_SCALE, RoundingMode.HALF_UP);
                default:
                    return null;
            }
        }

        private static BigDecimal decimal(Object raw, MetricDefinition definition) {
            try {
                if (raw instanceof BigDecimal) {
                    return (BigDecimal) raw;
                }
                if (raw instanceof Number) {
                    return new BigDecimal(raw.toString());
                }
                if (raw instanceof String) {
                    return new BigDecimal(((String) raw).trim());
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Metric " + definition.label()
                        + " encountered a non-numeric value: " + raw, exception);
            }
            throw new IllegalArgumentException("Metric " + definition.label()
                    + " requires a numeric or string value, got: "
                    + raw.getClass().getSimpleName());
        }
    }

    private static final class GroupState {

        private final String display;
        private final List<Accumulator> accumulators;

        private GroupState(String display, List<MetricDefinition> definitions,
                           List<String> metricColumns) {
            this.display = display;
            this.accumulators = new ArrayList<>();
            for (int index = 0; index < definitions.size(); index++) {
                accumulators.add(new Accumulator(definitions.get(index), metricColumns.get(index)));
            }
        }

        private GroupValues values() {
            List<BigDecimal> values = new ArrayList<>();
            for (Accumulator accumulator : accumulators) {
                values.add(accumulator.value());
            }
            return new GroupValues(display, values);
        }
    }

    private static final class GroupValues {

        private final String display;
        private final List<BigDecimal> values;

        private GroupValues(String display, List<BigDecimal> values) {
            this.display = display;
            this.values = values;
        }
    }
}
