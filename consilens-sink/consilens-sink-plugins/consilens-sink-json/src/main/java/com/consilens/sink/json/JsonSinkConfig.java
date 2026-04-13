package com.consilens.sink.json;

import com.consilens.sink.api.model.ColumnMapping;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON format sink configuration.
 *
 * <p>If {@code columns} is non-empty, each record is output as a JSON object with custom columns;
 * otherwise serializes DiffRow / DiffResult directly (default behavior).
 *
 * <pre>
 * result:
 *   sinks:
 *     - format: json
 *       type: diff-record
 *       properties:
 *         path: ./output/diff-${taskId}.json
 *         pretty: true
 *         columns:
 *           - name: taskId
 *             value: ${taskId}
 *           - name: operation
 *             value: ${operation}
 *           - name: primaryKey
 *             value: ${primaryKey}
 *           - name: srcAmount
 *             value: ${src.amount}
 *           - name: tgtAmount
 *             value: ${tgt.amount}
 *           - name: env
 *             value: production
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonSinkConfig {

    /** Output file path; supports ${varName} placeholders. */
    private String path;

    /** Whether to pretty-print (indented) output; default false. */
    private boolean pretty = false;

    /**
     * Custom column mapping list.
     * When non-empty, outputs each record as a JSON object with specified columns;
     * otherwise serializes DiffRow / DiffResult directly.
     */
    private List<ColumnMapping> columns = new ArrayList<>();

    /**
     * When true, outputs all default fields and uses {@code columns} entries as value overrides by name.
     * Extra entries in {@code columns} not matching a default field name are appended.
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
