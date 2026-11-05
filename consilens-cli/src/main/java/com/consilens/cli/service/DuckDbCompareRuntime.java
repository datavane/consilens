package com.consilens.cli.service;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.file.FileSchemaInferrer;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Optional DuckDB row-level diff for local file sources.
 *
 * <p>This is an opt-in backend, not the default. It requires:
 * <ul>
 *   <li>both sides {@code type=file} and locally readable by DuckDB;</li>
 *   <li>unique keys on both sides (duplicates are rejected);</li>
 *   <li>no normalization spec and no filters;</li>
 *   <li>DuckDB SQL equality semantics, not the Java normalizer.</li>
 * </ul>
 */
public final class DuckDbCompareRuntime implements CompareRuntime {

    private static final long DEFAULT_MAX_DIFFERENCES = 1_000_000L;

    @Override
    public DiffResult execute(CompareRequest request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.getNormalizationSpec() != null) {
            throw new IllegalArgumentException("duckdb engine does not support normalization;"
                    + " use the default runtime");
        }
        if (request.getSourceFilter() != null || request.getTargetFilter() != null) {
            throw new IllegalArgumentException("duckdb engine does not support filters;"
                    + " use the default runtime");
        }
        List<String> sourceKeys = fields(request.getSourceKeySpec());
        List<String> targetKeys = fields(request.getTargetKeySpec());
        if (sourceKeys.isEmpty() || sourceKeys.size() != targetKeys.size()) {
            throw new IllegalArgumentException("duckdb engine requires the same number of source/target keys");
        }
        List<String> sourceFields = fields(request.getSourceComparisons());
        List<String> targetFields = fields(request.getTargetComparisons());
        if (sourceFields.isEmpty() && targetFields.isEmpty()) {
            // 未声明比较列时与默认引擎一致：比较"除主键、除 exclude 外的所有列"。
            // 若保持为空，changedPredicate 会退化成 FALSE，两个内容不同的文件会被判成完全一致。
            sourceFields = nonKeyColumns(request.getSource(), sourceKeys,
                    excluded(request.getSourceComparisons()));
            targetFields = nonKeyColumns(request.getTarget(), targetKeys,
                    excluded(request.getTargetComparisons()));
            if (sourceFields.isEmpty() && targetFields.isEmpty()) {
                throw new IllegalArgumentException("duckdb engine found no non-key columns to compare;"
                        + " declare comparison fields explicitly");
            }
            if (!new java.util.HashSet<>(sourceFields).equals(new java.util.HashSet<>(targetFields))) {
                // 列集合不同时按下标配对毫无意义（会把 amount 和 remark 拿来比），必须显式声明映射
                throw new IllegalArgumentException("duckdb engine compares inferred columns by name;"
                        + " source=" + sourceFields + ", target=" + targetFields
                        + "; declare comparison fields to map different column names");
            }
            // 同名列但顺序不同：统一按源侧顺序，保证 source/target 按下标配对的是同一列
            targetFields = sourceFields;
        } else if (!sourceFields.isEmpty() && sourceFields.size() == targetFields.size()
                && !sourceFields.equals(targetFields)
                && new java.util.HashSet<>(sourceFields).equals(new java.util.HashSet<>(targetFields))) {
            // 显式声明了同名同集合，只是顺序不同：按列名重排，避免 A 列与 B 列错配
            targetFields = sourceFields;
        }
        if (sourceFields.size() != targetFields.size()) {
            throw new IllegalArgumentException("duckdb engine requires matching comparison field counts:"
                    + " source=" + sourceFields + ", target=" + targetFields);
        }

