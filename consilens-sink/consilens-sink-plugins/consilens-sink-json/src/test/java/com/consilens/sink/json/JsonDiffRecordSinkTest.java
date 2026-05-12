package com.consilens.sink.json;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.ColumnMapping;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonDiffRecordSinkTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteStableDiffRecordFieldsByDefault() throws Exception {
        Path output = tempDir.resolve("diff-records.json");
        write(output, Map.of("pretty", true), DiffRow.modified(
                List.of(1),
                List.of("Alice", "100.00"),
                List.of("Alicia", "101.00"),
                List.of("name", "amount"),
                List.of("name", "amount"),
                List.of("name", "amount"),
                List.of("name", "amount")));

        JsonNode row = firstRow(output);
        assertEquals("mismatch", row.get("operation").asText());
        assertEquals("1", row.get("primaryKey").asText());
        assertTrue(row.get("sourceValues").isArray());
        assertEquals("Alice", row.get("sourceValues").get(0).asText());
        assertEquals("100.00", row.get("sourceValues").get(1).asText());
        assertEquals("Alicia", row.get("targetValues").get(0).asText());
        assertEquals("name", row.get("columnNames1").get(0).asText());
        assertEquals("amount", row.get("columnNames2").get(1).asText());
        assertEquals("name", row.get("changedColumns1").get(0).asText());
        assertEquals("amount", row.get("changedColumns2").get(1).asText());
    }

    @Test
    void shouldWriteOnlyConfiguredColumnsInCustomMode() throws Exception {
        Path output = tempDir.resolve("custom-diff-records.json");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("columns", List.of(
                new ColumnMapping("taskId", "${taskId}", null, null),
                new ColumnMapping("operation", "${operation}", null, null),
                new ColumnMapping("srcName", "${src.name}", null, null),
                new ColumnMapping("tgtName", "${tgt.name}", null, null),
                new ColumnMapping("fallback", "${src.missing}", "n/a", null),
                new ColumnMapping("env", "production", null, null)));

        write(output, properties, DiffRow.modified(
                List.of(1),
                List.of("Alice"),
                List.of("Alicia"),
                List.of("name"),
                List.of("name")));

        JsonNode row = firstRow(output);
        assertEquals(6, row.size());
        assertEquals("task-1", row.get("taskId").asText());
        assertEquals("mismatch", row.get("operation").asText());
        assertEquals("Alice", row.get("srcName").asText());
        assertEquals("Alicia", row.get("tgtName").asText());
        assertEquals("n/a", row.get("fallback").asText());
        assertEquals("production", row.get("env").asText());
        assertFalse(row.has("primaryKey"));
    }

    @Test
    void shouldMergeDefaultFieldsWithConfiguredOverrides() throws Exception {
        Path output = tempDir.resolve("merged-diff-records.json");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mergeDefaults", true);
        properties.put("columns", List.of(
                new ColumnMapping("primaryKey", "pk-${primaryKey}", null, null),
                new ColumnMapping("taskId", "${taskId}", null, null)));

        write(output, properties, DiffRow.modified(
                List.of(1),
                List.of("Alice"),
                List.of("Alicia"),
                List.of("name"),
                List.of("name"),
                List.of("name"),
                List.of("name")));

        JsonNode row = firstRow(output);
        assertEquals("mismatch", row.get("operation").asText());
        assertEquals("pk-1", row.get("primaryKey").asText());
        assertEquals("Alice", row.get("sourceValues").get(0).asText());
        assertEquals("Alicia", row.get("targetValues").get(0).asText());
        assertEquals("name", row.get("changedColumns1").get(0).asText());
        assertEquals("task-1", row.get("taskId").asText());
    }

    private void write(Path output, Map<String, Object> properties, DiffRow row) throws Exception {
        Map<String, Object> jsonProperties = new LinkedHashMap<>(properties);
        jsonProperties.put("path", output.toString());

        SinkConfig config = new SinkConfig();
        config.setFormat("json");
        config.setType("diff-record");
        config.setProperties(OBJECT_MAPPER.writeValueAsString(jsonProperties));

        DiffContext context = DiffContext.builder().taskId("task-1").build();
        JsonDiffRecordSink sink = new JsonDiffRecordSink();
        sink.open(config, context);
        sink.onDiffRecords(List.of(row), context);
        sink.close();
    }

    private JsonNode firstRow(Path output) throws Exception {
        assertTrue(Files.exists(output));
        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(output));
        assertTrue(root.isArray());
        assertEquals(1, root.size());
        return root.get(0);
    }
}
