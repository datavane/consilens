package com.consilens.sink.jsonl;

import com.consilens.common.io.PathSupport;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes diff records to a JSON Lines file: one standalone JSON object per line.
 *
 * <p>Line-oriented layout makes the file pageable (offset = line offset) and keeps
 * values containing commas / spaces / newlines / quotes safe via JSON escaping.
 *
 * <p>Default mode writes {@code operation / primaryKey / metadata} (metadata carries
 * changedColumns1/2 and changedColumnIndices); custom column mode uses configured
 * {@code columns} with ${varName} placeholders.
 */
@Slf4j
public class JsonlDiffRecordSink implements Sink {

    private static final List<String> DEFAULT_FIELDS = List.of(
            "operation", "primaryKey", "metadata");

    private ObjectMapper objectMapper;
    private JsonlSinkConfig sinkConfig;
    private String resolvedPath;
    private BufferedWriter writer;
    private long recordCount;
    private boolean truncated;

    @Override
    public void open(SinkConfig config, DiffContext context) throws IOException {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        sinkConfig = parseConfig(config.getProperties());
        String path = sinkConfig.getPath();
        resolvedPath = ColumnValueInterpolator.resolvePath(
                path != null ? path : "diff-record-${taskId}.jsonl", context);
        Path target = Paths.get(resolvedPath);
        PathSupport.ensureParent(target);
        writer = Files.newBufferedWriter(target);
    }

    @Override
    public void onDiffRecords(List<DiffRow> rows, DiffContext context) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        try {
            for (DiffRow row : rows) {
                if (recordCount >= sinkConfig.getMaxRows()) {
                    truncated = true;
                    break;
                }
                Object record;
                if (sinkConfig.hasCustomColumns()) {
                    record = buildCustomRecord(row, context);
                } else {
                    record = buildDefaultRecord(row);
                }
                writer.write(objectMapper.writeValueAsString(record));
                writer.write("\n");
                recordCount++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write diff record to " + resolvedPath, e);
        }
    }

    @Override
    public long writtenRecordCount() {
        return recordCount;
    }

    @Override
    public boolean isTruncated() {
        return truncated;
    }

    private LinkedHashMap<String, Object> buildDefaultRecord(DiffRow row) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        for (String field : DEFAULT_FIELDS) {
            switch (field) {
                case "operation":
                    record.put(field, row.getOperation().getCode());
                    break;
                case "primaryKey":
                    record.put(field, row.getPrimaryKey());
                    break;
                case "metadata":
                    record.put(field, row.getMetadata());
                    break;
                default:
                    throw new IllegalStateException("Unsupported default field: " + field);
            }
        }
        // 字段值：完整列名 + 两边行值（与 primaryKey/metadata 一同落盘，供差异明细展示且不截断）
        List<String> columnNames = row.resolvedColumnNames();
        if (!columnNames.isEmpty()) {
            record.put("columnNames", columnNames);
        }
        List<Object> sourceValues = row.getAllSourceValues();
        if (!sourceValues.isEmpty()) {
            record.put("sourceValues", sourceValues);
        }
        List<Object> targetValues = row.getAllTargetValues();
        if (!targetValues.isEmpty()) {
            record.put("targetValues", targetValues);
        }
        return record;
    }

    private LinkedHashMap<String, String> buildCustomRecord(DiffRow row, DiffContext context) {
        LinkedHashMap<String, String> record = new LinkedHashMap<>();
        for (ColumnMapping field : sinkConfig.getColumns()) {
            record.put(field.getName(), ColumnValueInterpolator.resolveField(field, context, row));
        }
        return record;
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    private JsonlSinkConfig parseConfig(String propertiesJson) {
        JsonlSinkConfig config = new JsonlSinkConfig();
        if (propertiesJson == null || propertiesJson.isBlank()) {
            return config;
        }
        try {
            return objectMapper.readValue(propertiesJson, JsonlSinkConfig.class);
        } catch (Exception e) {
            log.warn("Invalid jsonl sink properties, using defaults: {}", e.getMessage());
            return config;
        }
    }
}
