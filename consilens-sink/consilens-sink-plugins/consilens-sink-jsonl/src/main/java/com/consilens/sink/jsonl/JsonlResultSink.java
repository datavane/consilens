package com.consilens.sink.jsonl;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.ColumnValueInterpolator;
import com.consilens.sink.api.Sink;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the run summary (statistics + counts) as a single JSON Lines record.
 */
@Slf4j
public class JsonlResultSink implements Sink {

    private ObjectMapper objectMapper;
    private String resolvedPath;
    private BufferedWriter writer;

    @Override
    public void open(SinkConfig config, DiffContext context) throws IOException {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        String propertiesJson = config.getProperties();
        JsonlSinkConfig sinkConfig = new JsonlSinkConfig();
        if (propertiesJson != null && !propertiesJson.isBlank()) {
            try {
                sinkConfig = objectMapper.readValue(propertiesJson, JsonlSinkConfig.class);
            } catch (Exception e) {
                log.warn("Invalid jsonl sink properties, using defaults: {}", e.getMessage());
            }
        }
        String path = sinkConfig.getPath();
        resolvedPath = ColumnValueInterpolator.resolvePath(
                path != null ? path : "diff-result-${taskId}.jsonl", context);
        Path target = Paths.get(resolvedPath);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        writer = Files.newBufferedWriter(target);
    }

    @Override
    public void onResult(DiffResult result, DiffContext context) throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskId", context.getTaskId());
        summary.put("hasDifferences", result != null && result.hasDifferences());
        summary.put("differenceCount", result != null ? result.getDifferenceCount() : 0L);
        summary.put("statistics", result != null ? result.getStatisticsMap() : Map.of());
        writer.write(objectMapper.writeValueAsString(summary));
        writer.write("\n");
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
