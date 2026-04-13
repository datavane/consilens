package com.consilens.sink.csv;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.ColumnValueInterpolator;
import com.consilens.sink.api.Sink;
import com.consilens.sink.api.model.ColumnMapping;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outputs diff records to a CSV file with streaming writes (flush per batch).
 *
 * <p>Three output modes depending on {@code columns} and {@code mergeDefaults}:
 * <ul>
 *   <li><b>Default mode</b> ({@code columns} empty): four fixed columns — operation / primaryKey / sourceValues / targetValues.</li>
 *   <li><b>Full custom mode</b> ({@code columns} non-empty, {@code mergeDefaults=false}): only the configured columns.</li>
 *   <li><b>Merge mode</b> ({@code columns} non-empty, {@code mergeDefaults=true}): default columns with value overrides,
 *       plus any extra columns appended after defaults.</li>
 * </ul>
 */
@Slf4j
public class CsvDiffRecordSink implements Sink {

    private static final List<String> DEFAULT_COLUMNS = Arrays.asList("operation", "primaryKey", "sourceValues", "targetValues");

    private BufferedWriter writer;
    private CsvSinkConfig sinkConfig;

    @Override
    public void open(SinkConfig config, DiffContext context) throws IOException {
        sinkConfig = parseConfig(config.getProperties());
        String path = sinkConfig.getPath();
        String resolvedPath = ColumnValueInterpolator.resolvePath(
                path != null ? path : "diff-record-${taskId}.csv", context);
        Path filePath = Paths.get(resolvedPath);
        if (filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }
        writer = Files.newBufferedWriter(filePath);
        if (sinkConfig.isIncludeHeader()) {
            writeHeader();
        }
    }

    private void writeHeader() throws IOException {
        List<String> headers;
        if (sinkConfig.isMergeMode()) {
            headers = buildMergeHeaders();
        } else if (sinkConfig.hasCustomColumns()) {
            headers = new ArrayList<>();
            for (ColumnMapping col : sinkConfig.getColumns()) {
                headers.add(col.getName());
            }
        } else {
            headers = DEFAULT_COLUMNS;
        }
        writer.write(String.join(sinkConfig.getDelimiter(), headers));
        writer.newLine();
        writer.flush();
    }

    @Override
    public void onDiffRecords(List<DiffRow> rows, DiffContext context) throws IOException {
        for (DiffRow row : rows) {
            writer.write(buildLine(row, context));
            writer.newLine();
        }
        writer.flush();
    }

    private String buildLine(DiffRow row, DiffContext context) {
        String delim = sinkConfig.getDelimiter();
        if (sinkConfig.isMergeMode()) {
            return buildMergeLine(row, context, delim);
        } else if (sinkConfig.hasCustomColumns()) {
            StringBuilder sb = new StringBuilder();
            List<ColumnMapping> cols = sinkConfig.getColumns();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(delim);
                sb.append(escapeCSV(ColumnValueInterpolator.resolveField(cols.get(i), context, row)));
            }
            return sb.toString();
        } else {
            return escapeCSV(row.getOperation().name()) + delim
                    + escapeCSV(row.getPrimaryKeyString()) + delim
                    + escapeCSV(row.getAllSourceValues().toString()) + delim
                    + escapeCSV(row.getAllTargetValues().toString());
        }
    }

    private String buildMergeLine(DiffRow row, DiffContext context, String delim) {
        Map<String, ColumnMapping> overrideMap = buildOverrideMap();
        List<String> headers = buildMergeHeaders();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) sb.append(delim);
            String colName = headers.get(i);
            String value;
            if (overrideMap.containsKey(colName)) {
                value = ColumnValueInterpolator.resolveField(overrideMap.get(colName), context, row);
                if (value == null) value = defaultValue(colName, row);
            } else {
                value = defaultValue(colName, row);
            }
            sb.append(escapeCSV(value));
        }
        return sb.toString();
    }

    /** Default column headers for merge mode: defaults + extra custom columns. */
    private List<String> buildMergeHeaders() {
        List<String> headers = new ArrayList<>(DEFAULT_COLUMNS);
        Map<String, ColumnMapping> overrideMap = buildOverrideMap();
        for (ColumnMapping cm : sinkConfig.getColumns()) {
            if (!DEFAULT_COLUMNS.contains(cm.getName())) {
                headers.add(cm.getName());
            }
        }
        return headers;
    }

    /** Build a name→mapping lookup from the configured columns list. */
    private Map<String, ColumnMapping> buildOverrideMap() {
        Map<String, ColumnMapping> map = new LinkedHashMap<>();
        if (sinkConfig.getColumns() != null) {
            for (ColumnMapping cm : sinkConfig.getColumns()) {
                map.put(cm.getName(), cm);
            }
        }
        return map;
    }

    /** Default value for a known default column. Returns empty string for unknown extras. */
    private String defaultValue(String colName, DiffRow row) {
        switch (colName) {
            case "operation":    return row.getOperation().name();
            case "primaryKey":   return row.getPrimaryKeyString();
            case "sourceValues": return row.getAllSourceValues().toString();
            case "targetValues": return row.getAllTargetValues().toString();
            default:             return "";
        }
    }

    private String escapeCSV(String value) {
        if (value == null) return "";
        String delim = sinkConfig.getDelimiter();
        if (value.contains(delim) || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
            log.info("CsvDiffRecordSink closed: {}", sinkConfig != null ? sinkConfig.getPath() : "");
        }
    }

    private CsvSinkConfig parseConfig(String properties) throws IOException {
        if (properties == null || properties.isBlank()) {
            return new CsvSinkConfig();
        }
        return new ObjectMapper().readValue(properties, CsvSinkConfig.class);
    }
}
