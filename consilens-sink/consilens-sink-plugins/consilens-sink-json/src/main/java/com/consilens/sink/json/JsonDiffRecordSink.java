package com.consilens.sink.json;

import com.consilens.common.io.PathSupport;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.ColumnValueInterpolator;
import com.consilens.sink.api.Sink;
import com.consilens.sink.api.model.ColumnMapping;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outputs diff records to a JSON file.
 *
 * <p>Three output modes depending on {@code columns} and {@code mergeDefaults}:
 * <ul>
 *   <li><b>Default mode</b> ({@code columns} empty): writes stable JSON objects with primitive/list fields.</li>
 *   <li><b>Full custom mode</b> ({@code columns} non-empty, {@code mergeDefaults=false}): only the configured columns as a JSON object.</li>
 *   <li><b>Merge mode</b> ({@code columns} non-empty, {@code mergeDefaults=true}): default fields with value overrides,
 *       plus extra columns appended after defaults.</li>
 * </ul>
 */
@Slf4j
public class JsonDiffRecordSink implements Sink {

    /** Default field names output in merge mode. */
    private static final List<String> DEFAULT_FIELDS = Arrays.asList(
            "operation", "primaryKey", "sourceValues", "targetValues",
            "columnNames1", "columnNames2", "changedColumns1", "changedColumns2");

    private ObjectMapper objectMapper;
    private JsonSinkConfig sinkConfig;
    private String resolvedPath;
    private BufferedWriter writer;
    private boolean firstRecord = true;
    private long recordCount;

    @Override
    public void open(SinkConfig config, DiffContext context) throws IOException {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        sinkConfig = parseConfig(config.getProperties());
        if (sinkConfig.isPretty()) {
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
        String path = sinkConfig.getPath();
        resolvedPath = ColumnValueInterpolator.resolvePath(
                path != null ? path : "diff-record-${taskId}.json", context);
        Path target = Paths.get(resolvedPath);
        PathSupport.ensureParent(target);
        writer = Files.newBufferedWriter(target);
        writer.write("[");
    }

    @Override
    public void onDiffRecords(List<DiffRow> rows, DiffContext context) {
        try {
            if (sinkConfig.isMergeMode()) {
                Map<String, ColumnMapping> overrideMap = buildOverrideMap();
                for (DiffRow row : rows) {
                    writeRecord(buildMergeRecord(row, context, overrideMap));
                }
            } else if (sinkConfig.hasCustomColumns()) {
                List<ColumnMapping> fields = sinkConfig.getColumns();
                for (DiffRow row : rows) {
                    LinkedHashMap<String, String> record = new LinkedHashMap<>();
                    for (ColumnMapping f : fields) {
                        record.put(f.getName(), ColumnValueInterpolator.resolveField(f, context, row));
                    }
                    writeRecord(record);
                }
            } else {
                for (DiffRow row : rows) {
                    writeRecord(buildDefaultRecord(row));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write diff record to " + resolvedPath, e);
        }
    }

    private void writeRecord(Object record) throws IOException {
        if (!firstRecord) {
            writer.write(",");
        }
        if (sinkConfig.isPretty()) {
            writer.write("\n  ");
        }
        writer.write(objectMapper.writeValueAsString(record));
        firstRecord = false;
        recordCount++;
    }

    private LinkedHashMap<String, Object> buildDefaultRecord(DiffRow row) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        for (String field : DEFAULT_FIELDS) {
            record.put(field, defaultValue(field, row));
        }
        return record;
    }

    private LinkedHashMap<String, Object> buildMergeRecord(DiffRow row, DiffContext context,
                                                            Map<String, ColumnMapping> overrideMap) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        // Default fields with optional overrides
        for (String field : DEFAULT_FIELDS) {
            if (overrideMap.containsKey(field)) {
                String resolved = ColumnValueInterpolator.resolveField(overrideMap.get(field), context, row);
                record.put(field, resolved != null ? resolved : defaultValue(field, row));
            } else {
                record.put(field, defaultValue(field, row));
            }
        }
        // Extra columns not in default set
        for (ColumnMapping cm : sinkConfig.getColumns()) {
            if (!DEFAULT_FIELDS.contains(cm.getName())) {
                record.put(cm.getName(), ColumnValueInterpolator.resolveField(cm, context, row));
            }
        }
        return record;
    }

    /** Default value for a known default field. */
    private Object defaultValue(String field, DiffRow row) {
        switch (field) {
            case "operation":      return row.getOperation().getCode();
            case "primaryKey":     return row.getPrimaryKeyString();
            case "sourceValues":   return row.getAllSourceValues();
            case "targetValues":   return row.getAllTargetValues();
            case "columnNames1":   return row.getColumnNames1();
            case "columnNames2":   return row.getColumnNames2();
            case "changedColumns1": return row.getChangedColumns1();
            case "changedColumns2": return row.getChangedColumns2();
            default:               return null;
        }
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

    @Override
    public void onError(DiffContext context, Throwable error) {
        log.warn("Closing JSON diff record sink after error: {}", error.getMessage());
        try {
            close();
        } catch (IOException e) {
            log.warn("Failed to finalize JSON diff record file after error", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (writer == null) {
            return;
        }
        try {
            if (sinkConfig.isPretty()) {
                writer.write("\n");
            }
            writer.write("]");
        } finally {
            writer.close();
            writer = null;
        }
        log.info("JsonDiffRecordSink wrote {} records to {}", recordCount, resolvedPath);
    }

    private JsonSinkConfig parseConfig(String properties) throws IOException {
        if (properties == null || properties.isBlank()) {
            return new JsonSinkConfig();
        }
        return new ObjectMapper().readValue(properties, JsonSinkConfig.class);
    }
}
