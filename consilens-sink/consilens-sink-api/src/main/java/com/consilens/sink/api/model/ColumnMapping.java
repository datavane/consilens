package com.consilens.sink.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Output field mapping rule.
 *
 * <p>{@code value} supports {@code ${varName}} placeholders mixed with plain text, e.g.:
 * <ul>
 *   <li>{@code ${taskId}}              — task ID</li>
 *   <li>{@code ${operation}}           — diff operation type (SOURCE_MISSING / TARGET_MISSING / MISMATCH)</li>
 *   <li>{@code ${primaryKey}}          — primary key string</li>
 *   <li>{@code ${changedColumns}}      — changed columns JSON array</li>
 *   <li>{@code ${changedColumns1}}     — source-side changed columns JSON array</li>
 *   <li>{@code ${changedColumns2}}     — target-side changed columns JSON array</li>
 *   <li>{@code ${src.colName}}         — source-side value of specified column</li>
 *   <li>{@code ${tgt.colName}}         — target-side value of specified column</li>
 *   <li>{@code ${sourceTable}}         — source table name</li>
 *   <li>{@code ${targetTable}}         — target table name</li>
 *   <li>{@code ${strategy}}            — comparison strategy</li>
 *   <li>{@code ${algorithm}}           — comparison algorithm</li>
 *   <li>{@code ${timestamp}}           — current time ISO string</li>
 *   <li>{@code ${status}}              — execution status (result type only: EQUAL / DIFF)</li>
 *   <li>{@code ${totalDifferences}}    — total differences (result type only)</li>
 *   <li>{@code ${sourceMissingCount}}  — source-missing row count (result type only)</li>
 *   <li>{@code ${targetMissingCount}}  — target-missing row count (result type only)</li>
 *   <li>{@code ${mismatchCount}}       — mismatched row count (result type only)</li>
 *   <li>{@code ${statistics_json}}     — full statistics JSON string (result type only):
 *       {@code {"totalCount":N,"mismatchCount":N,"sourceMissingCount":N,"targetMissingCount":N,"totalDiffCount":N,"accuracyRate":N}}</li>
 *   <li>{@code ${attr.key}}            — DiffContext custom attribute</li>
 *   <li>plain string (no placeholder)  — used as static constant value</li>
 * </ul>
 *
 * <p>When {@code value} resolves to empty or null, falls back to {@code defaultValue}.
 *
 * <p>YAML configuration example:
 * <pre>
 * result:
 *   sinks:
 *     - format: table
 *       type: diff-record
 *       properties:
 *         url: jdbc:mysql://localhost:3306/db
 *         username: root
 *         password: secret
 *         tableName: my_diff_table
 *         fields:
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
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ColumnMapping {

    /**
     * Output column/field name.
     */
    private String name;

    /**
     * Value template expression supporting {@code ${varName}} placeholders and static text.
     * If no placeholder, used directly as a static constant.
     */
    private String value;

    /**
     * Fallback value used when {@code value} resolves to empty or null.
     */
    private String defaultValue;

    /**
     * SQL column type for DDL (table format only), e.g. {@code VARCHAR(64)}, {@code BIGINT}, {@code TEXT}.
     * Defaults to {@code TEXT} if not set.
     */
    private String columnType;
}
