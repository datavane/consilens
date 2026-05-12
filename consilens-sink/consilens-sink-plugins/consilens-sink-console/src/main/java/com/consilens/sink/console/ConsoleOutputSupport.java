package com.consilens.sink.console;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.SegmentResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConsoleOutputSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private ConsoleOutputSupport() {
    }

    static ConsoleSinkConfig parseConfig(String properties) throws IOException {
        if (properties == null || properties.isBlank()) {
            return new ConsoleSinkConfig();
        }
        return OBJECT_MAPPER.readValue(properties, ConsoleSinkConfig.class);
    }

    static void printStdout(Map<String, Object> payload, ConsoleSinkConfig config) {
        System.out.println(toJson(payload, config));
    }

    static void printStderr(Map<String, Object> payload, ConsoleSinkConfig config) {
        System.err.println(toJson(payload, config));
    }

    static Map<String, Object> diffRecordPayload(DiffRow row, DiffContext context) {
        LinkedHashMap<String, Object> payload = basePayload("diff-record", context);
        payload.put("operation", row.getOperation().getCode());
        payload.put("primaryKey", row.getPrimaryKey());
        payload.put("primaryKeyString", row.getPrimaryKeyString());
        payload.put("sourceValues", row.getAllSourceValues());
        payload.put("targetValues", row.getAllTargetValues());
        payload.put("columnNames1", row.getColumnNames1());
        payload.put("columnNames2", row.getColumnNames2());
        payload.put("changedColumns1", row.getChangedColumns1());
        payload.put("changedColumns2", row.getChangedColumns2());
        if (row.getMetadata() != null && !row.getMetadata().isEmpty()) {
            payload.put("metadata", row.getMetadata());
        }
        return payload;
    }

    static Map<String, Object> resultPayload(DiffResult result, DiffContext context, boolean includeSummary) {
        LinkedHashMap<String, Object> payload = basePayload("result", context);
        payload.put("status", result != null && result.hasDifferences() ? "DIFF" : "EQUAL");
        payload.put("statistics", result != null ? result.getStatisticsMap() : Map.of());
        if (includeSummary) {
            payload.put("summary", safeSummary(result));
        }
        if (result != null && result.getMetadata() != null && !result.getMetadata().isEmpty()) {
            payload.put("metadata", result.getMetadata());
        }
        return payload;
    }

    static Map<String, Object> segmentPayload(SegmentResult segmentResult) {
        LinkedHashMap<String, Object> payload = basePayload("segment-complete", segmentResult.getContext());
        payload.put("segmentIndex", segmentResult.getSegmentIndex());
        payload.put("sourceRowsScanned", segmentResult.getSourceRowsScanned());
        payload.put("targetRowsScanned", segmentResult.getTargetRowsScanned());
        payload.put("differencesFound", segmentResult.getDifferencesFound());
        payload.put("totalDifferencesAccumulated", segmentResult.getTotalDifferencesAccumulated());
        payload.put("durationMs", segmentResult.getDuration() != null ? segmentResult.getDuration().toMillis() : null);
        return payload;
    }

    static Map<String, Object> truncationPayload(DiffContext context, long maxRows) {
        LinkedHashMap<String, Object> payload = basePayload("diff-record-truncated", context);
        payload.put("maxRows", maxRows);
        payload.put("message", "maxRows reached, further rows suppressed");
        return payload;
    }

    static Map<String, Object> errorPayload(DiffContext context, Throwable error) {
        LinkedHashMap<String, Object> payload = basePayload("error", context);
        payload.put("error", errorDetails(error));
        return payload;
    }

    private static LinkedHashMap<String, Object> basePayload(String event, DiffContext context) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("timestamp", Instant.now().toString());
        if (context == null) {
            return payload;
        }
        payload.put("taskId", context.getTaskId());
        payload.put("strategy", context.getStrategy());
        payload.put("algorithm", context.getAlgorithm());
        if (context.getSourceTablePath() != null) {
            payload.put("sourceTable", context.getSourceTablePath().getFullPath());
        }
        if (context.getTargetTablePath() != null) {
            payload.put("targetTable", context.getTargetTablePath().getFullPath());
        }
        return payload;
    }

    private static Map<String, Object> errorDetails(Throwable error) {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        if (error == null) {
            detail.put("type", "UnknownError");
            detail.put("message", null);
            return detail;
        }
        detail.put("type", error.getClass().getName());
        detail.put("message", error.getMessage());
        Throwable cause = error.getCause();
        if (cause != null) {
            List<Map<String, Object>> causes = new ArrayList<>();
            while (cause != null) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("type", cause.getClass().getName());
                item.put("message", cause.getMessage());
                causes.add(item);
                cause = cause.getCause();
            }
            detail.put("causes", causes);
        }
        if (error.getSuppressed() != null && error.getSuppressed().length > 0) {
            List<Map<String, Object>> suppressed = new ArrayList<>();
            for (Throwable item : error.getSuppressed()) {
                LinkedHashMap<String, Object> suppressedItem = new LinkedHashMap<>();
                suppressedItem.put("type", item.getClass().getName());
                suppressedItem.put("message", item.getMessage());
                suppressed.add(suppressedItem);
            }
            detail.put("suppressed", suppressed);
        }
        return detail;
    }

    private static String safeSummary(DiffResult result) {
        if (result == null) {
            return "No result available";
        }
        if (result.getSourceTablePath() == null || result.getTargetTablePath() == null) {
            return "Source/target table metadata unavailable";
        }
        return result.getSummary();
    }

    private static String toJson(Map<String, Object> payload, ConsoleSinkConfig config) {
        try {
            if (config != null && config.isPretty()) {
                return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            }
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize console payload", e);
        }
    }
}
