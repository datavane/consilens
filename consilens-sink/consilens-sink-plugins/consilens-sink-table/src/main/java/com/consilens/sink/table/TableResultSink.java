package com.consilens.sink.table;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.common.type.Types;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialects;
import com.consilens.connector.api.write.OutputColumnSpec;
import com.consilens.connector.api.write.PreparedWriteRow;
import com.consilens.connector.api.write.TableWriteCompileRequest;
import com.consilens.connector.api.write.TableWriteCompiler;
import com.consilens.connector.api.write.TableWritePlan;
import com.consilens.connector.api.write.TypedOutputRow;
import com.consilens.connector.api.write.TypedOutputValue;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.lifecycle.DiffContext;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class TableResultSink implements Sink {

    private HikariDataSource dataSource;
    private TableSinkConfig sinkConfig;
    private String tableName;
    private TableWriteCompiler writeCompiler;
    private TableWritePlan writePlan;
    private List<OutputColumnSpec> outputColumns;

    @Override
    public void open(SinkConfig config, DiffContext context) throws Exception {
        sinkConfig = parseConfig(config.getProperties());
        dataSource = createDataSource(sinkConfig);
        tableName = sinkConfig.resolveTableName();

        DatabaseDialect dialect = DatabaseDialects.require(sinkConfig.resolveDatabaseType());
        writeCompiler = dialect.getTableWriteCompiler();
        outputColumns = buildOutputColumns(dialect);
        writePlan = writeCompiler.compile(new TableWriteCompileRequest(
                tableName,
                sinkConfig.isCreateTable(),
                sinkConfig.isDropIfExists(),
                outputColumns
        ));

        if (sinkConfig.isCreateTable()) {
            createTableIfNotExists();
        }
        log.info("TableResultSink opened, table={}, customFields={}", tableName, sinkConfig.hasCustomColumns());
    }

    @Override
    public void onResult(DiffResult result, DiffContext context) throws SQLException {
        if (dataSource == null) {
            return;
        }
        executePreparedRow(writeCompiler.prepareRow(buildResultRow(result, context), writePlan));
    }

    @Override
    public void onError(DiffContext context, Throwable error) {
        if (dataSource == null) {
            return;
        }
        try {
            executePreparedRow(writeCompiler.prepareRow(buildErrorRow(context, error), writePlan));
        } catch (SQLException e) {
            log.error("Failed to write error result for task {}", context.getTaskId(), e);
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private void createTableIfNotExists() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (sinkConfig.isDropIfExists()) {
                try (PreparedStatement statement = connection.prepareStatement(writePlan.getDropTableSql())) {
                    statement.execute();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(writePlan.getCreateTableSql())) {
                statement.execute();
            }
        }
    }

    private void executePreparedRow(PreparedWriteRow preparedWriteRow) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(writePlan.getInsertSql())) {
            preparedWriteRow.bind(statement);
            statement.executeUpdate();
        }
    }

    private TypedOutputRow buildResultRow(DiffResult result, DiffContext context) {
        List<TypedOutputValue> values = new ArrayList<>();
        for (OutputColumnSpec outputColumn : outputColumns) {
            Object value;
            if (sinkConfig.hasCustomColumns()) {
                ColumnMapping mapping = findColumnMapping(outputColumn.getColumnName());
                value = TypedValueResolver.resolve(mapping, context, result);
            } else {
                value = resolveDefaultResultValue(outputColumn.getColumnName(), result, context);
            }
            values.add(new TypedOutputValue(outputColumn.getColumnName(), outputColumn.getSystemType(), value));
        }
        return new TypedOutputRow(values);
    }

    private TypedOutputRow buildErrorRow(DiffContext context, Throwable error) {
        List<TypedOutputValue> values = new ArrayList<>();
        for (OutputColumnSpec outputColumn : outputColumns) {
            Object value;
            if (sinkConfig.hasCustomColumns()) {
                ColumnMapping mapping = findColumnMapping(outputColumn.getColumnName());
                value = TypedValueResolver.resolveError(mapping, context, error);
            } else {
                value = resolveDefaultErrorValue(outputColumn.getColumnName(), context);
            }
            values.add(new TypedOutputValue(outputColumn.getColumnName(), outputColumn.getSystemType(), value));
        }
        return new TypedOutputRow(values);
    }

    private Object resolveDefaultResultValue(String columnName, DiffResult result, DiffContext context) {
        DiffResult.DiffStatistics stats = result != null ? result.getStatistics() : null;
        switch (columnName) {
            case "nl_dq_execution_id":
                return context.getTaskId();
            case "src_table":
                return result != null && result.getSourceTablePath() != null ? result.getSourceTablePath().getTableName() : "";
            case "tgt_table":
                return result != null && result.getTargetTablePath() != null ? result.getTargetTablePath().getTableName() : "";
            case "diff_count":
                return stats != null ? stats.getTotalDifferences() : 0L;
            case "src_missing":
                return stats != null ? stats.getSourceMissingCount() : 0L;
            case "tgt_missing":
                return stats != null ? stats.getTargetMissingCount() : 0L;
            case "mismatch_count":
                return stats != null ? stats.getMismatchCount() : 0L;
            case "run_status":
                return result != null && result.hasDifferences() ? "DIFF" : "EQUAL";
            case "completed_at":
                return result != null && result.getCompletedAt() != null ? result.getCompletedAt() : Instant.now();
            default:
                return null;
        }
    }

    private Object resolveDefaultErrorValue(String columnName, DiffContext context) {
        switch (columnName) {
            case "nl_dq_execution_id":
                return context.getTaskId();
            case "run_status":
                return "ERROR";
            case "completed_at":
                return Instant.now();
            default:
                return null;
        }
    }

    private List<OutputColumnSpec> buildOutputColumns(DatabaseDialect dialect) {
        if (sinkConfig.hasCustomColumns()) {
            List<OutputColumnSpec> columns = new ArrayList<>();
            for (ColumnMapping mapping : sinkConfig.getColumns()) {
                columns.add(new OutputColumnSpec(
                        sanitize(mapping.getName()),
                        resolveSystemType(dialect, mapping.getColumnType(), Types.TEXT()),
                        true,
                        mapping.getColumnType()
                ));
            }
            return List.copyOf(columns);
        }

        return List.of(
                new OutputColumnSpec("nl_dq_execution_id", Types.VARCHAR(64), true, null),
                new OutputColumnSpec("src_table", Types.VARCHAR(256), true, null),
                new OutputColumnSpec("tgt_table", Types.VARCHAR(256), true, null),
                new OutputColumnSpec("diff_count", Types.BIGINT(), true, null),
                new OutputColumnSpec("src_missing", Types.BIGINT(), true, null),
                new OutputColumnSpec("tgt_missing", Types.BIGINT(), true, null),
                new OutputColumnSpec("mismatch_count", Types.BIGINT(), true, null),
                new OutputColumnSpec("run_status", Types.VARCHAR(32), true, null),
                new OutputColumnSpec("completed_at", Types.TIMESTAMP(), true, null)
        );
    }

    private TypeDescriptor resolveSystemType(DatabaseDialect dialect, String declaredColumnType, TypeDescriptor defaultType) {
        if (declaredColumnType == null || declaredColumnType.isBlank()) {
            return defaultType;
        }
        TypeDescriptor descriptor = dialect.getDataTypeHandler().convertToTypeDescriptor(declaredColumnType);
        return descriptor.getType() == com.consilens.common.enums.DataType.UNKNOWN_TYPE ? defaultType : descriptor;
    }

    private ColumnMapping findColumnMapping(String outputColumnName) {
        for (ColumnMapping mapping : sinkConfig.getColumns()) {
            if (sanitize(mapping.getName()).equals(outputColumnName)) {
                return mapping;
            }
        }
        return null;
    }

    private String sanitize(String column) {
        return column.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private TableSinkConfig parseConfig(String properties) throws Exception {
        if (properties == null || properties.isBlank()) {
            throw new IllegalArgumentException("TableResultSink requires properties configuration (url, username, password)");
        }
        return new ObjectMapper().readValue(properties, TableSinkConfig.class);
    }

    private HikariDataSource createDataSource(TableSinkConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.getUrl());
        hikari.setUsername(config.getUsername());
        hikari.setPassword(config.getPassword());
        hikari.setDriverClassName(config.resolveDriver());
        hikari.setMaximumPoolSize(config.getMaxPoolSize());
        return new HikariDataSource(hikari);
    }
}
