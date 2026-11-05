package com.consilens.cli.service;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.ResourceLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DuckDbFileSqlTest {

    @Test
    void shouldRejectExplicitOrcFormat() {
        assertThatThrownBy(() -> DuckDbFileSql.tableExpression(config("data.orc", "orc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support ORC");
    }

    @Test
    void shouldRejectOrcByExtension() {
        assertThatThrownBy(() -> DuckDbFileSql.tableExpression(config("data.orc", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support ORC");
    }

    @Test
    void shouldNormalizeNdjsonToJsonl(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.ndjson");
        Files.writeString(file, "{\"id\":1}\n");

        assertThat(DuckDbFileSql.tableExpression(config(file.toString(), "ndjson")))
                .startsWith("read_json_auto(")
                .contains("columns={'id': 'BIGINT'}");
    }

    @Test
    void shouldRejectUnsupportedFormat() {
        assertThatThrownBy(() -> DuckDbFileSql.tableExpression(config("data.avro", "avro")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support format 'avro'");
    }

    @Test
    void shouldUseParquetReader() {
        assertThat(DuckDbFileSql.tableExpression(config("data.parquet", null)))
                .startsWith("read_parquet(");
    }

    @Test
    void shouldForceVarcharForLeadingZeroColumns(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("data.csv");
        Files.writeString(csv, "id,amount\n001,10\n002,20\n");

        String sql = DuckDbFileSql.tableExpression(config(csv.toString(), "csv"));

        assertThat(sql).contains("types={'id': 'VARCHAR'}");
        assertThat(sql).doesNotContain("'amount': 'VARCHAR'");
    }

    @Test
    void shouldNotAddTypesForNumericOnlyCsv(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("numeric.csv");
        Files.writeString(csv, "id,amount\n1,10\n2,20\n");

        String sql = DuckDbFileSql.tableExpression(config(csv.toString(), "csv"));

        assertThat(sql).doesNotContain("types=");
    }

    @Test
    void shouldForceTextForExplicitTemporalTypes(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("dates.csv");
        Files.writeString(csv, "id,dt\n1,2024-01-01\n");

        String sql = DuckDbFileSql.tableExpression(configWithOptions(csv.toString(), Map.of(
                "format", "csv",
                "types", "dt:DATE")));

        assertThat(sql).contains("types={'dt': 'VARCHAR'}");
    }

    @Test
    void shouldRejectJsonlSkipRows(@TempDir Path dir) throws Exception {
        Path jsonl = dir.resolve("skip.jsonl");
        Files.writeString(jsonl, "{\"id\":1}\n");

        assertThatThrownBy(() -> DuckDbFileSql.tableExpression(configWithOptions(
                jsonl.toString(), Map.of("format", "jsonl", "skipRows", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skipRows for JSONL");
    }

    @Test
    void shouldRejectJsonlNullValue(@TempDir Path dir) throws Exception {
        Path jsonl = dir.resolve("nullstr.jsonl");
        Files.writeString(jsonl, "{\"id\":1}\n");

        assertThatThrownBy(() -> DuckDbFileSql.tableExpression(configWithOptions(
                jsonl.toString(), Map.of("format", "jsonl", "nullValue", "NULL"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullValue for JSONL");
    }

    @Test
    void shouldGenerateColumnsForJsonl(@TempDir Path dir) throws Exception {
        Path jsonl = dir.resolve("data.jsonl");
        Files.writeString(jsonl, "{\"id\":1,\"payload\":{\"a\":1}}\n");

        String sql = DuckDbFileSql.tableExpression(config(jsonl.toString(), "jsonl"));

        assertThat(sql).contains("columns={'id': 'BIGINT', 'payload': 'VARCHAR'}");
    }

    @Test
    void shouldMapGbkCharsetToGb18030(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("gbk.csv");
        Files.writeString(csv, "id,name\n1,alice\n");

        String sql = DuckDbFileSql.tableExpression(configWithOptions(csv.toString(),
                Map.of("format", "csv", "charset", "GBK")));

        assertThat(sql).contains("encoding='gb18030'");
    }

    @Test
    void shouldRejectUnsupportedCharset(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("utf32.csv");
        Files.writeString(csv, "id,name\n1,alice\n");

        assertThatThrownBy(() -> DuckDbFileSql.tableExpression(configWithOptions(csv.toString(),
                Map.of("format", "csv", "charset", "UTF-32"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support charset");
    }

    @Test
    void shouldGenerateColumnsForRenamedCsv(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("rename.csv");
        Files.writeString(csv, "id,amount\n1,10\n");

        String sql = DuckDbFileSql.tableExpression(configWithOptions(csv.toString(), Map.of(
                "format", "csv",
                "columns", java.util.List.of("user_id", "amount"))));

        assertThat(sql).contains("columns={'user_id': 'BIGINT', 'amount': 'BIGINT'}");
    }

    @Test
    void shouldGenerateColumnsForHeaderlessCsv(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("no-header.csv");
        Files.writeString(csv, "1|alice|10\n2|bob|20\n");

        String sql = DuckDbFileSql.tableExpression(configWithOptions(csv.toString(), Map.of(
                "format", "csv",
                "delimiter", "|",
                "hasHeader", false,
                "columns", java.util.List.of("id", "name", "amount"))));

        assertThat(sql).contains("header=false");
        assertThat(sql).contains("columns={'id': 'BIGINT', 'name': 'VARCHAR', 'amount': 'BIGINT'}");
    }

    @Test
    void shouldRequireColumnsForHeaderlessCsv(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("no-header-no-columns.csv");
        Files.writeString(csv, "1|alice|10\n");

        assertThatThrownBy(() -> DuckDbFileSql.tableExpression(configWithOptions(csv.toString(), Map.of(
                "format", "csv", "delimiter", "|", "hasHeader", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires resource.columns");
    }

    @Test
    void shouldUseFirstCharOfMultiCharDelimiter(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("pipe.csv");
        Files.writeString(csv, "id|name\n1|alice\n");

        String sql = DuckDbFileSql.tableExpression(configWithOptions(csv.toString(),
                Map.of("format", "csv", "delimiter", "||")));

        assertThat(sql).contains("delim='|'");
        assertThat(sql).doesNotContain("delim='||'");
    }

    @Test
    void shouldClampNegativeSkipRows(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("skip.csv");
        Files.writeString(csv, "id,name\n1,alice\n");

        String sql = DuckDbFileSql.tableExpression(configWithOptions(csv.toString(),
                Map.of("format", "csv", "skipRows", -1)));

        assertThat(sql).contains("skip=0");
    }

    @Test
    void shouldRejectParquetColumnOverrides() {
        assertThatThrownBy(() -> DuckDbFileSql.tableExpression(configWithOptions(
                "data.parquet", Map.of("format", "parquet",
                        "columns", java.util.List.of("id")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columns for Parquet");
    }

    @Test
    void shouldRejectParquetTypeOverrides() {
        assertThatThrownBy(() -> DuckDbFileSql.tableExpression(configWithOptions(
                "data.parquet", Map.of("format", "parquet",
                        "types", "amount:VARCHAR"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("types for Parquet");
    }

    @Test
    void shouldForceVarcharForJsonlTemporalTypes(@TempDir Path dir) throws Exception {
        Path jsonl = dir.resolve("dates.jsonl");
        Files.writeString(jsonl, "{\"id\":1,\"dt\":\"2024-01-01\"}\n");

        String sql = DuckDbFileSql.tableExpression(configWithOptions(jsonl.toString(),
                Map.of("format", "jsonl", "types", "dt:DATE")));

        assertThat(sql).contains("'dt': 'VARCHAR'");
    }

    @Test
    void shouldForceVarcharForHeaderlessCsvTemporalTypes(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("dates.csv");
        Files.writeString(csv, "1,2024-01-01\n");

        String sql = DuckDbFileSql.tableExpression(configWithOptions(csv.toString(), Map.of(
                "format", "csv",
                "hasHeader", false,
                "columns", java.util.List.of("id", "dt"),
                "types", "dt:DATE")));

        assertThat(sql).contains("'dt': 'VARCHAR'");
    }

    @Test
    void shouldDisableAutoDetectForEmptyCsvWithColumns(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("empty.csv");
        Files.writeString(csv, "");

        String sql = DuckDbFileSql.tableExpression(configWithOptions(csv.toString(), Map.of(
                "format", "csv",
                "hasHeader", false,
                "columns", java.util.List.of("id", "name"))));

        assertThat(sql).contains("auto_detect=false");
        assertThat(sql).contains("columns={'id': 'VARCHAR', 'name': 'VARCHAR'}");
    }

    private ConnectorConfig config(String path, String format) {
        return configWithOptions(path, format == null ? Map.of() : Map.of("format", format));
    }

    private ConnectorConfig configWithOptions(String path, Map<String, Object> options) {
        ResourceLocator locator = ResourceLocator.builder()
                .type("file")
                .path(path)
                .options(options)
                .build();
        return ConnectorConfig.builder()
                .type("file")
                .name("test")
                .resource(locator)
                .build();
    }
}
