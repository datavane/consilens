package com.consilens.sink.csv;

import com.consilens.core.diff.DiffResult;
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
import java.util.List;
import java.util.Map;

/**
 * Outputs the final diff result to a CSV file.
 *
 * <p>If {@code columns} is non-empty, uses column mapping rules; otherwise outputs default stat columns.
 */
@Slf4j
public class CsvResultSink implements Sink {

    private CsvSinkConfig sinkConfig;
    private String resolvedPath;
    private DiffResult pendingResult;
    private DiffContext pendingContext;

    @Override
    public void open(SinkConfig config, DiffContext context) throws IOException {
        sinkConfig = parseConfig(config.getProperties());
        String path = sinkConfig.getPath();
        resolvedPath = ColumnValueInterpolator.resolvePath(
                path != null ? path : "result-${taskId}.csv", context);
    }

    @Override
    public void onResult(DiffResult result, DiffContext context) {
        pendingResult = result;
        pendingContext = context;
    }

    @Override
    public void close() throws IOException {
        if (pendingResult == null) {
            return;
        }
        Path path = Paths.get(resolvedPath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        String delim = sinkConfig.getDelimiter();
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            if (sinkConfig.hasCustomColumns()) {
                writeCustomColumns(writer, delim);
            } else {
                writeDefaultColumns(writer, delim);
            }
        }
        log.info("CsvResultSink wrote result to {}", resolvedPath);
    }

    private void writeCustomColumns(BufferedWriter writer, String delim) throws IOException {
        List<ColumnMapping> cols = sinkConfig.getColumns();
        if (sinkConfig.isIncludeHeader()) {
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) header.append(delim);
                header.append(cols.get(i).getName());
            }
            writer.write(header.toString());
            writer.newLine();
        }
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) row.append(delim);
            row.append(escapeCSV(ColumnValueInterpolator.resolveField(cols.get(i), pendingContext, pendingResult), delim));
        }
        writer.write(row.toString());
        writer.newLine();
    }

    private void writeDefaultColumns(BufferedWriter writer, String delim) throws IOException {
        List<String> defaultCols = java.util.Arrays.asList(
                "taskId", "totalDifferences", "sourceMissingCount", "targetMissingCount", "mismatchCount");
        if (sinkConfig.isIncludeHeader()) {
            writer.write(String.join(delim, defaultCols));
            writer.newLine();
        }
        Map<String, Object> statsMap = pendingResult.getStatisticsMap();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < defaultCols.size(); i++) {
            if (i > 0) sb.append(delim);
            String col = defaultCols.get(i);
            Object val = "taskId".equals(col) ? pendingContext.getTaskId() : statsMap.getOrDefault(col, "");
            sb.append(escapeCSV(val != null ? val.toString() : "", delim));
        }
        writer.write(sb.toString());
        writer.newLine();
    }

    private String escapeCSV(String value, String delim) {
        if (value == null) return "";
        if (value.contains(delim) || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private CsvSinkConfig parseConfig(String properties) throws IOException {
        if (properties == null || properties.isBlank()) {
            return new CsvSinkConfig();
        }
        return new ObjectMapper().readValue(properties, CsvSinkConfig.class);
    }
}
