package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileConnectorIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDiffTwoCsvFilesThroughStreamingPlan() throws Exception {
        Path source = write("source.csv", "id,name,amount\n"
                + "1,alice,10.0\n"
                + "2,bob,20.0\n"
                + "3,carol,30.0\n");
        Path target = write("target.csv", "id,name,amount\n"
                + "1,alice,10.0\n"
                + "2,bob,21.0\n"
                + "3,carol,30.0\n");

        DiffResult result = new DefaultCompareRuntime().execute(request(source, target, "csv"));

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals(1L, result.getStatistics().getTotalDifferences());
        DiffRow row = result.getDifferences().get(0);
        assertEquals("2", String.valueOf(row.getPrimaryKey().get(0)));
        assertTrue(row.getChangedColumns1().contains("amount"));
    }

    @Test
    void shouldDiffTwoJsonlFiles() throws Exception {
        Path source = write("source.jsonl",
                "{\"id\":1,\"name\":\"alice\",\"amount\":10.0}\n"
                        + "{\"id\":2,\"name\":\"bob\",\"amount\":20.0}\n");
        Path target = write("target.jsonl",
                "{\"id\":1,\"name\":\"alice\",\"amount\":10.0}\n"
                        + "{\"id\":2,\"name\":\"bob\",\"amount\":25.0}\n");

        DiffResult result = new DefaultCompareRuntime().execute(request(source, target, "jsonl"));

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals("2", String.valueOf(result.getDifferences().get(0).getPrimaryKey().get(0)));
        assertTrue(result.getDifferences().get(0).getChangedColumns1().contains("amount"));
    }

    @Test
    void shouldRejectNonObjectJsonlLine() throws Exception {
        Path source = write("array-source.jsonl", "[1,\"alice\",10]\n");
        Path target = write("array-target.jsonl", "[1,\"alice\",10]\n");

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> new DefaultCompareRuntime().execute(request(source, target, "jsonl")));
        assertTrue(exception.getMessage().contains("not a JSON object"),
                "expected a non-object error, got: " + exception.getMessage());
    }

    @Test
    void shouldSkipLeadingLinesInJsonl() throws Exception {
        Path source = write("skip-source.jsonl",
                "#meta\n{\"id\":1,\"name\":\"alice\",\"amount\":10.0}\n");
        Path target = write("skip-target.jsonl",
                "#meta\n{\"id\":1,\"name\":\"alice\",\"amount\":11.0}\n");
        Map<String, Object> options = Map.of("format", "jsonl", "skipRows", 1);
        CompareRequest request = CompareRequest.builder()
                .source(configWithOptions("source", source, options))
                .target(configWithOptions("target", target, options))
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .sourceComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .targetComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .build();

        DiffResult result = new DefaultCompareRuntime().execute(request);

        assertEquals(1L, result.getStatistics().getMismatchCount());
    }

    @Test
    void shouldDiffGzipCsvAgainstPlainCsv() throws Exception {
        Path source = tempDir.resolve("source.csv.gz");
        try (java.util.zip.GZIPOutputStream out =
                     new java.util.zip.GZIPOutputStream(Files.newOutputStream(source))) {
            out.write(("id,name,amount\n"
                    + "1,alice,10.0\n"
                    + "2,bob,20.0\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Path target = write("target-plain.csv", "id,name,amount\n"
                + "1,alice,10.0\n"
                + "2,bob,25.0\n");

        DiffResult result = new DefaultCompareRuntime().execute(request(source, target, "csv"));

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertTrue(result.getDifferences().get(0).getChangedColumns1().contains("amount"));
    }

    @Test
    void shouldIgnoreUtf8BomInCsv() throws Exception {
        Path source = tempDir.resolve("bom-source.csv");
        try (java.io.OutputStream out = Files.newOutputStream(source)) {
            out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            out.write(("id,name,amount\n"
                    + "1,alice,10.0\n"
                    + "2,bob,20.0\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Path target = write("target-bom.csv", "id,name,amount\n"
                + "1,alice,10.0\n"
                + "2,bob,21.0\n");

        DiffResult result = new DefaultCompareRuntime().execute(request(source, target, "csv"));

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals("2", String.valueOf(result.getDifferences().get(0).getPrimaryKey().get(0)));
    }

    @Test
    void shouldTrimCsvHeaderSpaces() throws Exception {
        Path source = write("space-source.csv", " id , name , amount\n1,alice,10\n2,bob,20\n");
        Path target = write("space-target.csv", " id , name , amount\n1,alice,10\n2,robert,20\n");

        DiffResult result = new DefaultCompareRuntime().execute(request(source, target, "csv"));

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals("2", String.valueOf(result.getDifferences().get(0).getPrimaryKey().get(0)));
    }

    @Test
    void shouldRejectDuplicateJsonKeys() throws Exception {
        Path source = write("dupkey-source.jsonl", "{\"id\":1,\"v\":\"a\",\"v\":\"b\"}\n");
        Path target = write("dupkey-target.jsonl", "{\"id\":1,\"v\":\"a\"}\n");

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> new DefaultCompareRuntime().execute(request(source, target, "jsonl")));
        assertTrue(exception.getMessage().toLowerCase().contains("duplicate"),
                "expected a duplicate-key error, got: " + exception.getMessage());
    }

    @Test
    void shouldRejectDuplicateCsvHeaders() throws Exception {
        Path source = write("dup-header-source.csv", "id,name,name\n1,a,b\n");
        Path target = write("dup-header-target.csv", "id,name,name\n1,a,b\n");

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> new DefaultCompareRuntime().execute(request(source, target, "csv")));
        assertTrue(exception.getMessage().contains("Duplicate CSV column name"),
                "expected a duplicate-header error, got: " + exception.getMessage());
    }

    @Test
    void shouldParseMultilineQuotedCsvField() throws Exception {
        Path source = write("multi-source.csv", "id,name,amount\n1,\"hello\nworld\",10\n");
        Path target = write("multi-target.csv", "id,name,amount\n1,\"hello\nworld\",11\n");

        DiffResult result = new DefaultCompareRuntime().execute(request(source, target, "csv"));

        assertEquals(1L, result.getStatistics().getSourceRowCount());
        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals("1", String.valueOf(result.getDifferences().get(0).getPrimaryKey().get(0)));
    }

    @Test
    void shouldDiffCsvDirectoryAgainstCsvFile() throws Exception {
        Path sourceDir = tempDir.resolve("source-parts");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("part-01.csv"), "id,name,amount\n1,alice,10.0\n");
        Files.writeString(sourceDir.resolve("part-02.csv"), "id,name,amount\n2,bob,20.0\n");
        Path target = write("target-dir.csv", "id,name,amount\n1,alice,10.0\n2,bob,25.0\n");

        DiffResult result = new DefaultCompareRuntime().execute(
                requestByLocation(sourceDir.toString(), "csv", target.toString(), "csv"));

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals("2", String.valueOf(result.getDifferences().get(0).getPrimaryKey().get(0)));
    }

    @Test
    void shouldRejectMixedFormatsInDirectoryWithoutExplicitFormat() throws Exception {
        Path dir = tempDir.resolve("mixed-formats");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.csv"), "id,name,amount\n1,alice,10.0\n");
        Files.writeString(dir.resolve("b.jsonl"), "{\"id\":2,\"name\":\"bob\",\"amount\":20.0}\n");
        Path target = write("target-mixed.csv", "id,name,amount\n1,alice,10.0\n");

        CompareRequest request = CompareRequest.builder()
                .source(configWithoutFormat("source", dir))
                .target(config("target", target, "csv"))
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .sourceComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .targetComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .build();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> new DefaultCompareRuntime().execute(request));
        assertTrue(exception.getMessage().contains("Multiple file formats"),
                "expected a mixed-format error, got: " + exception.getMessage());
    }

    @Test
    void shouldInferSingleFormatInDirectoryWithoutExplicitFormat() throws Exception {
        Path dir = tempDir.resolve("jsonl-parts");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("part-01.jsonl"),
                "{\"id\":1,\"name\":\"alice\",\"amount\":10.0}\n");
        Files.writeString(dir.resolve("part-02.jsonl"),
                "{\"id\":2,\"name\":\"bob\",\"amount\":20.0}\n");
        Path target = write("target-jsonl.jsonl",
                "{\"id\":1,\"name\":\"alice\",\"amount\":10.0}\n"
                        + "{\"id\":2,\"name\":\"bob\",\"amount\":25.0}\n");

        CompareRequest request = CompareRequest.builder()
                .source(configWithoutFormat("source", dir))
                .target(config("target", target, "jsonl"))
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .sourceComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .targetComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .build();

        DiffResult result = new DefaultCompareRuntime().execute(request);

        assertEquals(1L, result.getStatistics().getMismatchCount());
    }

    @Test
    void shouldDiffParquetGlobAgainstCsv() throws Exception {
        Path parts = tempDir.resolve("parquet-parts");
        Files.createDirectories(parts);
        writeParquet(parts.resolve("part-01.parquet"),
                "SELECT 1 AS id, 'alice' AS name, CAST(10.0 AS DOUBLE) AS amount");
        writeParquet(parts.resolve("part-02.parquet"),
                "SELECT 2 AS id, 'bob' AS name, CAST(20.0 AS DOUBLE) AS amount");
        Path target = write("target-glob.csv", "id,name,amount\n1,alice,10.0\n2,bob,25.0\n");

        DiffResult result = new DefaultCompareRuntime().execute(
                requestByLocation(parts + "/*.parquet", "parquet", target.toString(), "csv"));

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals("2", String.valueOf(result.getDifferences().get(0).getPrimaryKey().get(0)));
    }

    @Test
    void shouldInferParquetSchemaAcrossFiles() throws Exception {
        Path parts = tempDir.resolve("parquet-union");
        Files.createDirectories(parts);
        writeParquet(parts.resolve("part-01.parquet"), "SELECT 1 AS id, 'alice' AS name");
        writeParquet(parts.resolve("part-02.parquet"),
                "SELECT 2 AS id, CAST(20.0 AS DOUBLE) AS amount");

        var schema = FileSchemaInferrer.infer(java.util.List.of(
                        parts.resolve("part-01.parquet"), parts.resolve("part-02.parquet")),
                FileOptions.from(ResourceLocator.builder()
                        .type("file").path(parts.toString())
                        .options(java.util.Map.of("format", "parquet")).build(), null));

        assertEquals(3, schema.getFields().size());
        assertEquals("DOUBLE", schema.getFieldMap().get("amount").getCanonicalType());
    }

    @Test
    void shouldReadParquetValues() throws Exception {
        Path source = writeParquet("values.parquet",
                "SELECT 1 AS id, 'alice' AS name, CAST(10.5 AS DOUBLE) AS amount");

        var schema = FileSchemaInferrer.infer(source, FileOptions.from(ResourceLocator.builder()
                .type("file").path(source.toString()).build(), null));
        var records = ParquetSupport.load(java.util.List.of(source), schema);

        assertEquals(1, records.size());
        assertEquals(1L, records.get(0).getValues().get("id").getValue());
        assertEquals("alice", records.get(0).getValues().get("name").getValue());
        assertEquals(10.5d, records.get(0).getValues().get("amount").getValue());
    }

    @Test
    void shouldOverrideInferredParquetType() throws Exception {
        Path source = writeParquet("decimal.parquet", "SELECT 1 AS id, 10.5 AS amount");

        var schema = FileSchemaInferrer.infer(source, FileOptions.from(ResourceLocator.builder()
                .type("file")
                .path(source.toString())
                .options(java.util.Map.of("types", "amount:DOUBLE"))
                .build(), null));

        assertEquals("DECIMAL", FileSchemaInferrer.infer(source, FileOptions.from(
                ResourceLocator.builder().type("file").path(source.toString()).build(), null))
                .getFieldMap().get("amount").getCanonicalType());
        assertEquals("DOUBLE", schema.getFieldMap().get("amount").getCanonicalType());
    }

    @Test
    void shouldDiffParquetAgainstCsv() throws Exception {
        Path source = writeParquet("source.parquet",
                "SELECT 1 AS id, 'alice' AS name, CAST(10.0 AS DOUBLE) AS amount "
                        + "UNION ALL SELECT 2, 'bob', CAST(20.0 AS DOUBLE) "
                        + "UNION ALL SELECT 3, 'carol', CAST(30.0 AS DOUBLE)");
        Path target = write("target.csv", "id,name,amount\n"
                + "1,alice,10.0\n"
                + "2,bob,21.0\n"
                + "3,carol,30.0\n");

        DiffResult result = new DefaultCompareRuntime()
                .execute(request(source, "parquet", target, "csv"));

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals("2", String.valueOf(result.getDifferences().get(0).getPrimaryKey().get(0)));
        assertTrue(result.getDifferences().get(0).getChangedColumns1().contains("amount"));
    }

    @Test
    void shouldHandleFilePathResourceName() throws Exception {
        Path source = write("name-source.csv", "id,name,amount\n1,alice,10.0\n");
        Path target = write("name-target.csv", "id,name,amount\n1,alice,11.0\n");

        CompareRequest request = CompareRequest.builder()
                .source(configByName("source", source, "csv"))
                .target(configByName("target", target, "csv"))
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .sourceComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .targetComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .build();

        DiffResult result = new DefaultCompareRuntime().execute(request);

        assertEquals(1L, result.getStatistics().getMismatchCount());
    }

    @Test
    void shouldKeepDecimalPrecisionBeyondDoubleRange() throws Exception {
        Path source = write("decimal-source.csv",
                "id,amount\n1,12345678901234567890.001\n2,1.50\n");
        Path target = write("decimal-target.csv",
                "id,amount\n1,12345678901234567890.002\n2,1.5\n");

        // CSV 推断会把小数认成 DOUBLE，这里按表结构显式声明 DECIMAL（与 Parquet/库侧一致）
        DiffResult result = new DefaultCompareRuntime().execute(
                com.consilens.connector.api.planner.CompareRequest.builder()
                        .source(decimalConfig("source", source))
                        .target(decimalConfig("target", target))
                        .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                        .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                        .sourceComparisons(ComparisonSpec.builder().fields(List.of("amount")).build())
                        .targetComparisons(ComparisonSpec.builder().fields(List.of("amount")).build())
                        .build());

        // 20 位有效数字的定点数：经 double 解析后两者的差异会被抹平，只剩 1.50/1.5 的等值
        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals(List.of("amount"), result.getDifferences().get(0).getChangedColumns1());
    }

    @Test
    void shouldReadBinaryNestedAndStructOrcColumns() throws Exception {
        Path path = tempDir.resolve("nested.orc");
        writeOrcWithNestedColumns(path);

        var schema = com.consilens.connector.api.model.SchemaDescriptor.builder()
                .fields(List.of(
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("id").canonicalType("BIGINT").build(),
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("payload").canonicalType("BLOB").build(),
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("tags").canonicalType("VARCHAR").build(),
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("attrs").canonicalType("VARCHAR").build(),
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("info").canonicalType("VARCHAR").build()))
                .build();

        var records = OrcSupport.load(List.of(path), schema);

        assertEquals(1, records.size());
        var values = records.get(0).getValues();
        assertEquals(1L, values.get("id").getValue());
        // BLOB 必须是字节，嵌套列必须渲染成稳定文本：盲转 LongColumnVector 会直接 ClassCastException
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) values.get("payload").getValue());
        // 字符串要带引号：否则 ["a,b"] 与 ["a","b"] 会渲染成同一串（这些文本参与判等）
        assertEquals("[\"a\",\"b\"]", values.get("tags").getValue());
        assertEquals("{\"k\":7}", values.get("attrs").getValue());
        assertEquals("{\"score\":5,\"label\":\"x\"}", values.get("info").getValue());
    }

    @Test
    void shouldRenderDistinctNestedOrcValuesDistinctly() throws Exception {
        Path path = tempDir.resolve("nested-edge.orc");
        writeOrcNestedEdgeCases(path);

        var schema = com.consilens.connector.api.model.SchemaDescriptor.builder()
                .fields(List.of(
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("id").canonicalType("BIGINT").build(),
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("one_value_with_comma").canonicalType("VARCHAR").build(),
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("two_values").canonicalType("VARCHAR").build(),
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("nulls").canonicalType("VARCHAR").build(),
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("literal_null").canonicalType("VARCHAR").build(),
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("with_null_key").canonicalType("VARCHAR").build()))
                .build();

        var values = OrcSupport.load(List.of(path), schema).get(0).getValues();

        // 含逗号的单元素 vs 两个元素：渲染结果必须不同，否则不同数据会被判等（静默漏报）
        assertNotEquals(values.get("one_value_with_comma").getValue(), values.get("two_values").getValue());
        assertEquals("[\"a,b\"]", values.get("one_value_with_comma").getValue());
        assertEquals("[\"a\",\"b\"]", values.get("two_values").getValue());
        // null 元素 vs 字符串 "null"
        assertNotEquals(values.get("nulls").getValue(), values.get("literal_null").getValue());
        assertEquals("[null]", values.get("nulls").getValue());
        assertEquals("[\"null\"]", values.get("literal_null").getValue());
        // ORC 允许 null key：不能 NPE（TreeMap 自然序不接受 null）
        assertEquals("{\"k\":2,null:1}", values.get("with_null_key").getValue());
    }

    @Test
    void shouldReadTimestampWithLocalTimeZoneInsteadOfNull() throws Exception {
        Path path = tempDir.resolve("tz.orc");
        writeOrcTimestampInstant(path);

        var schema = com.consilens.connector.api.model.SchemaDescriptor.builder()
                .fields(List.of(
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("id").canonicalType("BIGINT").build(),
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("ts").canonicalType("TIMESTAMP").build()))
                .build();

        var values = OrcSupport.load(List.of(path), schema).get(0).getValues();

        // TIMESTAMP_INSTANT 落到 default 分支会静默返回 null，整列变空
        assertNotNull(values.get("ts").getValue());
        assertEquals(String.valueOf(new java.sql.Timestamp(1704085500000L)),
                values.get("ts").getValue());
    }

    @Test
    void shouldDiffOrcFiles() throws Exception {
        Path source = tempDir.resolve("source.orc");
        Path target = tempDir.resolve("target.orc");
        writeOrc(source, new long[]{1, 2, 3},
                new String[]{"alice", "bob", "carol"}, new double[]{10, 20, 30});
        writeOrc(target, new long[]{1, 2, 3},
                new String[]{"alice", "bob", "carol"}, new double[]{10, 21, 30});

        DiffResult result = new DefaultCompareRuntime().execute(
                request(source, target, "orc"));

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertTrue(result.getDifferences().get(0).getChangedColumns1().contains("amount"));
    }

    @Test
    void shouldReadOrcDateColumnsAsDatesNotEpochDays() throws Exception {
        Path source = tempDir.resolve("dates-source.orc");
        Path target = tempDir.resolve("dates-target.csv");
        writeOrcWithDates(source, new long[]{1, 2},
                new String[]{"alice", "bob"}, new String[]{"2024-02-29", "1999-12-31"});
        Files.writeString(target, "id,name,event_date\n1,alice,2024-02-29\n2,bob,2000-01-01\n");

        com.consilens.connector.api.planner.CompareRequest request =
                com.consilens.connector.api.planner.CompareRequest.builder()
                        .source(config("source", source, "orc"))
                        .target(config("target", target, "csv"))
                        .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                        .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                        .sourceComparisons(ComparisonSpec.builder().fields(List.of("name", "event_date")).build())
                        .targetComparisons(ComparisonSpec.builder().fields(List.of("name", "event_date")).build())
                        .build();

        DiffResult result = new DefaultCompareRuntime().execute(request);

        // 只有第二行日期不同；若 ORC 的 DATE 被读成 epoch-day 整数，两行都会误报差异
        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertTrue(result.getDifferences().get(0).getChangedColumns1().contains("event_date"),
                String.valueOf(result.getDifferences().get(0).getChangedColumns1()));
    }

    @Test
    void shouldReportStreamingProgress() throws Exception {
        Path source = write("progress-source.csv", "id,name,amount\n1,alice,10.0\n2,bob,20.0\n");
        Path target = write("progress-target.csv", "id,name,amount\n1,alice,10.0\n2,bob,21.0\n");
        java.util.List<com.consilens.connector.api.planner.CompareProgress> events =
                new java.util.ArrayList<>();

        DiffResult result = new DefaultCompareRuntime().execute(
                request(source, target, "csv", events::add));

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertTrue(!events.isEmpty());
        com.consilens.connector.api.planner.CompareProgress last =
                events.get(events.size() - 1);
        assertEquals(2L, last.getSourceRows());
        assertEquals(2L, last.getTargetRows());
        assertEquals(1L, last.getDifferences());
    }

    @Test
    void shouldInferParquetSchema() throws Exception {
        Path source = writeParquet("schema.parquet",
                "SELECT 1 AS id, 'alice' AS name, CAST(10.5 AS DOUBLE) AS amount, true AS active");

        var schema = FileSchemaInferrer.infer(source,
                FileOptions.from(ResourceLocator.builder()
                        .type("file")
                        .path(source.toString())
                        .build(), null));

        assertEquals(4, schema.getFields().size());
        assertEquals("BIGINT", schema.getFieldMap().get("id").getCanonicalType());
        assertEquals("VARCHAR", schema.getFieldMap().get("name").getCanonicalType());
        assertEquals("DOUBLE", schema.getFieldMap().get("amount").getCanonicalType());
        assertEquals("BOOLEAN", schema.getFieldMap().get("active").getCanonicalType());
    }

    private Path writeParquet(String name, String select) throws Exception {
        return writeParquet(tempDir.resolve(name), select);
    }

    private Path writeParquet(Path path, String select) throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("COPY (" + select + ") TO '"
                    + path.toAbsolutePath().toString().replace("'", "''") + "' (FORMAT PARQUET)");
        }
        return path;
    }

    private void writeOrcWithNestedColumns(Path path) throws Exception {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        org.apache.orc.TypeDescription schema = org.apache.orc.TypeDescription.fromString(
                "struct<id:bigint,payload:binary,tags:array<string>,"
                        + "attrs:map<string,int>,info:struct<score:int,label:string>>");
        try (org.apache.orc.Writer writer = org.apache.orc.OrcFile.createWriter(
                new org.apache.hadoop.fs.Path(path.toUri()),
                org.apache.orc.OrcFile.writerOptions(conf).setSchema(schema))) {
            // 子向量容量与 batch size 相同：写 1 行但带 2 个数组元素，需要更大的容量
            org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch batch = schema.createRowBatch(4);
            org.apache.orc.storage.ql.exec.vector.LongColumnVector idVector =
                    (org.apache.orc.storage.ql.exec.vector.LongColumnVector) batch.cols[0];
            org.apache.orc.storage.ql.exec.vector.BytesColumnVector payloadVector =
                    (org.apache.orc.storage.ql.exec.vector.BytesColumnVector) batch.cols[1];
            org.apache.orc.storage.ql.exec.vector.ListColumnVector tagsVector =
                    (org.apache.orc.storage.ql.exec.vector.ListColumnVector) batch.cols[2];
            org.apache.orc.storage.ql.exec.vector.MapColumnVector attrsVector =
                    (org.apache.orc.storage.ql.exec.vector.MapColumnVector) batch.cols[3];
            org.apache.orc.storage.ql.exec.vector.StructColumnVector infoVector =
                    (org.apache.orc.storage.ql.exec.vector.StructColumnVector) batch.cols[4];

            idVector.vector[0] = 1;
            payloadVector.setRef(0, new byte[]{1, 2, 3}, 0, 3);

            org.apache.orc.storage.ql.exec.vector.BytesColumnVector tagChild =
                    (org.apache.orc.storage.ql.exec.vector.BytesColumnVector) tagsVector.child;
            tagsVector.offsets[0] = 0;
            tagsVector.lengths[0] = 2;
            tagChild.setRef(0, "a".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, 1);
            tagChild.setRef(1, "b".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, 1);
            tagsVector.childCount = 2;

            org.apache.orc.storage.ql.exec.vector.BytesColumnVector keyChild =
                    (org.apache.orc.storage.ql.exec.vector.BytesColumnVector) attrsVector.keys;
            org.apache.orc.storage.ql.exec.vector.LongColumnVector valueChild =
                    (org.apache.orc.storage.ql.exec.vector.LongColumnVector) attrsVector.values;
            attrsVector.offsets[0] = 0;
            attrsVector.lengths[0] = 1;
            keyChild.setRef(0, "k".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, 1);
            valueChild.vector[0] = 7;
            attrsVector.childCount = 1;

            ((org.apache.orc.storage.ql.exec.vector.LongColumnVector) infoVector.fields[0]).vector[0] = 5;
            ((org.apache.orc.storage.ql.exec.vector.BytesColumnVector) infoVector.fields[1])
                    .setRef(0, "x".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, 1);

            batch.size = 1;
            writer.addRowBatch(batch);
        }
    }

    private void writeOrcNestedEdgeCases(Path path) throws Exception {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        org.apache.orc.TypeDescription schema = org.apache.orc.TypeDescription.fromString(
                "struct<id:bigint,one_value_with_comma:array<string>,two_values:array<string>,"
                        + "nulls:array<string>,literal_null:array<string>,with_null_key:map<string,int>>");
        try (org.apache.orc.Writer writer = org.apache.orc.OrcFile.createWriter(
                new org.apache.hadoop.fs.Path(path.toUri()),
                org.apache.orc.OrcFile.writerOptions(conf).setSchema(schema))) {
            org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch batch = schema.createRowBatch(8);
            ((org.apache.orc.storage.ql.exec.vector.LongColumnVector) batch.cols[0]).vector[0] = 1;

            writeList(batch, 1, new String[]{"a,b"});
            writeList(batch, 2, new String[]{"a", "b"});
            writeList(batch, 3, new String[]{null});
            writeList(batch, 4, new String[]{"null"});

            org.apache.orc.storage.ql.exec.vector.MapColumnVector map =
                    (org.apache.orc.storage.ql.exec.vector.MapColumnVector) batch.cols[5];
            org.apache.orc.storage.ql.exec.vector.BytesColumnVector keys =
                    (org.apache.orc.storage.ql.exec.vector.BytesColumnVector) map.keys;
            org.apache.orc.storage.ql.exec.vector.LongColumnVector values =
                    (org.apache.orc.storage.ql.exec.vector.LongColumnVector) map.values;
            map.offsets[0] = 0;
            map.lengths[0] = 2;
            keys.setRef(0, "k".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, 1);
            keys.noNulls = false;
            keys.isNull[0] = false;
            keys.isNull[1] = true;
            values.vector[0] = 2;
            values.vector[1] = 1;
            map.childCount = 2;

            batch.size = 1;
            writer.addRowBatch(batch);
        }
    }

    private static void writeList(org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch batch,
                                  int column, String[] elements) {
        org.apache.orc.storage.ql.exec.vector.ListColumnVector list =
                (org.apache.orc.storage.ql.exec.vector.ListColumnVector) batch.cols[column];
        org.apache.orc.storage.ql.exec.vector.BytesColumnVector child =
                (org.apache.orc.storage.ql.exec.vector.BytesColumnVector) list.child;
        int offset = list.childCount;
        list.offsets[0] = offset;
        list.lengths[0] = elements.length;
        for (int index = 0; index < elements.length; index++) {
            if (elements[index] == null) {
                child.noNulls = false;
                child.isNull[offset + index] = true;
            } else {
                byte[] bytes = elements[index].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                child.setRef(offset + index, bytes, 0, bytes.length);
                child.isNull[offset + index] = false;
            }
        }
        list.childCount = offset + elements.length;
    }

    private void writeOrcTimestampInstant(Path path) throws Exception {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        org.apache.orc.TypeDescription schema = org.apache.orc.TypeDescription.fromString(
                "struct<id:bigint,ts:timestamp with local time zone>");
        try (org.apache.orc.Writer writer = org.apache.orc.OrcFile.createWriter(
                new org.apache.hadoop.fs.Path(path.toUri()),
                org.apache.orc.OrcFile.writerOptions(conf).setSchema(schema))) {
            org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch batch = schema.createRowBatch(1);
            ((org.apache.orc.storage.ql.exec.vector.LongColumnVector) batch.cols[0]).vector[0] = 1;
            org.apache.orc.storage.ql.exec.vector.TimestampColumnVector ts =
                    (org.apache.orc.storage.ql.exec.vector.TimestampColumnVector) batch.cols[1];
            long millis = 1704085500000L;
            ts.time[0] = millis;
            ts.set(0, new java.sql.Timestamp(millis));
            batch.size = 1;
            writer.addRowBatch(batch);
        }
    }

    private void writeOrcWithDates(Path path, long[] ids, String[] names, String[] dates) throws Exception {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        org.apache.orc.TypeDescription schema = org.apache.orc.TypeDescription.fromString(
                "struct<id:bigint,name:string,event_date:date>");
        try (org.apache.orc.Writer writer = org.apache.orc.OrcFile.createWriter(
                new org.apache.hadoop.fs.Path(path.toUri()),
                org.apache.orc.OrcFile.writerOptions(conf).setSchema(schema))) {
            org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch batch = schema.createRowBatch(ids.length);
            org.apache.orc.storage.ql.exec.vector.LongColumnVector idVector =
                    (org.apache.orc.storage.ql.exec.vector.LongColumnVector) batch.cols[0];
            org.apache.orc.storage.ql.exec.vector.BytesColumnVector nameVector =
                    (org.apache.orc.storage.ql.exec.vector.BytesColumnVector) batch.cols[1];
            org.apache.orc.storage.ql.exec.vector.LongColumnVector dateVector =
                    (org.apache.orc.storage.ql.exec.vector.LongColumnVector) batch.cols[2];
            for (int index = 0; index < ids.length; index++) {
                idVector.vector[index] = ids[index];
                byte[] name = names[index].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                nameVector.setRef(index, name, 0, name.length);
                dateVector.vector[index] = java.time.LocalDate.parse(dates[index]).toEpochDay();
            }
            batch.size = ids.length;
            writer.addRowBatch(batch);
        }
    }

    private void writeOrc(Path path, long[] ids, String[] names, double[] amounts) throws Exception {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        org.apache.orc.TypeDescription schema = org.apache.orc.TypeDescription.fromString(
                "struct<id:bigint,name:string,amount:double>");
        try (org.apache.orc.Writer writer = org.apache.orc.OrcFile.createWriter(
                new org.apache.hadoop.fs.Path(path.toUri()),
                org.apache.orc.OrcFile.writerOptions(conf).setSchema(schema))) {
            org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch batch = schema.createRowBatch(ids.length);
            org.apache.orc.storage.ql.exec.vector.LongColumnVector idVector =
                    (org.apache.orc.storage.ql.exec.vector.LongColumnVector) batch.cols[0];
            org.apache.orc.storage.ql.exec.vector.BytesColumnVector nameVector =
                    (org.apache.orc.storage.ql.exec.vector.BytesColumnVector) batch.cols[1];
            org.apache.orc.storage.ql.exec.vector.DoubleColumnVector amountVector =
                    (org.apache.orc.storage.ql.exec.vector.DoubleColumnVector) batch.cols[2];
            for (int index = 0; index < ids.length; index++) {
                idVector.vector[index] = ids[index];
                byte[] name = names[index].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                nameVector.setRef(index, name, 0, name.length);
                amountVector.vector[index] = amounts[index];
            }
            batch.size = ids.length;
            writer.addRowBatch(batch);
        }
    }

    private Path write(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private CompareRequest request(Path source, Path target, String format) {
        return request(source, format, target, format);
    }

    private CompareRequest request(Path source, Path target, String format,
                                   com.consilens.connector.api.planner.ProgressListener listener) {
        return CompareRequest.builder()
                .source(config("source", source, format))
                .target(config("target", target, format))
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .sourceComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .targetComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .executionOptions(com.consilens.connector.api.planner.CompareExecutionOptions.builder()
                        .progressListener(listener)
                        .build())
                .build();
    }

    private CompareRequest requestByLocation(String source, String sourceFormat,
                                             String target, String targetFormat) {
        return CompareRequest.builder()
                .source(config("source", source, sourceFormat))
                .target(config("target", target, targetFormat))
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .sourceComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .targetComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .build();
    }

    private ConnectorConfig config(String name, String location, String format) {
        return ConnectorConfig.builder()
                .type("file")
                .name(name)
                .resource(ResourceLocator.builder()
                        .type("file")
                        .name(location)
                        .path(location)
                        .options(java.util.Map.of("format", format))
                        .build())
                .build();
    }

    private CompareRequest request(Path source, String sourceFormat, Path target, String targetFormat) {
        return CompareRequest.builder()
                .source(config("source", source, sourceFormat))
                .target(config("target", target, targetFormat))
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .sourceComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .targetComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .build();
    }

    private ConnectorConfig configByName(String name, Path path, String format) {
        return ConnectorConfig.builder()
                .type("file")
                .name(name)
                .resource(ResourceLocator.builder()
                        .type("file")
                        .name(path.toString())
                        .options(java.util.Map.of("format", format))
                        .build())
                .build();
    }

    private ConnectorConfig configWithOptions(String name, Path path, Map<String, Object> options) {
        return ConnectorConfig.builder()
                .type("file")
                .name(name)
                .resource(ResourceLocator.builder()
                        .type("file")
                        .name(path.getFileName().toString())
                        .path(path.toString())
                        .options(options)
                        .build())
                .build();
    }

    private ConnectorConfig configWithoutFormat(String name, Path path) {
        return ConnectorConfig.builder()
                .type("file")
                .name(name)
                .resource(ResourceLocator.builder()
                        .type("file")
                        .name(path.toString())
                        .path(path.toString())
                        .build())
                .build();
    }

    private ConnectorConfig decimalConfig(String name, Path path) {
        return ConnectorConfig.builder()
                .type("file")
                .name(name)
                .resource(ResourceLocator.builder()
                        .type("file")
                        .name(path.getFileName().toString())
                        .path(path.toString())
                        .options(java.util.Map.of("format", "csv", "types", "amount:decimal"))
                        .build())
                .build();
    }

    private ConnectorConfig config(String name, Path path, String format) {
        return ConnectorConfig.builder()
                .type("file")
                .name(name)
                .resource(ResourceLocator.builder()
                        .type("file")
                        .name(path.getFileName().toString())
                        .path(path.toString())
                        .options(java.util.Map.of("format", format))
                        .build())
                .build();
    }
}
