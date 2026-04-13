package com.consilens.sink.json;

import com.consilens.core.diff.DiffResult;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Outputs the final diff result to a JSON file.
 *
 * <p>If {@code fields} is non-empty, outputs a JSON object with custom fields;
 * otherwise serializes statisticsMap.
 */
@Slf4j
public class JsonResultSink implements Sink {

    private ObjectMapper objectMapper;
    private JsonSinkConfig sinkConfig;
    private String resolvedPath;
    private DiffResult pendingResult;
    private DiffContext pendingContext;

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
                path != null ? path : "result-${taskId}.json", context);
    }

    @Override
    public void onResult(DiffResult result, DiffContext context) {
        pendingResult = result;
        pendingContext = context;
    }

    @Override
    public void onError(DiffContext context, Throwable error) {
        log.error("Diff error for task {}: {}", context.getTaskId(), error.getMessage());
    }

    @Override
    public void close() throws IOException {
        if (pendingResult == null || resolvedPath == null) {
            return;
        }
        Path path = Paths.get(resolvedPath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Object output;
        if (sinkConfig.hasCustomColumns()) {
            List<ColumnMapping> fields = sinkConfig.getColumns();
            LinkedHashMap<String, String> record = new LinkedHashMap<>();
            for (ColumnMapping f : fields) {
                record.put(f.getName(), ColumnValueInterpolator.resolveField(f, pendingContext, pendingResult));
            }
            output = record;
        } else {
            output = pendingResult.getStatisticsMap();
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            objectMapper.writeValue(writer, output);
        }
        log.info("JsonResultSink wrote result to {}", resolvedPath);
    }

    private JsonSinkConfig parseConfig(String properties) throws IOException {
        if (properties == null || properties.isBlank()) {
            return new JsonSinkConfig();
        }
        return new ObjectMapper().readValue(properties, JsonSinkConfig.class);
    }
}
