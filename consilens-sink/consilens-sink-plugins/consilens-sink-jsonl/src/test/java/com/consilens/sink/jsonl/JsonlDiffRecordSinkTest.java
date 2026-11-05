package com.consilens.sink.jsonl;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlDiffRecordSinkTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private DiffContext context() {
        return DiffContext.builder().taskId("t-1").build();
    }

    private JsonlDiffRecordSink openSink(Path path) throws Exception {
        JsonlDiffRecordSink sink = new JsonlDiffRecordSink();
        sink.open(SinkConfig.builder()
                .format("jsonl")
                .type("diff-record")
                .properties(OBJECT_MAPPER.writeValueAsString(Map.of("path", path.toString())))
                .build(), context());
        return sink;
    }

    @Test
    void shouldWriteColumnValuesForMismatchRow() throws Exception {
        Path output = tempDir.resolve("diff.jsonl");
        JsonlDiffRecordSink sink = openSink(output);
        sink.onDiffRecords(List.of(DiffRow.modified(
                List.of(1),
                List.of("Alice", "100.00"),
                List.of("Alicia", "101.00"),
                List.of("name", "amount"),
                List.of("name", "amount"),
                List.of("name"),
                List.of("name"))), context());
        sink.close();

        JsonNode record = OBJECT_MAPPER.readTree(Files.readString(output));
        assertEquals("mismatch", record.path("operation").asText());
        assertEquals(List.of("name", "amount"),
                OBJECT_MAPPER.convertValue(record.path("columnNames"), List.class));
        assertEquals(List.of("Alice", "100.00"),
                OBJECT_MAPPER.convertValue(record.path("sourceValues"), List.class));
        assertEquals(List.of("Alicia", "101.00"),
                OBJECT_MAPPER.convertValue(record.path("targetValues"), List.class));
        assertTrue(record.path("metadata").path("changedColumns1").isArray());
    }

    @Test
    void shouldWriteOnlyAvailableValuesForAddAndRemoveRows() throws Exception {
        Path output = tempDir.resolve("add-remove.jsonl");
        JsonlDiffRecordSink sink = openSink(output);
        sink.onDiffRecords(List.of(
                // 新增行（目标有、源无）：只有 targetValues
                DiffRow.added(List.of(2), List.of("Bob", "200.00"), List.of("name", "amount")),
                // 删除行（源有、目标无）：只有 sourceValues
                DiffRow.removed(List.of(3), List.of("Carol", "300.00"), List.of("name", "amount"))),
                context());
        sink.close();

        List<String> lines = Files.readAllLines(output);
        assertEquals(2, lines.size());

        JsonNode added = OBJECT_MAPPER.readTree(lines.get(0));
        assertEquals("source_missing", added.path("operation").asText());
        assertEquals(List.of("name", "amount"),
                OBJECT_MAPPER.convertValue(added.path("columnNames"), List.class));
        assertFalse(added.has("sourceValues"));
        assertEquals(List.of("Bob", "200.00"),
                OBJECT_MAPPER.convertValue(added.path("targetValues"), List.class));

        JsonNode removed = OBJECT_MAPPER.readTree(lines.get(1));
        assertEquals("target_missing", removed.path("operation").asText());
        assertEquals(List.of("Carol", "300.00"),
                OBJECT_MAPPER.convertValue(removed.path("sourceValues"), List.class));
        assertFalse(removed.has("targetValues"));
    }
}
