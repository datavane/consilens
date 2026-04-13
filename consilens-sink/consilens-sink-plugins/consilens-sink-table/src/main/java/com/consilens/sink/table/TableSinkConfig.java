package com.consilens.sink.table;

import com.consilens.sink.api.model.ColumnMapping;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Table format sink configuration (wide-table mode).
 *
 * <p>Field mapping rules:
 * <ul>
 *   <li>If {@code fields} is empty, uses default wide-table mode (nl_dq_execution_id + nl_dq_diff_type + dynamic columns).</li>
 *   <li>If {@code fields} is non-empty, outputs by field mapping rules with {@code ${varName}} placeholders.</li>
 *   <li>Multiple sinks of the same format+type can write to different tables.</li>
 * </ul>
 *
 * <pre>
 * result:
 *   sinks:
 *     # write diff records with custom fields
 *     - format: table
 *       type: diff-record
 *       properties:
 *         url: jdbc:mysql://localhost:3306/mydb
 *         username: root
 *         password: secret
 *         tableName: my_diff_detail
 *         createTable: true
 *         batchSize: 1000
 *         columns:
 *           - name: task_id
 *             value: ${taskId}
 *           - name: diff_type
 *             value: ${operation}
 *           - name: pk_value
 *             value: ${primaryKey}
 *           - name: src_amount
 *             value: ${src.amount}
 *             defaultValue: "0"
 *           - name: tgt_amount
 *             value: ${tgt.amount}
 *             defaultValue: "0"
 *           - name: env
 *             value: production
 *           - name: created_at
 *             value: ${timestamp}
 *             columnType: TIMESTAMP
 *     # write to another table with different column mapping
 *     - format: table
 *       type: diff-record
 *       properties:
 *         url: jdbc:mysql://localhost:3306/audit
 *         username: root
 *         password: secret
 *         tableName: audit_diff_log
 *         columns:
 *           - name: execution_id
 *             value: ${taskId}
 *           - name: op
 *             value: ${operation}
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableSinkConfig {

    // ---- Database connection ----

    /** JDBC URL */
    private String url;

    /** Username. */
    private String username;

    /** Password. */
    private String password;

    /** JDBC driver (optional; auto-detected from URL). */
    private String driver;

    /** Max connection pool size; default 5. */
    private int maxPoolSize = 5;

    // ---- Output table naming ----

    /**
     * Explicit table name; overrides prefix + suffixTimestamp when set.
     * When not set, uses prefix + (optional timestamp).
     */
    private String tableName;

    /** Table name prefix; default diff_result_. */
    private String prefix = "diff_result_";

    /** Whether to append timestamp (yyyyMMdd_HHmmss) to table name; default true. */
    private boolean suffixTimestamp = true;

    // ---- DDL options ----

    /** Whether to auto-create the table before writing; default true. */
    private boolean createTable = true;

    /** Whether to drop the table before recreating it; default false. */
    private boolean dropIfExists = false;

    /** Default max length for VARCHAR columns; default 255. */
    private int defaultColumnLength = 255;

    /** Batch size for bulk inserts; default 1000. */
    private int batchSize = 1000;

    // ---- Custom field mapping ----

    /**
     * Custom output column mapping list.
     * <p>When non-empty and {@code mergeDefaults=false}, builds table DDL and INSERT based on mapping rules, ignoring default wide-table mode.
     * <p>When non-empty and {@code mergeDefaults=true}, uses default wide-table schema but overrides values for matched column names.
     * <p>When empty, uses default wide-table mode (nl_dq_execution_id + nl_dq_diff_type + dynamic columns).
     */
    private List<ColumnMapping> columns = new ArrayList<>();

    /**
     * When true, uses default wide-table schema and applies {@code columns} entries as value overrides by name.
     * When false (default), {@code columns} replaces the entire output (full custom mode).
     */
    private boolean mergeDefaults = false;

    /**
     * Whether custom column mode is enabled.
     */
    public boolean hasCustomColumns() {
        return columns != null && !columns.isEmpty();
    }

    /**
     * Whether merge mode is active (defaults + overrides).
     */
    public boolean isMergeMode() {
        return mergeDefaults && hasCustomColumns();
    }

    /**
     * Resolves the actual table name from configuration.
     */
    public String resolveTableName() {
        if (tableName != null && !tableName.isBlank()) {
            return tableName;
        }
        String name = (prefix != null ? prefix : "diff_result_");
        if (suffixTimestamp) {
            name += java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        }
        return name;
    }

    /**
     * Infers the JDBC driver class from the URL.
     */
    public String resolveDriver() {
        if (driver != null && !driver.isBlank()) {
            return driver;
        }
        if (url != null) {
            if (url.contains("mysql")) return "com.mysql.cj.jdbc.Driver";
            if (url.contains("postgresql")) return "org.postgresql.Driver";
            if (url.contains("h2")) return "org.h2.Driver";
            if (url.contains("oracle")) return "oracle.jdbc.OracleDriver";
        }
        return "com.mysql.cj.jdbc.Driver";
    }
}
