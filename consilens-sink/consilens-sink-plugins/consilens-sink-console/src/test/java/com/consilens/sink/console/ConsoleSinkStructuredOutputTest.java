package com.consilens.sink.console;

import com.consilens.connector.api.model.TablePath;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.SegmentResult;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MappingIterator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleSinkStructuredOutputTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUpStreams() throws Exception {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8.name()));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8.name()));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void shouldPrintStructuredResultAndErrorPayloads() throws Exception {
        ConsoleResultSink sink = new ConsoleResultSink();
        DiffContext context = diffContext();

        sink.open(resultSinkConfig(), context);
        sink.onResult(diffResult(), context);
        sink.onError(context, new IllegalStateException("console sink failed", new RuntimeException("root cause")));

        JsonNode resultNode = readFirstJsonLine(out);
        assertEquals("result", resultNode.get("event").asText());
        assertEquals("task-1", resultNode.get("taskId").asText());
        assertEquals("DIFF", resultNode.get("status").asText());
        assertEquals(1, resultNode.get("statistics").get("totalDifferences").asInt());
        assertTrue(resultNode.has("summary"));
        assertFalse(resultNode.has("infoTree"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\n  \"event\""));

        JsonNode errorNode = readFirstJsonLine(err);
        assertEquals("error", errorNode.get("event").asText());
        assertEquals("java.lang.IllegalStateException", errorNode.get("error").get("type").asText());
        assertEquals("console sink failed", errorNode.get("error").get("message").asText());
        assertEquals("java.lang.RuntimeException", errorNode.get("error").get("causes").get(0).get("type").asText());
    }

    @Test
    void shouldPrintStructuredDiffRecordsAndRespectMaxRows() throws Exception {
        ConsoleDiffRecordSink sink = new ConsoleDiffRecordSink();
        DiffContext context = diffContext();
        SinkConfig config = new SinkConfig();
        config.setProperties("{\"maxRows\":1}");

        sink.open(config, context);
        sink.onDiffRecords(List.of(diffRow(), diffRow()), context);
        sink.onSegmentComplete(SegmentResult.builder()
                .segmentIndex(3)
                .sourceRowsScanned(10)
                .targetRowsScanned(10)
                .differencesFound(1)
                .totalDifferencesAccumulated(1)
                .duration(Duration.ofMillis(25))
                .context(context)
                .build());

        List<JsonNode> lines = readJsonLines(out);
        assertEquals(3, lines.size());
        assertEquals("diff-record", lines.get(0).get("event").asText());
        assertEquals("mismatch", lines.get(0).get("operation").asText());
        assertEquals("42", lines.get(0).get("primaryKeyString").asText());
        assertTrue(lines.get(0).get("metadata").has("changedColumns1"));

        assertEquals("diff-record-truncated", lines.get(1).get("event").asText());
        assertEquals(1, lines.get(1).get("maxRows").asInt());

        assertEquals("segment-complete", lines.get(2).get("event").asText());
        assertEquals(3, lines.get(2).get("segmentIndex").asInt());

        String plainOutput = out.toString(StandardCharsets.UTF_8);
        assertFalse(plainOutput.contains("[DIFF RESULT]"));
        assertFalse(plainOutput.contains("[DIFF]"));
        assertFalse(plainOutput.contains("[ConsoleDiffRecordSink]"));
    }

    private DiffContext diffContext() {
        return DiffContext.builder()
                .taskId("task-1")
                .startTime(Instant.parse("2026-05-12T08:00:00Z"))
                .sourceTablePath(TablePath.fromString("mydb.users"))
                .targetTablePath(TablePath.fromString("public.users"))
                .strategy("checksum")
                .algorithm("xor")
                .build();
    }

    private SinkConfig resultSinkConfig() {
        SinkConfig config = new SinkConfig();
        config.setProperties("{\"showStatistics\":true}");
        return config;
    }

    private DiffRow diffRow() {
        return DiffRow.modified(
                List.of(42),
                List.of("Alice", "active"),
                List.of("Alice", "inactive"),
                List.of("name", "status"),
                List.of("name", "status"),
                List.of("status"),
                List.of("status"));
    }

    private DiffResult diffResult() {
        return DiffResult.builder()
                .differences(List.of(diffRow()))
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(100)
                        .targetRowCount(100)
                        .sourceMissingCount(0)
                        .targetMissingCount(0)
                        .mismatchCount(1)
                        .unchangedCount(99)
                        .totalDifferences(1)
                        .differencePercentage(0.01d)
                        .processingTimeMs(12L)
                        .build())
                .infoTree(Optional.empty())
                .completedAt(Instant.parse("2026-05-12T08:00:12Z"))
                .metadata(Map.of("jobId", "job-1"))
                .sourceTablePath(TablePath.fromString("mydb.users"))
                .targetTablePath(TablePath.fromString("public.users"))
                .build();
    }

    private JsonNode readFirstJsonLine(ByteArrayOutputStream stream) throws Exception {
        return readJsonLines(stream).get(0);
    }

    private List<JsonNode> readJsonLines(ByteArrayOutputStream stream) throws Exception {
        byte[] bytes = stream.toByteArray();
        assertTrue(bytes.length > 0);
        java.util.ArrayList<JsonNode> result = new java.util.ArrayList<>();
        try (MappingIterator<JsonNode> iterator = OBJECT_MAPPER.readerFor(JsonNode.class)
                .readValues(new ByteArrayInputStream(bytes))) {
            while (iterator.hasNext()) {
                result.add(iterator.next());
            }
        }
        return result;
    }
}
