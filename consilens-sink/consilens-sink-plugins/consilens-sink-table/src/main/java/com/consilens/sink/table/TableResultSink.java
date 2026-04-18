package com.consilens.sink.table;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.lifecycle.DiffContext;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Writes the final diff result to a database table.
 *
 * <p>Supports two output modes:
 * <ul>
 *   <li><b>Custom field mode</b> (fields non-empty): creates table and inserts by FieldMapping rules.</li>
 *   <li><b>Default fixed mode</b> (fields empty): fixed columns nl_dq_execution_id / src_table / tgt_table, etc.</li>
 * </ul>
 */
@Slf4j
public class TableResultSink implements Sink {

    private HikariDataSource dataSource;
    private TableSinkConfig sinkConfig;
    private String tableName;

    @Override
    public void open(SinkConfig config, DiffContext context) throws Exception {
        sinkConfig = parseConfig(config.getProperties());
        dataSource = createDataSource(sinkConfig);
        tableName = sinkConfig.resolveTableName();

        if (sinkConfig.isCreateTable()) {
            createTableIfNotExists();
        }
        log.info("TableResultSink opened, table={}, customFields={}", tableName, sinkConfig.hasCustomColumns());
    }

    private void createTableIfNotExists() throws SQLException {
        if (sinkConfig.isDropIfExists()) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DROP TABLE IF EXISTS " + tableName)) {
                ps.execute();
            }
        }
        String ddl = buildCreateTableDdl();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(ddl)) {
            ps.execute();
        }
    }

    private String buildCreateTableDdl() {
        if (sinkConfig.hasCustomColumns()) {
            StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (");
            List<ColumnMapping> fields = sinkConfig.getColumns();
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) sql.append(", ");
                ColumnMapping f = fields.get(i);
                String colType = (f.getColumnType() != null && !f.getColumnType().isBlank())
                        ? f.getColumnType() : "TEXT";
                sql.append(sanitize(f.getName())).append(" ").append(colType);
            }
            sql.append(")");
            return sql.toString();
        }
        return "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "nl_dq_execution_id VARCHAR(64), "
                + "src_table VARCHAR(256), "
                + "tgt_table VARCHAR(256), "
                + "diff_count BIGINT, "
                + "src_missing BIGINT, "
                + "tgt_missing BIGINT, "
                + "mismatch_count BIGINT, "
                + "run_status VARCHAR(32), "
                + "completed_at TIMESTAMP"
                + ")";
    }

    @Override
    public void onResult(DiffResult result, DiffContext context) throws SQLException {
        if (dataSource == null) {
            return;
        }
        if (sinkConfig.hasCustomColumns()) {
            insertWithCustomFields(result, context);
        } else {
            insertDefault(result, context);
        }
        log.info("TableResultSink wrote result for task {}", context.getTaskId());
    }

    private void insertWithCustomFields(DiffResult result, DiffContext context) throws SQLException {
        List<ColumnMapping> fields = sinkConfig.getColumns();
        StringBuilder cols = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        StringBuilder placeholders = new StringBuilder("VALUES (");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                cols.append(", ");
                placeholders.append(",");
            }
            cols.append(sanitize(fields.get(i).getName()));
            placeholders.append("?");
        }
        cols.append(") ");
        placeholders.append(")");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(cols.toString() + placeholders.toString())) {
            for (int i = 0; i < fields.size(); i++) {
                ps.setString(i + 1, ColumnValueInterpolator.resolveField(fields.get(i), context, result));
            }
            ps.executeUpdate();
        }
    }

    private void insertDefault(DiffResult result, DiffContext context) throws SQLException {
        String sql = "INSERT INTO " + tableName
                + " (nl_dq_execution_id, src_table, tgt_table, diff_count, src_missing, tgt_missing, mismatch_count, run_status, completed_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)";
        DiffResult.DiffStatistics stats = result.getStatistics();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, context.getTaskId());
            ps.setString(2, result.getSourceTablePath() != null ? result.getSourceTablePath().getTableName() : "");
            ps.setString(3, result.getTargetTablePath() != null ? result.getTargetTablePath().getTableName() : "");
            ps.setLong(4, stats != null ? stats.getTotalDifferences() : 0);
            ps.setLong(5, stats != null ? stats.getSourceMissingCount() : 0);
            ps.setLong(6, stats != null ? stats.getTargetMissingCount() : 0);
            ps.setLong(7, stats != null ? stats.getMismatchCount() : 0);
            ps.setString(8, result.hasDifferences() ? "DIFF" : "EQUAL");
            ps.setTimestamp(9, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    @Override
    public void onError(DiffContext context, Throwable error) {
        if (dataSource == null) {
            return;
        }
        try {
            if (sinkConfig.hasCustomColumns()) {
                insertCustomErrorFields(context, error);
            } else {
                insertDefaultError(context);
            }
        } catch (SQLException e) {
            log.error("Failed to write error result for task {}", context.getTaskId(), e);
        }
    }

    private void insertDefaultError(DiffContext context) throws SQLException {
        String sql = "INSERT INTO " + tableName
                + " (nl_dq_execution_id, run_status, completed_at) VALUES (?,?,?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, context.getTaskId());
            ps.setString(2, "ERROR");
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private void insertCustomErrorFields(DiffContext context, Throwable error) throws SQLException {
        List<ColumnMapping> fields = sinkConfig.getColumns();
        StringBuilder cols = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        StringBuilder placeholders = new StringBuilder("VALUES (");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                cols.append(", ");
                placeholders.append(",");
            }
            cols.append(sanitize(fields.get(i).getName()));
            placeholders.append("?");
        }
        cols.append(") ");
        placeholders.append(")");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(cols.toString() + placeholders.toString())) {
            for (int i = 0; i < fields.size(); i++) {
                ps.setString(i + 1, resolveErrorField(fields.get(i), context, error));
            }
            ps.executeUpdate();
        }
    }

    private String resolveErrorField(ColumnMapping field, DiffContext context, Throwable error) {
        String resolved = ColumnValueInterpolator.resolveField(field, context, (DiffResult) null);
        if (resolved != null && !resolved.isEmpty()) {
            return resolved;
        }
        String normalizedName = field.getName() != null ? sanitize(field.getName()).toLowerCase() : "";
        switch (normalizedName) {
            case "run_status":
            case "status":
            case "execution_status":
                return "ERROR";
            case "error_message":
            case "message":
            case "error":
                return error != null ? error.getMessage() : null;
            case "task_id":
            case "execution_id":
            case "nl_dq_execution_id":
                return context.getTaskId();
            case "completed_at":
            case "timestamp":
                return Instant.now().toString();
            default:
                return null;
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private String sanitize(String col) {
        return col.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private TableSinkConfig parseConfig(String properties) throws Exception {
        if (properties == null || properties.isBlank()) {
            throw new IllegalArgumentException("TableResultSink requires properties configuration (url, username, password)");
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
