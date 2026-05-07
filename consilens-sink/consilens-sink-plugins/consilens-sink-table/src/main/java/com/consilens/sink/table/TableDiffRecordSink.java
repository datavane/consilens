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
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.SegmentResult;
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

@Slf4j
public class TableDiffRecordSink implements Sink {

    private HikariDataSource dataSource;
    private TableSinkConfig sinkConfig;
    private String tableName;
    private List<String> sourceColumns;
    private List<String> targetColumns;
    private Map<String, String> sourceOutputColumns;
    private Map<String, String> targetOutputColumns;
    private int batchSize;
    private TableWriteCompiler writeCompiler;
    private TableWritePlan writePlan;
    private List<OutputColumnSpec> outputColumns;

    @Override
    public void open(SinkConfig config, DiffContext context) throws Exception {
        sinkConfig = parseConfig(config.getProperties());
        batchSize = sinkConfig.getBatchSize();
        if (batchSize <= 0) {
            throw new IllegalArgumentException("sink.batchSize 必须大于 0");
        }
        TableColumnNames.validateUniqueSanitizedColumns(sinkConfig.getColumns(), "TableDiffRecordSink columns");
        dataSource = createDataSource(sinkConfig);
        tableName = sinkConfig.resolveTableName();

        sourceColumns = context.getSourceColumnNames() != null ? context.getSourceColumnNames() : new ArrayList<>();
        targetColumns = context.getTargetColumnNames() != null ? context.getTargetColumnNames() : new ArrayList<>();
        sourceOutputColumns = new LinkedHashMap<>();
        targetOutputColumns = new LinkedHashMap<>();
        for (String column : sourceColumns) {
            sourceOutputColumns.put(TableColumnNames.sanitize(column) + "_1", column);
        }
        for (String column : targetColumns) {
            targetOutputColumns.put(TableColumnNames.sanitize(column) + "_2", column);
        }

        DatabaseDialect dialect = resolveDialect(sinkConfig);
        writeCompiler = dialect.getTableWriteCompiler();
        outputColumns = buildOutputColumns(dialect);
        TableColumnNames.validateUniqueOutputColumns(outputColumns, "TableDiffRecordSink");
        writePlan = writeCompiler.compile(new TableWriteCompileRequest(
                tableName,
                sinkConfig.isCreateTable(),
                sinkConfig.isDropIfExists(),
                outputColumns
        ));

        if (sinkConfig.isCreateTable() && !outputColumns.isEmpty()) {
            createOutputTable();
        }
        log.info("TableDiffRecordSink opened, table={}, mode={}", tableName,
                sinkConfig.isMergeMode() ? "merge" : sinkConfig.hasCustomColumns() ? "custom" : "default");
    }

    @Override
    public void onDiffRecords(List<DiffRow> rows, DiffContext context) throws SQLException {
        if (dataSource == null || rows == null || rows.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(writePlan.getInsertSql())) {
            connection.setAutoCommit(false);
            try {
                int count = 0;
                for (DiffRow row : rows) {
                    PreparedWriteRow preparedRow = writeCompiler.prepareRow(buildTypedOutputRow(row, context), writePlan);
                    preparedRow.bind(preparedStatement);
                    preparedStatement.addBatch();
                    if (++count % batchSize == 0) {
                        preparedStatement.executeBatch();
                    }
                }
                if (count % batchSize != 0) {
                    preparedStatement.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            }
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

    private void createOutputTable() throws SQLException {
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

    private TypedOutputRow buildTypedOutputRow(DiffRow row, DiffContext context) {
        List<TypedOutputValue> values = new ArrayList<>();
        Map<String, ColumnMapping> overrideMap = sinkConfig.isMergeMode() ? buildOverrideMap() : Map.of();
        for (OutputColumnSpec outputColumn : outputColumns) {
            Object value;
            if (sinkConfig.hasCustomColumns() && !sinkConfig.isMergeMode()) {
                ColumnMapping mapping = findColumnMapping(outputColumn.getColumnName());
                value = TypedValueResolver.resolve(mapping, context, row);
            } else {
                value = resolveDefaultValue(outputColumn.getColumnName(), overrideMap, context, row);
            }
            values.add(new TypedOutputValue(outputColumn.getColumnName(), outputColumn.getSystemType(), value));
        }
        return new TypedOutputRow(values);
    }

    private Object resolveDefaultValue(String outputColumnName,
                                       Map<String, ColumnMapping> overrideMap,
                                       DiffContext context,
                                       DiffRow row) {
        ColumnMapping override = overrideMap.get(outputColumnName);
        if (override != null) {
            return TypedValueResolver.resolve(override, context, row);
        }
        switch (outputColumnName) {
            case "nl_dq_execution_id":
                return context.getTaskId();
            case "nl_dq_diff_type":
                return row.getOperation().getCode();
            case "nl_dq_diff_columns1":
                return toJsonArray(row.getChangedColumns1());
            case "nl_dq_diff_columns2":
                return toJsonArray(row.getChangedColumns2());
            default:
                if (sourceOutputColumns.containsKey(outputColumnName)) {
                    return row.getSourceValue(sourceOutputColumns.get(outputColumnName));
                }
                if (targetOutputColumns.containsKey(outputColumnName)) {
                    return row.getTargetValue(targetOutputColumns.get(outputColumnName));
                }
                return null;
        }
    }

    private List<OutputColumnSpec> buildOutputColumns(DatabaseDialect dialect) {
        if (sinkConfig.hasCustomColumns() && !sinkConfig.isMergeMode()) {
            List<OutputColumnSpec> columns = new ArrayList<>();
            for (ColumnMapping mapping : sinkConfig.getColumns()) {
                columns.add(new OutputColumnSpec(
                        TableColumnNames.sanitize(mapping.getName()),
                        resolveSystemType(dialect, mapping.getColumnType(), Types.TEXT()),
                        true,
                        mapping.getColumnType()
                ));
            }
            return List.copyOf(columns);
        }

        List<OutputColumnSpec> columns = new ArrayList<>();
        columns.add(new OutputColumnSpec("nl_dq_execution_id", Types.VARCHAR(64), true, null));
        columns.add(new OutputColumnSpec("nl_dq_diff_type", Types.VARCHAR(20), false, null));
        columns.add(new OutputColumnSpec("nl_dq_diff_columns1", Types.JSON(), true, null));
        columns.add(new OutputColumnSpec("nl_dq_diff_columns2", Types.JSON(), true, null));
        for (String column : sourceColumns) {
            columns.add(new OutputColumnSpec(TableColumnNames.sanitize(column) + "_1", Types.TEXT(), true, null));
        }
        for (String column : targetColumns) {
            columns.add(new OutputColumnSpec(TableColumnNames.sanitize(column) + "_2", Types.TEXT(), true, null));
        }
        return List.copyOf(columns);
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
            if (TableColumnNames.sanitize(mapping.getName()).equals(outputColumnName)) {
                return mapping;
            }
        }
        return null;
    }

    private Map<String, ColumnMapping> buildOverrideMap() {
        Map<String, ColumnMapping> map = new LinkedHashMap<>();
        if (sinkConfig.getColumns() != null) {
            for (ColumnMapping column : sinkConfig.getColumns()) {
                map.put(TableColumnNames.sanitize(column.getName()), column);
            }
        }
        return map;
    }

    private void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            log.warn("TableDiffRecordSink rollback failed", rollbackError);
        }
    }

    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
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

    private DatabaseDialect resolveDialect(TableSinkConfig config) {
        return DatabaseDialects.require(config.resolveDatabaseType());
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
