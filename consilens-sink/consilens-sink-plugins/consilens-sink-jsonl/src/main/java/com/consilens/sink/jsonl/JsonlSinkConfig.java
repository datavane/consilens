package com.consilens.sink.jsonl;

import com.consilens.sink.api.model.ColumnMapping;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON Lines sink configuration.
 *
 * <p>Each diff record is written as one standalone JSON object per line (LF terminated),
 * which supports line-based pagination and is safe for values containing commas,
 * spaces, newlines, quotes or CJK characters.
 *
 * <pre>
 * result:
 *   sinks:
 *     - format: jsonl
 *       type: diff-record
 *       properties:
 *         path: ./.consilens-server/artifacts/artifact-${artifactId}.jsonl
 *         columns:
 *           - name: op
 *             value: ${operation}
 *           - name: pk
 *             value: ${primaryKey}
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonlSinkConfig {

    /** Output file path; supports ${varName} placeholders. */
    private String path;

    /**
     * Maximum number of rows written; extra rows are skipped and the sink is marked truncated.
     * Defaults to unlimited (Long.MAX_VALUE) — the differences file always contains all rows.
     */
    private long maxRows = Long.MAX_VALUE;

    /**
     * Custom column mapping list. When non-empty, each record is a JSON object with the
     * configured columns (${operation} / ${primaryKey} / ${src.amount} placeholders);
     * otherwise the default fields (operation / primaryKey / metadata) are written.
     */
    private List<ColumnMapping> columns = new ArrayList<>();

    public boolean hasCustomColumns() {
        return columns != null && !columns.isEmpty();
    }
}