        String sourceTable = DuckDbFileSql.tableExpression(request.getSource());
        String targetTable = DuckDbFileSql.tableExpression(request.getTarget());
        ensureMatchingTypes(request.getSource(), request.getTarget(),
                sourceKeys, targetKeys, "key");
        ensureMatchingTypes(request.getSource(), request.getTarget(),
                sourceFields, targetFields, "comparison field");
        long maxDifferences = maxDifferences(request);

        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException | LinkageError error) {
            throw new ConnectorException("duckdb engine requires org.duckdb:duckdb_jdbc", error);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            ensureUniqueKeys(connection, sourceTable, sourceKeys, "source");
            ensureUniqueKeys(connection, targetTable, targetKeys, "target");
            Counts counts = counts(connection, sourceTable, targetTable,
                    sourceKeys, targetKeys, sourceFields, targetFields);
            List<DiffRow> differences = differences(connection, sourceTable, targetTable,
                    sourceKeys, targetKeys, sourceFields, targetFields, maxDifferences);
            return result(request, counts, differences);
        } catch (SQLException exception) {
            throw new ConnectorException("DuckDB comparison failed: "
                    + DuckDbErrors.message(exception), exception);
        }
    }

    private static DiffResult result(CompareRequest request, Counts counts,
                                     List<DiffRow> differences) {
        long total = counts.sourceMissing + counts.targetMissing + counts.mismatch;
        long unchanged = Math.max(0L, Math.min(counts.sourceRows, counts.targetRows) - counts.mismatch);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("engine", "duckdb");
        return DiffResult.builder()
                .differences(differences)
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(counts.sourceRows)
                        .targetRowCount(counts.targetRows)
                        .sourceMissingCount(counts.sourceMissing)
                        .targetMissingCount(counts.targetMissing)
                        .mismatchCount(counts.mismatch)
                        .totalDifferences(total)
                        .unchangedCount(unchanged)
                        .processingTimeMs(0L)
                        .build())
                .infoTree(java.util.Optional.empty())
                .completedAt(Instant.now())
                .metadata(metadata)
                .sourceTablePath(tablePath(request.getSource()))
                .targetTablePath(tablePath(request.getTarget()))
                .build();
    }

    private static com.consilens.connector.api.model.TablePath tablePath(
            ConnectorConfig config) {
        if (config == null || config.getResource() == null) {
            return null;
        }
        String value = config.getResource().getName();
        if (value == null || value.isBlank()) {
            value = config.getResource().getPath();
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized.isBlank() ? null
                : com.consilens.connector.api.model.TablePath.of(normalized);
    }

    private static void ensureUniqueKeys(Connection connection, String table, List<String> keys,
                                         String side) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT 1 FROM ")
                .append(table).append(" AS x GROUP BY ");
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(identifier(keys.get(index)));
        }
        sql.append(" HAVING COUNT(*) > 1 LIMIT 1");
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql.toString())) {
            if (result.next()) {
                throw new ConnectorException("duckdb engine requires unique " + side
                        + " keys; duplicate key found for " + keys);
            }
        }
    }

    private static Counts counts(Connection connection, String sourceTable, String targetTable,
                                  List<String> sourceKeys, List<String> targetKeys,
                                  List<String> sourceFields, List<String> targetFields)
            throws SQLException {
        String changed = changedPredicate(sourceFields, targetFields, "s", "t");
        String sql = "WITH s AS (SELECT *, TRUE AS __present FROM " + sourceTable + "),"
                + " t AS (SELECT *, TRUE AS __present FROM " + targetTable + ")"
                + " SELECT"
                + " (SELECT COUNT(*) FROM s) AS source_rows,"
                + " (SELECT COUNT(*) FROM t) AS target_rows,"
                + " COUNT(*) FILTER (WHERE s.__present IS NULL) AS source_missing,"
                + " COUNT(*) FILTER (WHERE t.__present IS NULL) AS target_missing,"
                + " COUNT(*) FILTER (WHERE s.__present IS NOT NULL AND t.__present IS NOT NULL"
                + " AND (" + changed + ")) AS mismatch"
                + " FROM s FULL OUTER JOIN t ON " + join(sourceKeys, targetKeys);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new ConnectorException("DuckDB returned no counts");
            }
            return new Counts(result.getLong("source_rows"), result.getLong("target_rows"),
                    result.getLong("source_missing"), result.getLong("target_missing"),
                    result.getLong("mismatch"));
        }
    }

    private static List<DiffRow> differences(Connection connection,
                                             String sourceTable, String targetTable,
                                             List<String> sourceKeys, List<String> targetKeys,
                                             List<String> sourceFields, List<String> targetFields,
                                             long maxDifferences) throws SQLException {
        String changed = changedPredicate(sourceFields, targetFields, "s", "t");
        StringBuilder sql = new StringBuilder("WITH s AS (SELECT *, TRUE AS __present FROM ")
                .append(sourceTable).append("), t AS (SELECT *, TRUE AS __present FROM ")
                .append(targetTable).append(") SELECT ");
        for (int index = 0; index < sourceKeys.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("s.").append(identifier(sourceKeys.get(index))).append(" AS sk").append(index)
                    .append(", t.").append(identifier(targetKeys.get(index))).append(" AS tk").append(index);
        }
        sql.append(", s.__present AS sp, t.__present AS tp");
        for (int index = 0; index < sourceFields.size(); index++) {
            sql.append(", (s.").append(identifier(sourceFields.get(index)))
                    .append(" IS DISTINCT FROM t.").append(identifier(targetFields.get(index)))
                    .append(") AS ch").append(index);
        }
        for (int index = 0; index < sourceFields.size(); index++) {
            sql.append(", s.").append(identifier(sourceFields.get(index))).append(" AS sf").append(index)
                    .append(", t.").append(identifier(targetFields.get(index))).append(" AS tf").append(index);
        }
        sql.append(" FROM s FULL OUTER JOIN t ON ").append(join(sourceKeys, targetKeys))
                .append(" WHERE s.__present IS NULL OR t.__present IS NULL OR (").append(changed).append(")")
                .append(" ORDER BY ");
        for (int index = 0; index < sourceKeys.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(index * 2 + 1);
        }
        sql.append(" LIMIT ").append(maxDifferences + 1);

        List<DiffRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql.toString())) {
            while (result.next()) {
                if (rows.size() >= maxDifferences) {
                    throw new ConnectorException("Diff result exceeds maxDifferences="
                            + maxDifferences + ". Increase strategy.maxDifferences or narrow the comparison scope.");
                }
                boolean sourcePresent = result.getObject(2 * sourceKeys.size() + 1) != null;
                boolean targetPresent = result.getObject(2 * sourceKeys.size() + 2) != null;
                List<Object> primaryKey = new ArrayList<>();
                for (int index = 0; index < sourceKeys.size(); index++) {
                    Object sourceKey = result.getObject(index * 2 + 1);
                    Object targetKey = result.getObject(index * 2 + 2);
                    primaryKey.add(sourcePresent ? sourceKey : targetKey);
                }
                int changedOffset = 2 * sourceKeys.size() + 3;
                int sourceOffset = changedOffset + sourceFields.size();
                List<Object> sourceValues = new ArrayList<>();
                List<Object> targetValues = new ArrayList<>();
                for (int index = 0; index < sourceKeys.size(); index++) {
                    sourceValues.add(result.getObject(index * 2 + 1));
                    targetValues.add(result.getObject(index * 2 + 2));
                }
                for (int index = 0; index < sourceFields.size(); index++) {
                    sourceValues.add(result.getObject(sourceOffset + index * 2));
                    targetValues.add(result.getObject(sourceOffset + index * 2 + 1));
                }
                List<String> sourceColumns = new ArrayList<>(sourceKeys);
                sourceColumns.addAll(sourceFields);
                List<String> targetColumns = new ArrayList<>(targetKeys);
                targetColumns.addAll(targetFields);

                if (!sourcePresent) {
                    rows.add(DiffRow.added(primaryKey, targetValues, targetColumns));
                } else if (!targetPresent) {
                    rows.add(DiffRow.removed(primaryKey, sourceValues, sourceColumns));
                } else {
                    List<String> changedSource = new ArrayList<>();
                    List<String> changedTarget = new ArrayList<>();
                    for (int index = 0; index < sourceFields.size(); index++) {
                        if (Boolean.TRUE.equals(result.getObject(changedOffset + index))) {
                            changedSource.add(sourceFields.get(index));
                            changedTarget.add(targetFields.get(index));
                        }
                    }
                    if (!changedSource.isEmpty()) {
                        rows.add(DiffRow.modified(primaryKey, sourceValues, targetValues,
                                sourceColumns, targetColumns, changedSource, changedTarget));
                    }
                }
            }
        }
        return rows;
    }

    private static String join(List<String> sourceKeys, List<String> targetKeys) {
        StringBuilder join = new StringBuilder();
        for (int index = 0; index < sourceKeys.size(); index++) {
            if (index > 0) {
                join.append(" AND ");
            }
            join.append("s.").append(identifier(sourceKeys.get(index)))
                    .append(" IS NOT DISTINCT FROM t.")
                    .append(identifier(targetKeys.get(index)));
        }
        return join.toString();
    }

    private static String changedPredicate(List<String> sourceFields, List<String> targetFields,
                                           String sourceAlias, String targetAlias) {
        if (sourceFields.isEmpty()) {
            return "FALSE";
        }
        StringBuilder predicate = new StringBuilder();
        for (int index = 0; index < sourceFields.size(); index++) {
            if (index > 0) {
                predicate.append(" OR ");
            }
            predicate.append(sourceAlias).append('.').append(identifier(sourceFields.get(index)))
                    .append(" IS DISTINCT FROM ")
                    .append(targetAlias).append('.').append(identifier(targetFields.get(index)));
        }
        return predicate.toString();
    }

    /**
     * DuckDB implicitly casts VARCHAR to numbers in joins, so {@code "001"} and
     * {@code 1} compare equal even though the Java scanner treats them as
     * different keys. Refuse mismatched key types instead of reporting a false
     * match; users can align the key types or use the default engine.
     */
    private static void ensureMatchingTypes(ConnectorConfig source, ConnectorConfig target,
                                            List<String> sourceFields, List<String> targetFields,
                                            String label) {
        Map<String, String> sourceTypes = canonicalTypes(source);
        Map<String, String> targetTypes = canonicalTypes(target);
        for (int index = 0; index < sourceFields.size(); index++) {
            String sourceField = sourceFields.get(index);
            String targetField = targetFields.get(index);
            String sourceType = sourceTypes.get(sourceField);
            String targetType = targetTypes.get(targetField);
            if (sourceType != null && targetType != null
                    && !isUnknown(sourceType) && !isUnknown(targetType)
                    && !sourceType.equalsIgnoreCase(targetType)) {
                throw new IllegalArgumentException("duckdb engine requires matching " + label
                        + " types; source." + sourceField + "=" + sourceType
                        + ", target." + targetField + "=" + targetType
                        + "; align the types or use the java engine");
            }
        }
    }

    private static boolean isUnknown(String type) {
        return "UNKNOWN".equalsIgnoreCase(type);
    }

    private static Map<String, String> canonicalTypes(ConnectorConfig config) {
        SchemaDescriptor schema = FileSchemaInferrer.infer(config.getResource(), null);
        Map<String, String> types = new HashMap<>();
        if (schema.getFields() != null) {
            for (FieldDescriptor field : schema.getFields()) {
                if (field.getName() != null && field.getCanonicalType() != null) {
                    types.put(field.getName(), field.getCanonicalType());
                }
            }
        }
        return types;
    }

    private static List<String> excluded(ComparisonSpec comparisons) {
        if (comparisons == null || comparisons.getExclude() == null) {
            return List.of();
        }
        return comparisons.getExclude();
    }

    /** 推断出的列里去掉主键列与 exclude 列，作为"未显式声明比较列"时的比较范围。 */
    private static List<String> nonKeyColumns(ConnectorConfig config, List<String> keys) {
        return nonKeyColumns(config, keys, List.of());
    }

    private static List<String> nonKeyColumns(ConnectorConfig config, List<String> keys,
                                              List<String> exclude) {
        if (config == null || config.getResource() == null) {
            return List.of();
        }
        SchemaDescriptor schema = FileSchemaInferrer.infer(config.getResource(), null);
        if (schema == null || schema.getFields() == null) {
            return List.of();
        }
        java.util.Set<String> keySet = new java.util.LinkedHashSet<>(keys);
        keySet.addAll(exclude);
        List<String> columns = new ArrayList<>();
        for (FieldDescriptor field : schema.getFields()) {
            String name = field == null ? null : field.getName();
            if (name != null && !keySet.contains(name)) {
                columns.add(name);
            }
        }
        return columns;
    }

    private static List<String> fields(KeySpec keySpec) {
        if (keySpec == null || keySpec.getFields() == null || keySpec.getFields().isEmpty()) {
            return List.of();
        }
        for (String field : keySpec.getFields()) {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("duckdb engine key names must not be blank");
            }
        }
        return keySpec.getFields();
    }

    private static List<String> fields(ComparisonSpec comparisons) {
        if (comparisons == null || comparisons.getFields() == null) {
            return List.of();
        }
        for (String field : comparisons.getFields()) {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("duckdb engine field names must not be blank");
            }
        }
        return comparisons.getFields();
    }

    private static long maxDifferences(CompareRequest request) {
        if (request.getExecutionOptions() != null
                && request.getExecutionOptions().getMaxDifferences() != null
                && request.getExecutionOptions().getMaxDifferences() > 0) {
            return request.getExecutionOptions().getMaxDifferences();
        }
        return DEFAULT_MAX_DIFFERENCES;
    }

    static String identifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static final class Counts {
        private final long sourceRows;
        private final long targetRows;
        private final long sourceMissing;
        private final long targetMissing;
        private final long mismatch;

        private Counts(long sourceRows, long targetRows, long sourceMissing,
                       long targetMissing, long mismatch) {
            this.sourceRows = sourceRows;
            this.targetRows = targetRows;
            this.sourceMissing = sourceMissing;
            this.targetMissing = targetMissing;
            this.mismatch = mismatch;
        }
    }
}
