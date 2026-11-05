package com.consilens.sink.csv;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvDiffRecordSinkTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRemoveStaleWithinToleranceReportOnReopen() throws Exception {
        Path output = tempDir.resolve("stale.csv");
        Path sibling = tempDir.resolve("stale.within-tolerance.csv");
        Files.writeString(sibling, "MISMATCH,1,[1, 10.5],[1, 10.504]\n", StandardCharsets.UTF_8);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", output.toString());
        SinkConfig config = new SinkConfig();
        config.setFormat("csv");
        config.setType("diff-record");
        config.setProperties(new ObjectMapper().writeValueAsString(properties));

        CsvDiffRecordSink sink = new CsvDiffRecordSink();
        try {
            sink.open(config, DiffContext.builder().taskId("csv-stale").build());
        } finally {
            sink.close();
        }

        assertTrue(!Files.exists(sibling), "重跑后不应残留上一轮的容差内报告");
    }

    @Test
    void shouldWriteWithinToleranceRowsToSiblingFile() throws Exception {
        Path output = tempDir.resolve("orders.csv");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", output.toString());

        SinkConfig config = new SinkConfig();
        config.setFormat("csv");
        config.setType("diff-record");
        config.setProperties(new ObjectMapper().writeValueAsString(properties));

        DiffContext context = DiffContext.builder().taskId("csv-within-tolerance").build();
        DiffRow within = DiffRow.withinTolerance(List.of(1),
                List.of("1", "10.500000"), List.of("1", "10.504000"),
                List.of("id", "amount"), List.of("id", "amount"),
                List.of("amount"), List.of("amount"), Map.of("amount", 0.004));

        CsvDiffRecordSink sink = new CsvDiffRecordSink();
        try {
            sink.open(config, context);
            sink.onWithinToleranceRecords(List.of(within), context);
        } finally {
            sink.close();
        }

        Path sibling = tempDir.resolve("orders.within-tolerance.csv");
        assertTrue(Files.exists(sibling), "容差内行应写到旁挂文件，而不是混进差异文件");
        String content = new String(Files.readAllBytes(sibling), StandardCharsets.UTF_8);
        assertTrue(content.contains("operation,primaryKey,sourceValues,targetValues"), content);
        assertTrue(content.contains("MISMATCH"), content);
        // 主文件只应有表头：容差内行不能算进差异文件
        String main = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertTrue(!main.contains("MISMATCH"), main);
    }

    @Test
    void shouldDefuseFormulaLookingCellsButKeepNumbersIntact() throws Exception {
        Path output = tempDir.resolve("formula.csv");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", output.toString());
        properties.put("columns", List.of(
                Map.of("name", "formula", "value", "${src.c1}"),
                Map.of("name", "number", "value", "${src.c2}"),
                Map.of("name", "negative", "value", "${src.c3}")));

        SinkConfig config = new SinkConfig();
        config.setFormat("csv");
        config.setType("diff-record");
        config.setProperties(new ObjectMapper().writeValueAsString(properties));

        DiffContext context = DiffContext.builder().taskId("csv-formula").build();
        DiffRow row = DiffRow.modified(List.of(1),
                List.of("=cmd|'/c calc'!A1", "42", "-12.5"),
                List.of("=cmd|'/c calc'!A1", "42", "-12.5"),
                List.of("c1", "c2", "c3"), List.of("c1", "c2", "c3"));

        CsvDiffRecordSink sink = new CsvDiffRecordSink();
        try {
            sink.open(config, context);
            sink.onDiffRecords(List.of(row), context);
        } finally {
            sink.close();
        }

        String content = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        // 以 = 开头的单元格必须加前导单引号，避免被 Excel 当公式执行
        assertTrue(content.contains("'=cmd|'/c calc'!A1"), content);
        // 合法数字（含负数）不能被改写成文本
        assertTrue(content.contains(",42,-12.5"), content);
    }

    @Test
    void shouldEscapeHeaderAndQuoteCarriageReturnValues() throws Exception {
        Path output = tempDir.resolve("diff.csv");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", output.toString());
        properties.put("columns", List.of(
                Map.of("name", "a,b", "value", "${src.c1}"),
                Map.of("name", "x\"y", "value", "${src.c2}")));

        SinkConfig config = new SinkConfig();
        config.setFormat("csv");
        config.setType("diff-record");
        config.setProperties(new ObjectMapper().writeValueAsString(properties));

        DiffContext context = DiffContext.builder().taskId("csv-escape").build();
        DiffRow row = DiffRow.modified(List.of(1),
                List.of("line1\rline2", "a\"b"),
                List.of("line1\rline2", "a\"b"),
                List.of("c1", "c2"), List.of("c1", "c2"));

        CsvDiffRecordSink sink = new CsvDiffRecordSink();
        try {
            sink.open(config, context);
            sink.onDiffRecords(List.of(row), context);
        } finally {
            sink.close();
        }

        String content = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        // 表头中的逗号/引号必须转义，否则列数会错位
        assertTrue(content.startsWith("\"a,b\",\"x\"\"y\"\n"), content);
        // 仅含 \r 的值也必须整体加引号，不能把一行拆成两行
        assertTrue(content.contains("\"line1\rline2\",\"a\"\"b\""), content);
    }
}
