package com.consilens.sink.table;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.SegmentResult;
import com.consilens.sink.api.ColumnValueInterpolator;
import com.consilens.sink.api.Sink;
import com.consilens.sink.api.model.ColumnMapping;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes diff records to a database table.
 *
 * <p>Three output modes depending on {@code columns} and {@code mergeDefaults}:
 * <ul>
 *   <li><b>Default wide-table mode</b> ({@code columns} empty): fixed columns nl_dq_execution_id + nl_dq_diff_type + dynamic business columns.</li>
 *   <li><b>Full custom mode</b> ({@code columns} non-empty, {@code mergeDefaults=false}): creates table and inserts by ColumnMapping rules.</li>
 *   <li><b>Merge mode</b> ({@code columns} non-empty, {@code mergeDefaults=true}): default wide-table schema,
 *       but overrides values for columns whose name matches a configured ColumnMapping.</li>
 * </ul>
 */
@Slf4j
public class TableDiffRecordSink implements Sink {

    private HikariDataSource dataSource;
    private TableSinkConfig sinkConfig;
    private String tableName;
    private List<String> sourceColumns;
    private List<String> targetColumns;
    private String insertSql;
    private int batchSize;

    @Override
    public void open(SinkConfig config, DiffContext context) throws Exception {
        sinkConfig = parseConfig(config.getProperties());
        dataSource = createDataSource(sinkConfig);
        tableName = sinkConfig.resolveTableName();
        batchSize = sinkConfig.getBatchSize();

        sourceColumns = context.getSourceColumnNames() != null
                ? context.getSourceColumnNames() : new ArrayList<>();
        targetColumns = context.getTargetColumnNames() != null
                ? context.getTargetColumnNames() : new ArrayList<>();

        if (sinkConfig.isCreateTable() && (sinkConfig.hasCustomColumns() || !sourceColumns.isEmpty())) {
            createOutputTable();
        }
        insertSql = buildInsertSql();
        log.info("TableDiffRecordSink opened, table={}, mode={}", tableName,
                sinkConfig.isMergeMode() ? "merge" : sinkConfig.hasCustomColumns() ? "custom" : "default");
    }

    @Override
    public void onDiffRecords(List<DiffRow> rows, DiffContext context) throws SQLException {
        if (dataSource == null || rows == null || rows.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            conn.setAutoCommit(false);
            int count = 0;
            for (DiffRow row : rows) {
                bindInsertParams(ps, row, context);
                ps.addBatch();
                if (++count % batchSize == 0) {
                    ps.executeBatch();
                    conn.commit();
                }
            }
            ps.executeBatch();
            conn.commit();
            log.debug("TableDiffRecordSink inserted {} rows", rows.size());
        }
    }

