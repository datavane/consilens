package com.consilens.connector.oracle;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;
import com.consilens.connector.api.model.DataType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Oracle SQL query generator.
 * Uses ROWNUM or FETCH FIRST for pagination.
 */
public class OracleSqlQueryGenerator extends BaseSqlQueryGenerator {

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public OracleSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
    }


    /**
     * Oracle 的 CAST 不接受无长度 VARCHAR，字符串化排序用 TO_CHAR；
     * 并显式 NULLS FIRST——Oracle 默认 NULLS LAST，而归并侧把 NULL 归一成 ''（排在最前），
     * 顺序不一致会让含 NULL 键的行被判成互不匹配（实测 edge 的 (NULL,s1) 行多报 SM+TM）。
     * 对侧（MySQL 族）默认即 NULLS FIRST，口径一致。
     */
    @Override
    protected String orderByExpression(String column) {
        return "TO_CHAR(" + capabilityProvider.quote(column) + ") NULLS FIRST";
    }

    /** join 计划比较列的类型归一化处理器（与 checksum 路径同口径）。 */
    @Override
    protected com.consilens.connector.api.DataTypeHandler dataTypeHandler() {
        return this.dataTypeHandler;
    }

    /** This dialect implements XOR checksum aggregation. */
    @Override
    protected boolean supportsXorChecksum() {
        return true;
    }

    /** 归一化结果为字符串时，须做字节级比较以规避库内排序规则（如 utf8mb4_0900_ai_ci）。 */
    @Override
    protected String binarySafeExpression(String expression) {
        return "STANDARD_HASH(" + expression + ", 'MD5')";
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        if (offset <= 0) {
            // Oracle 12c+: FETCH FIRST
            return "FETCH FIRST " + limit + " ROWS ONLY";
        }
        // Oracle 12c+: OFFSET...FETCH
        return "OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    /**
     * 单参重载必须显式覆写：基类实现返回 MySQL 风格 {@code LIMIT n}，Oracle 不支持，
     * 复合键边界查询等只传 limit 的调用会得到 ORA-00933。
     */
    @Override
    public String getLimitClause(long limit) {
        return getLimitClause(0L, limit);
    }

    @Override
    public String getChecksumSQL(String schemaName, String tableName,
            List<String> keyColumns,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause,
            ChecksumAlgorithm checksumAlgorithm) {
        
        if (checksumAlgorithm != null && checksumAlgorithm.isXor()) {
            return getChecksumSQLWithXor(schemaName, tableName, keyColumns, columns, columnDataTypes, whereClause);
        } else {
            return getChecksumSQLWithConcat(schemaName, tableName, keyColumns, columns, columnDataTypes, whereClause);
        }
    }
    /**
     * Generate checksum SQL using traditional CONCAT method (backward compatible).
     * Uses STANDARD_HASH with MD5 so comparison users do not require execute privilege
     * on SYS.DBMS_CRYPTO. Segmentation bounds the LISTAGG input size.
     */
    private String getChecksumSQLWithConcat(String schemaName, String tableName,
            List<String> keyColumns,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) as row_count, ");

        if (columns.isEmpty()) {
            sql.append("'' as checksum ");
        } else {
            sql.append("LOWER(RAWTOHEX(STANDARD_HASH(COALESCE(LISTAGG(row_checksum, '|') WITHIN GROUP (ORDER BY pk_key, row_checksum), ''), 'MD5'))) as checksum ");
            sql.append("FROM (SELECT ");

            // Build primary key for stable ordering
            for (int i = 0; i < keyColumns.size(); i++) {
                if (i > 0)
                    sql.append(" || '|' || ");
                String col = keyColumns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType)));
            }
            sql.append(" as pk_key, ");

            // Build row checksum using all columns
            sql.append("LOWER(RAWTOHEX(STANDARD_HASH(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0)
                    sql.append(" || '|' || ");
                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType)));
            }
            sql.append(", 'MD5'))) as row_checksum ");

            sql.append("FROM ");
            sql.append(buildRelationRef(schemaName, tableName));

            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql.append(" WHERE ").append(whereClause);
            }
            sql.append(")");
        }

        return sql.toString();
    }

    /**
     * Generate checksum SQL using XOR method (high performance)
     * Formula: SUM(MD5(col1+'_C1') + MD5(col2+'_C2') * 2 + MD5(col3+'_C3') * 3 + ...)
     * Uses SUM aggregate to avoid ORA-00937 (not a single-group group function) error.
     */
    private String getChecksumSQLWithXor(String schemaName, String tableName,
            List<String> keyColumns,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT COUNT(*) as row_count, ");

        if (columns.isEmpty()) {
            sql.append("'0' as checksum ");
        } else {
            // Use SUM aggregate to combine per-row hash values
            // This avoids ORA-00937 when used with COUNT(*)
            sql.append("TO_CHAR(SUM(");

            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(" + ");
                }

                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                String normalizedCol = dataTypeHandler.normalizeColumn(col, dataType);

                // Add salt to each column: col + '_C' + (index+1)
                String saltedCol = normalizedCol + " || '_C" + (i + 1) + "'";

                // Convert STANDARD_HASH to numeric for XOR operation
                // Take first 16 characters of hash and convert from hex to decimal
                String hashExpr = "TO_NUMBER(SUBSTR(STANDARD_HASH(" + saltedCol + ", 'MD5'), 1, 16), 'XXXXXXXXXXXXXXXX')";

                // Apply weight: multiply by (index + 1)
                if (i > 0) {
                    hashExpr = "(" + hashExpr + " * " + (i + 1) + ")";
                }

                sql.append(hashExpr);
            }

            // SUM of unsigned 64-bit hash terms overflows 16 hex digits: LPAD(x, 16)
            // then TRUNCATES leading digits, so different data can collide to the same
            // checksum (false negatives). Render the full decimal sum instead — every
            // value yields a distinct string and the engine only compares strings.
            sql.append(")) as checksum ");
        }

        sql.append("FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }
    @Override
    protected String stringJoin(String separator, List<String> args) {
        // Oracle does not have CONCAT_WS; use || concatenation operator.
        // Note: Oracle treats a NULL operand like an empty string here, so this
        // helper is only used where that behavior is acceptable. The diff-columns
        // expression is generated by buildDiffColumnsExpression below, which
        // explicitly matches MySQL CONCAT_WS semantics.
        if (args == null || args.isEmpty()) {
            return "''";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(" || ").append(separator).append(" || ");
            }
            sb.append(args.get(i));
        }
        return sb.toString();
    }

    /**
     * Build the JSON-like diff-columns expression for Oracle.
     *
     * <p>MySQL's CONCAT_WS skips NULL elements, so unchanged columns (CASE without
     * ELSE) disappear from the result. Oracle's {@code ||} treats NULL as an empty
     * string instead, which would leave stray separators such as {@code [, , "col", ]}.
     * To match MySQL byte for byte, each changed column contributes a leading
     * separator ({@code , "col"}) and LTRIM strips it before wrapping in brackets:
     * {@code '[' || ', ' || LTRIM(concat, ', ') || ', ' || ']'}.
     *
     * <p>覆盖带类型的重载（join 明细只有这一条调用路径）：不带类型的重载在基类里委托到这里，
     * 两个入口因此拿到同一形状。比较用 {@link #buildNullSafeNotEquals} 的二进制安全口径，
     * 与 checksum 路径一致，故列类型只影响归一化、不参与这里的比较。
     */
    @Override
    protected String buildDiffColumnsExpression(String alias1, String alias2,
            List<String> compareColumns1, List<String> compareColumns2,
            Map<String, DataType> columnTypes1, Map<String, DataType> columnTypes2) {
        return buildDiffColumnsExpression(alias1, alias2, compareColumns1, compareColumns2,
                columnTypes1, columnTypes2, compareColumns1);
    }

    @Override
    protected String buildDiffColumnsExpression(String alias1, String alias2,
            List<String> compareColumns1, List<String> compareColumns2,
            Map<String, DataType> columnTypes1, Map<String, DataType> columnTypes2,
            List<String> reportedNames) {
        if (compareColumns1 == null || compareColumns1.isEmpty()) {
            return "'[]'";
        }
        int count = Math.min(compareColumns1.size(),
                compareColumns2 != null ? compareColumns2.size() : compareColumns1.size());
        StringBuilder concat = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String col1 = compareColumns1.get(i);
            String col2 = compareColumns2 != null && compareColumns2.size() > i ? compareColumns2.get(i) : col1;
            String c1 = columnRef(alias1, col1);
            String c2 = columnRef(alias2, col2);
            if (i > 0) {
                concat.append(" || ");
            }
            String reported = reportedNames != null && reportedNames.size() > i
                    ? reportedNames.get(i) : col1;
            concat.append("CASE WHEN ").append(buildNullSafeNotEquals(c1, c2))
                    .append(" THEN ', \"").append(reported).append("\"' END");
        }
        return "'[' || ', ' || COALESCE(LTRIM(" + concat + ", ', '), '') || ', ' || ']'";
    }

    private String columnRef(String alias, String column) {
        if (alias == null || alias.trim().isEmpty()) {
            return capabilityProvider.quote(column);
        }
        return alias + "." + capabilityProvider.quote(column);
    }

    @Override
    public String getRowHashSQL(String schemaName, String tableName,
            List<String> primaryKeys,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();

        // SELECT primary key columns and row_hash
        sql.append("SELECT ");

        // Add primary key columns
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(capabilityProvider.quote(primaryKeys.get(i)));
        }

        // 与其它方言同一口径：'|' 作分隔符；Oracle 中 '' 即 NULL，|| 会把它当空串，
        // 与 MySQL 对 COALESCE(x,'') 的拼接结果一致，因此不再用 CHR(1) 作 NULL 哨兵。
        sql.append(", LOWER(RAWTOHEX(STANDARD_HASH(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(" || '|' || ");
            }
            String col = columns.get(i);
            DataType dataType = columnDataTypes.get(col);
            sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType)));
        }

        sql.append(", 'MD5'))) as row_hash");

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
}
