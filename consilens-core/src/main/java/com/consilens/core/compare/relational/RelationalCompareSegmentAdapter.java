package com.consilens.core.compare.relational;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.dataset.RelationalDatasetSupport;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.UpdateWindow;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.planner.KeyRangeSplit;
import com.consilens.connector.api.planner.OffsetLimitSplit;
import com.consilens.connector.api.planner.SegmentSplit;
import com.consilens.core.compare.CompareExecutionSettings;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.database.adpter.DefaultDatabaseAdapter;
import com.consilens.core.database.connection.ConnectionPool;
import com.consilens.core.segment.TableSegment;
import lombok.Getter;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class RelationalCompareSegmentAdapter {

    private RelationalCompareSegmentAdapter() {
    }

    public static PreparedTableSegment toTableSegment(CompareSegment segment, CompareExecutionSettings executionSettings) {
        if (segment == null || segment.getDataset() == null) {
            throw new ConnectorException("CompareSegment dataset is required");
        }

        RelationalDatasetSupport support = segment.getDataset()
                .getSupport(RelationalDatasetSupport.class)
                .orElseThrow(() -> new ConnectorException(
                        "Dataset " + segment.getDataset().getClass().getSimpleName()
                                + " does not provide relational execution support"));

        ResourceLocator resource = segment.getResource();
        boolean sqlResource = isSqlResource(resource);
        TablePath tablePath = sqlResource ? sqlResourceTablePath(resource) : support.getTablePath();
        SchemaDescriptor schema = segment.getSchema() != null ? segment.getSchema() : segment.getDataset().getSchema();
        List<String> keyColumns = requireKeyColumns(segment.getKeySpec(), tablePath);
        List<String> extraColumns = resolveComparisonColumns(segment.getComparisons(), schema, keyColumns);
        DatabaseAdapter databaseAdapter = createDatabaseAdapter(
                support,
                executionSettings,
                new SupportBackedConnectionPool(support));

        TableSegment.TableSegmentBuilder builder = TableSegment.builder()
                .database(databaseAdapter)
                .tablePath(tablePath)
                .relationSource(sqlResource ? sqlRelationSource(resource) : null)
                .keyColumns(keyColumns)
                .extraColumns(extraColumns)
                .whereClause(resolveWhereClause(segment.getFilter(), segment.getUpdateWindow(), support))
                .caseSensitive(false)
                .schema(Optional.ofNullable(RelationalSchemaAdapter.toLegacySchema(schema, tablePath)));

        applySplit(builder, segment.getSplit());
        return new PreparedTableSegment(builder.build());
    }

    private static DatabaseAdapter createDatabaseAdapter(RelationalDatasetSupport support,
                                                         CompareExecutionSettings executionSettings,
                                                         SupportBackedConnectionPool connectionPool) {
        return new DefaultDatabaseAdapter(
                support.getName(),
                connectionPool,
                support.getDialect(),
                support.getJdbcUrl(),
                executionSettings.getChecksumAlgorithm());
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
        throw new ConnectorException("Unsupported relational split type: " + split.getClass().getSimpleName());
    }

    private static Optional<String> resolveWhereClause(PredicateSpec filter,
                                                       UpdateWindow updateWindow,
                                                       RelationalDatasetSupport support) {
        String whereClause = buildWhereClause(filter, updateWindow, support);
        return whereClause == null || whereClause.isBlank() ? Optional.empty() : Optional.of(whereClause);
    }

    private static String buildWhereClause(PredicateSpec filter,
                                           UpdateWindow updateWindow,
                                           RelationalDatasetSupport support) {
        List<String> predicates = new ArrayList<>();
        if (filter != null && filter.getExpression() != null && !filter.getExpression().trim().isEmpty()) {
            predicates.add("(" + filter.getExpression().trim() + ")");
        }
        if (updateWindow != null && updateWindow.getColumn() != null && !updateWindow.getColumn().isBlank()) {
            List<String> window = new ArrayList<>();
            String column = updateWindow.getColumn();
            if (updateWindow.getStart() != null) {
                window.add(column + " >= " + support.getDialect().getSqlQueryGenerator()
                        .formatValue(Timestamp.from(updateWindow.getStart())));
            }
            if (updateWindow.getEnd() != null) {
                window.add(column + " < " + support.getDialect().getSqlQueryGenerator()
                        .formatValue(Timestamp.from(updateWindow.getEnd())));
            }
            if (!window.isEmpty()) {
                predicates.add("(" + String.join(" AND ", window) + ")");
            }
        }
        return predicates.isEmpty() ? null : String.join(" AND ", predicates);
    }

    private static String stripTrailingSemicolon(String sql) {
        String normalized = sql.trim();
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private static boolean isSqlResource(ResourceLocator resource) {
        return resource != null
                && resource.getType() != null
                && "sql".equalsIgnoreCase(resource.getType())
                && resource.getPath() != null
                && !resource.getPath().isBlank();
    }

    private static TablePath sqlResourceTablePath(ResourceLocator resource) {
        String name = resource.getName();
        if (name == null || name.isBlank()) {
            name = "consilens_sql_resource";
        }
        return TablePath.of(name.trim());
    }

    private static TableSegment.RelationSource sqlRelationSource(ResourceLocator resource) {
        return new TableSegment.RelationSource(
                stripTrailingSemicolon(resource.getPath()),
                sqlResourceTablePath(resource).getFullPath());
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
        Set<String> excluded = excludedColumns(comparisons);
        if (comparisons != null && comparisons.getFields() != null && !comparisons.getFields().isEmpty()) {
            Set<String> result = new LinkedHashSet<>(comparisons.getFields());
            result.removeAll(excluded);
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
                .filter(name -> name != null && !keySet.contains(name) && !excluded.contains(name))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static Set<String> excludedColumns(ComparisonSpec comparisons) {
        if (comparisons == null || comparisons.getExclude() == null || comparisons.getExclude().isEmpty()) {
            return Set.of();
        }
        return comparisons.getExclude().stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Getter
    public static final class PreparedTableSegment implements AutoCloseable {

        private final TableSegment tableSegment;

        private PreparedTableSegment(TableSegment tableSegment) {
            this.tableSegment = tableSegment;
        }

        @Override
        public void close() {
            if (tableSegment.getDatabase() != null) {
                tableSegment.getDatabase().close();
            }
        }
    }

    private static final class SupportBackedConnectionPool implements ConnectionPool {

        private final RelationalDatasetSupport support;
        private final PoolConfiguration configuration;
        private volatile boolean closed;

        private SupportBackedConnectionPool(RelationalDatasetSupport support) {
            this.support = support;
            PoolConfiguration config = new PoolConfiguration();
            config.setJdbcUrl(support.getJdbcUrl());
            config.setConnectorType(support.getConnectorType());
            String username = support.getUsername();
            config.setUsername(username != null && !username.trim().isEmpty() ? username : support.getName());
            this.configuration = config;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (closed) {
                throw new SQLException("Connection pool is closed for " + support.getName());
            }
            return support.getConnection();
        }

        @Override
        public void releaseConnection(Connection connection) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public int getActiveConnections() {
            return 0;
        }

        @Override
        public int getIdleConnections() {
            return 0;
        }

        @Override
        public int getMaxPoolSize() {
            return 1;
        }

        @Override
        public int getMinIdleConnections() {
            return 0;
        }

        @Override
        public PoolStatistics getStatistics() {
            return new PoolStatistics(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0);
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public String getConnectorType() {
            return support.getConnectorType();
        }

        @Override
        public PoolConfiguration getConfiguration() {
            return configuration.copy();
        }

        @Override
        public boolean isHealthy() {
            return !closed;
        }

        @Override
        public Map<String, Object> getMetrics() {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("mode", "support-backed");
            metrics.put("name", support.getName());
            metrics.put("databaseType", support.getConnectorType());
            return metrics;
        }
    }
}
