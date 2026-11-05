package com.consilens.connector.trino;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;

import com.consilens.connector.api.model.DataType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Trino SQL query generator.
 * Uses OFFSET...LIMIT for pagination (ANSI SQL standard).
 */
public class TrinoSqlQueryGenerator extends BaseSqlQueryGenerator {

    /** 归并比较把 NULL 当空串排在最前；这些方言升序默认 NULLS LAST，必须显式对齐。 */
    @Override
    protected String orderByExpression(String column) {
        return super.orderByExpression(column) + " NULLS FIRST";
    }

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public TrinoSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
    }


    @Override
    public boolean supportsKeyMultiplicityFilter() {
        // Trino 报 "Given correlated subquery is not supported"：开放档 join 遇重复键时
        // 退化为整表本地多重集配对（不 N×M，结果与 checksum/streaming 一致）。
        return false;
    }

    /** join 计划比较列的类型归一化处理器（与 checksum 路径同口径）。 */
    @Override
    protected com.consilens.connector.api.DataTypeHandler dataTypeHandler() {
        return this.dataTypeHandler;
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        if (offset <= 0) {
            return "LIMIT " + limit;
        }
        return "OFFSET " + offset + " LIMIT " + limit;
    }

    @Override
    public String getChecksumSQL(String schemaName, String tableName,
            List<String> keyColumns,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) as row_count, ");

        if (columns.isEmpty()) {
            sql.append("'' as checksum ");
        } else {
            // Two-step approach matching MySQL: per-row MD5 + aggregate MD5
            sql.append("COALESCE(lower(to_hex(md5(to_utf8(array_join(array_agg(row_checksum ORDER BY pk_key, row_checksum), '|'))))), '') as checksum ");
            sql.append("FROM (SELECT ");

            // Build primary key for stable ordering
            // Trino CONCAT requires at least 2 arguments, so handle single key specially
            if (keyColumns.size() == 1) {
                String col = keyColumns.get(0);
                DataType dataType = columnDataTypes.get(col);
                sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType))).append(" as pk_key, ");
            } else {
                sql.append("CONCAT(");
                for (int i = 0; i < keyColumns.size(); i++) {
                    if (i > 0)
                        sql.append(", '|', ");
                    String col = keyColumns.get(i);
                    DataType dataType = columnDataTypes.get(col);
                    sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType)));
                }
                sql.append(") as pk_key, ");
            }

            // Build per-row checksum using MD5
            sql.append("lower(to_hex(md5(to_utf8(")
                    .append(concatColumns(columns, columnDataTypes))
                    .append(")))) as row_checksum");
        }

        sql.append(" FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }
        
        if (!columns.isEmpty()) {
            sql.append(") t");
        }

        return sql.toString();
    }

    /**
     * Trino 的 CONCAT 至少需要两个参数：只有一列（或没有列）时不能生成 CONCAT(...)，
     * 否则 SQL 直接报 "There must be two or more concatenation arguments"。
     */
    private String concatColumns(List<String> columns, Map<String, DataType> columnDataTypes) {
        if (columns.isEmpty()) {
            return "''";
        }
        if (columns.size() == 1) {
            String col = columns.get(0);
            return escapeHashValue(dataTypeHandler.normalizeColumn(col, columnDataTypes.get(col)));
        }
        StringBuilder sql = new StringBuilder("CONCAT(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", '|', ");
            }
            String col = columns.get(i);
            sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, columnDataTypes.get(col))));
        }
        return sql.append(")").toString();
    }

    @Override
    public String getRowHashSQL(String schemaName, String tableName,
            List<String> primaryKeys,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT ");
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(capabilityProvider.quote(primaryKeys.get(i)));
        }

        sql.append(", lower(to_hex(md5(to_utf8(")
                .append(concatColumns(columns, columnDataTypes))
                .append(")))) AS row_hash");

        sql.append(" FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }
    @Override
    public String getBatchInsertSQL(String tableName, List<String> columns, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be at least 1");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(capabilityProvider.quote(tableName)).append(" (");

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0)
                sql.append(", ");
            sql.append(capabilityProvider.quote(columns.get(i)));
        }
        sql.append(") VALUES ");

        String valuePlaceholder = "(" +
                String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";

        for (int i = 0; i < batchSize; i++) {
            if (i > 0)
                sql.append(", ");
            sql.append(valuePlaceholder);
        }

        return sql.toString();
    }

    /**
     * Trino does not have CONCAT_WS. Use ARRAY_JOIN(ARRAY[...], sep) instead.
     */
    @Override
    protected String stringJoin(String separator, List<String> args) {
        return "ARRAY_JOIN(ARRAY[" + String.join(", ", args) + "], " + separator + ")";
    }
}
