package com.consilens.sink.csv;

import com.consilens.sink.api.model.ColumnMapping;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV format sink configuration.
 *
 * <p>If {@code columns} is non-empty, uses FieldMapping name/value rules with {@code ${varName}} placeholders;
 * otherwise serializes the raw DiffRow.
 *
 * <pre>
 * result:
 *   sinks:
 *     - format: csv
 *       type: diff-record
 *       properties:
 *         path: ./output/diff-${taskId}.csv
 *         delimiter: ","
 *         includeHeader: true
 *         columns:
 *           - name: task_id
 *             value: ${taskId}
 *           - name: op
 *             value: ${operation}
 *           - name: pk
 *             value: ${primaryKey}
 *           - name: src_amount
 *             value: ${src.amount}
 *           - name: tgt_amount
 *             value: ${tgt.amount}
 *           - name: env
 *             value: production
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CsvSinkConfig {

    /** Output file path; supports ${varName} placeholders. */
    private String path;

    /** Column delimiter; default is comma. */
    private String delimiter = ",";

    /** Whether to write a header row; default true. */
    private boolean includeHeader = true;

    /**
     * Custom column mapping list.
     * When non-empty, uses this mapping; otherwise defaults to operation/primaryKey/sourceValues/targetValues.
     */
    private List<ColumnMapping> columns = new ArrayList<>();

    /**
     * When true, outputs all default columns and uses {@code columns} entries as value overrides by name.
     * Extra entries in {@code columns} not matching a default column name are appended after defaults.
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
}