    @Override
    public void onSegmentComplete(SegmentResult segmentResult) {
        log.debug("TableDiffRecordSink segment={} done", segmentResult.getSegmentIndex());
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    // ---- DDL ----

    private void createOutputTable() throws SQLException {
        if (sinkConfig.isDropIfExists()) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DROP TABLE IF EXISTS " + tableName)) {
                ps.execute();
                log.debug("Dropped existing table: {}", tableName);
            }
        }
        String ddl = buildCreateTableDdl();
        log.info("Creating diff table with DDL: {}", ddl);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(ddl)) {
            ps.execute();
        }
        log.info("TableDiffRecordSink created table: {}", tableName);
    }

    private String buildCreateTableDdl() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (");
        if (sinkConfig.hasCustomColumns() && !sinkConfig.isMergeMode()) {
            // Full custom mode: DDL from ColumnMapping
            List<ColumnMapping> fields = sinkConfig.getColumns();
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) sql.append(", ");
                ColumnMapping f = fields.get(i);
                String colType = (f.getColumnType() != null && !f.getColumnType().isBlank())
                        ? f.getColumnType() : "TEXT";
                sql.append(sanitize(f.getName())).append(" ").append(colType);
            }
        } else {
            // Default / merge mode: standard wide-table schema
            sql.append("nl_dq_execution_id VARCHAR(64), ");
            sql.append("nl_dq_diff_type VARCHAR(20) NOT NULL, ");
            sql.append("nl_dq_diff_columns1 JSON, ");
            sql.append("nl_dq_diff_columns2 JSON");
            for (String col : sourceColumns) {
                sql.append(", ").append(sanitize(col)).append("_1 TEXT");
            }
            for (String col : targetColumns) {
                sql.append(", ").append(sanitize(col)).append("_2 TEXT");
            }
        }
        sql.append(")");
        return sql.toString();
    }

    // ---- INSERT ----

    private String buildInsertSql() {
        StringBuilder cols = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        StringBuilder placeholders = new StringBuilder("VALUES (");
        if (sinkConfig.hasCustomColumns() && !sinkConfig.isMergeMode()) {
            // Full custom mode
            List<ColumnMapping> fields = sinkConfig.getColumns();
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    cols.append(", ");
                    placeholders.append(",");
                }
                cols.append(sanitize(fields.get(i).getName()));
                placeholders.append("?");
            }
        } else {
            // Default / merge mode: wide-table columns
            cols.append("nl_dq_execution_id, nl_dq_diff_type, nl_dq_diff_columns1, nl_dq_diff_columns2");
            placeholders.append("?,?,?,?");
            for (String col : sourceColumns) {
                cols.append(", ").append(sanitize(col)).append("_1");
                placeholders.append(",?");
            }
            for (String col : targetColumns) {
                cols.append(", ").append(sanitize(col)).append("_2");
                placeholders.append(",?");
            }
        }
        cols.append(") ");
        placeholders.append(")");
        return cols.toString() + placeholders.toString();
    }

    private void bindInsertParams(PreparedStatement ps, DiffRow row, DiffContext context) throws SQLException {
        if (sinkConfig.hasCustomColumns() && !sinkConfig.isMergeMode()) {
            // Full custom mode
            List<ColumnMapping> fields = sinkConfig.getColumns();
            for (int i = 0; i < fields.size(); i++) {
                ps.setString(i + 1, ColumnValueInterpolator.resolveField(fields.get(i), context, row));
            }
        } else {
            // Default / merge mode
            Map<String, ColumnMapping> overrideMap = sinkConfig.isMergeMode() ? buildOverrideMap() : null;
            int idx = 1;
            ps.setString(idx++, resolve("nl_dq_execution_id", context.getTaskId(), overrideMap, context, row));
            ps.setString(idx++, resolve("nl_dq_diff_type", row.getOperation().getCode(), overrideMap, context, row));
            ps.setString(idx++, resolve("nl_dq_diff_columns1", toJsonArray(row.getChangedColumns1()), overrideMap, context, row));
            ps.setString(idx++, resolve("nl_dq_diff_columns2", toJsonArray(row.getChangedColumns2()), overrideMap, context, row));
            for (String col : sourceColumns) {
                ps.setString(idx++, resolve(sanitize(col) + "_1", toStr(row.getSourceValue(col)), overrideMap, context, row));
            }
            for (String col : targetColumns) {
                ps.setString(idx++, resolve(sanitize(col) + "_2", toStr(row.getTargetValue(col)), overrideMap, context, row));
            }
        }
    }

    // ---- helpers ----

    /** Build a name→mapping lookup from the configured columns list. */
    private Map<String, ColumnMapping> buildOverrideMap() {
        Map<String, ColumnMapping> map = new LinkedHashMap<>();
        if (sinkConfig.getColumns() != null) {
            for (ColumnMapping cm : sinkConfig.getColumns()) {
                map.put(cm.getName(), cm);
            }
        }
        return map;
    }

    /**
     * Returns the override-resolved value if an override exists for {@code colName},
     * falling back to {@code defaultValue} when overrideMap is null or no match.
     */
    private String resolve(String colName, String defaultValue,
                           Map<String, ColumnMapping> overrideMap, DiffContext ctx, DiffRow row) {
        if (overrideMap != null && overrideMap.containsKey(colName)) {
            String resolved = ColumnValueInterpolator.resolveField(overrideMap.get(colName), ctx, row);
            return resolved != null ? resolved : defaultValue;
        }
        return defaultValue;
    }

    private String sanitize(String col) {
        return col.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String toStr(Object val) {
        return val == null ? null : val.toString();
    }

    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(list.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private TableSinkConfig parseConfig(String properties) throws Exception {
        if (properties == null || properties.isBlank()) {
            throw new IllegalArgumentException("TableDiffRecordSink requires properties configuration (url, username, password)");
        }
        return new ObjectMapper().readValue(properties, TableSinkConfig.class);
    }

    private HikariDataSource createDataSource(TableSinkConfig cfg) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(cfg.getUrl());
        hikari.setUsername(cfg.getUsername());
        hikari.setPassword(cfg.getPassword());
        hikari.setDriverClassName(cfg.resolveDriver());
        hikari.setMaximumPoolSize(cfg.getMaxPoolSize());
        return new HikariDataSource(hikari);
    }
}
