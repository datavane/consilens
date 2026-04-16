package com.consilens.core.compare.jdbc.support;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.planner.KeyRangeSplit;
import com.consilens.connector.api.planner.OffsetLimitSplit;
import com.consilens.connector.api.planner.SegmentSplit;
import com.consilens.core.segment.TableSegment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class JdbcCompareSegmentAdapter {

    private JdbcCompareSegmentAdapter() {
    }

    public static LegacyTableSegment toTableSegment(CompareSegment segment) {
        if (segment == null) {
            throw new ConnectorException("CompareSegment cannot be null");
        }
        JdbcLegacyBridgeFactory.JdbcLegacySegment bridge = JdbcLegacyBridgeFactory.openSegment(segment);
        try {
            ResourceLocator resource = segment.getResource() != null ? segment.getResource() : segment.getDataset().getResource();
            TablePath tablePath = resolveTablePath(resource);
            SchemaDescriptor schema = bridge.getSchema();
            List<String> keyColumns = requireKeyColumns(segment.getKeySpec(), tablePath);
            List<String> extraColumns = resolveComparisonColumns(segment.getComparisons(), schema, keyColumns);

            TableSegment.TableSegmentBuilder builder = TableSegment.builder()
                    .database(bridge.getDatabaseAdapter())
                    .tablePath(tablePath)
                    .keyColumns(keyColumns)
                    .extraColumns(extraColumns)
                    .whereClause(resolveWhereClause(segment.getFilter()))
                    .caseSensitive(false)
                    .schema(Optional.ofNullable(JdbcSchemaAdapter.toLegacySchema(schema, tablePath)));

            applySplit(builder, segment.getSplit());
            return new LegacyTableSegment(builder.build(), bridge);
        } catch (RuntimeException | Error e) {
            bridge.close();
            throw e;
        }
    }

    private static void applySplit(TableSegment.TableSegmentBuilder builder, SegmentSplit split) {
        if (split == null) {
            return;
        }
        if (split instanceof KeyRangeSplit) {
            KeyRangeSplit keyRangeSplit = (KeyRangeSplit) split;
            builder.minKey(Optional.ofNullable(keyRangeSplit.getStartKey()));
            builder.maxKey(Optional.ofNullable(keyRangeSplit.getEndKey()));
            return;
        }
        if (split instanceof OffsetLimitSplit) {
            OffsetLimitSplit offsetLimitSplit = (OffsetLimitSplit) split;
            builder.limitOffset(Optional.of(new TableSegment.LimitOffset(
                    offsetLimitSplit.getLimit(),
                    offsetLimitSplit.getOffset())));
            return;
        }
        throw new ConnectorException("Unsupported JDBC split type: " + split.getClass().getSimpleName());
    }

    private static Optional<String> resolveWhereClause(PredicateSpec filter) {
        if (filter == null || filter.getExpression() == null || filter.getExpression().trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(filter.getExpression().trim());
    }

    private static List<String> requireKeyColumns(KeySpec keySpec, TablePath tablePath) {
        if (keySpec == null || keySpec.getFields() == null || keySpec.getFields().isEmpty()) {
            throw new ConnectorException("KeySpec is required for resource " + tablePath.getFullPath());
        }
        return List.copyOf(keySpec.getFields());
    }

    private static List<String> resolveComparisonColumns(ComparisonSpec comparisons,
                                                         SchemaDescriptor schema,
                                                         List<String> keyColumns) {
        if (comparisons != null && comparisons.getFields() != null && !comparisons.getFields().isEmpty()) {
            Set<String> result = new LinkedHashSet<>(comparisons.getFields());
            Set<String> overlap = new LinkedHashSet<>(result);
            overlap.retainAll(keyColumns);
            if (!overlap.isEmpty()) {
                throw new ConnectorException("Comparison columns must not include key columns: " + overlap);
            }
            return List.copyOf(result);
        }
        if (schema == null || schema.getFields() == null) {
            return List.of();
        }
        Set<String> keySet = new LinkedHashSet<>(keyColumns);
        return schema.getFields().stream()
                .map(FieldDescriptor::getName)
                .filter(name -> name != null && !keySet.contains(name))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static TablePath resolveTablePath(ResourceLocator resource) {
        if (resource == null) {
            throw new ConnectorException("ResourceLocator cannot be null");
        }
        if (resource.getName() != null && !resource.getName().trim().isEmpty()) {
            return TablePath.fromString(resource.getName().trim());
        }
        if (resource.getPath() != null && !resource.getPath().trim().isEmpty()) {
            return TablePath.fromString(resource.getPath().trim());
        }
        throw new ConnectorException("JDBC resource requires resource.name or resource.path");
    }

    public static final class LegacyTableSegment implements AutoCloseable {

        private final TableSegment tableSegment;
        private final JdbcLegacyBridgeFactory.JdbcLegacySegment bridge;

        private LegacyTableSegment(TableSegment tableSegment, JdbcLegacyBridgeFactory.JdbcLegacySegment bridge) {
            this.tableSegment = tableSegment;
            this.bridge = bridge;
        }

        public TableSegment getTableSegment() {
            return tableSegment;
        }

        @Override
        public void close() {
            bridge.close();
        }
    }
}
