package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSchemaInferrerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldInferCsvTypesFromHeaderAndSample() throws Exception {
        Path csv = tempDir.resolve("data.csv");
        Files.writeString(csv, "id,name,amount,active\n"
                + "1,alice,10.5,true\n"
                + "2,bob,20.0,false\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString()).build(), null);

        SchemaDescriptor schema = FileSchemaInferrer.infer(csv, options);

        assertEquals(DataType.BIGINT, type(schema, "id"));
        assertEquals(DataType.VARCHAR, type(schema, "name"));
        assertEquals(DataType.DOUBLE, type(schema, "amount"));
        assertEquals(DataType.BOOLEAN, type(schema, "active"));
    }

    @Test
    void shouldTreatLeadingZeroAsText() throws Exception {
        Path csv = tempDir.resolve("codes.csv");
        Files.writeString(csv, "code\n007\n008\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString()).build(), null);

        SchemaDescriptor schema = FileSchemaInferrer.infer(csv, options);

        assertEquals(DataType.VARCHAR, type(schema, "code"));
    }

    @Test
    void shouldInferJsonlUnionOfKeys() throws Exception {
        Path jsonl = tempDir.resolve("data.jsonl");
        Files.writeString(jsonl, "{\"id\":1,\"name\":\"alice\"}\n"
                + "{\"id\":2,\"amount\":20.5}\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(jsonl.toString()).build(), null);

        SchemaDescriptor schema = FileSchemaInferrer.infer(jsonl, options);

        assertEquals(DataType.BIGINT, type(schema, "id"));
        assertEquals(DataType.VARCHAR, type(schema, "name"));
        assertEquals(DataType.DOUBLE, type(schema, "amount"));
    }

    @Test
    void shouldAllowDuplicateHeaderWithExplicitColumns() throws Exception {
        Path csv = tempDir.resolve("duplicate-header-explicit.csv");
        Files.writeString(csv, "id,name,name\n1,a,b\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString())
                .options(Map.of("columns", "id,name1,name2"))
                .build(), null);

        SchemaDescriptor schema = FileSchemaInferrer.infer(csv, options);

        assertEquals(DataType.BIGINT, type(schema, "id"));
        assertEquals(DataType.VARCHAR, type(schema, "name1"));
        assertEquals(DataType.VARCHAR, type(schema, "name2"));
    }

    @Test
    void shouldRejectDuplicateCsvHeaderNames() throws Exception {
        Path csv = tempDir.resolve("duplicate-header.csv");
        Files.writeString(csv, "id,name,name\n1,a,b\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString()).build(), null);

        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> FileSchemaInferrer.infer(csv, options));
        assertTrue(exception.getMessage().contains("Duplicate CSV column name"));
    }

    @Test
    void shouldRejectEmptyCsvHeaderName() throws Exception {
        Path csv = tempDir.resolve("empty-header.csv");
        Files.writeString(csv, "id,\n1,a\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString()).build(), null);

        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> FileSchemaInferrer.infer(csv, options));
        assertTrue(exception.getMessage().contains("empty column name"));
    }

    @Test
    void shouldTrimCsvHeaderNames() throws Exception {
        Path csv = tempDir.resolve("spaced.csv");
        Files.writeString(csv, " id , name \n1,alice\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString()).build(), null);

        SchemaDescriptor schema = FileSchemaInferrer.infer(csv, options);

        assertEquals(DataType.BIGINT, type(schema, "id"));
        assertEquals(DataType.VARCHAR, type(schema, "name"));
    }

    @Test
    void shouldTreatConfiguredNullValueAsNullDuringInference() throws Exception {
        Path csv = tempDir.resolve("null-value.csv");
        Files.writeString(csv, "id,amount\n1,10\n2,NULL\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString())
                .options(Map.of("nullValue", "NULL"))
                .build(), null);

        SchemaDescriptor schema = FileSchemaInferrer.infer(csv, options);

        assertEquals(DataType.BIGINT, type(schema, "amount"));
    }

    @Test
    void shouldRejectUnknownTypeNameInTypes() {
        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> FileOptions.from(ResourceLocator.builder()
                        .type("file").path("unused.csv")
                        .options(Map.of("types", "amount:DECIAML"))
                        .build(), null));
        assertTrue(exception.getMessage().contains("Unsupported type 'DECIAML'"));
    }

    @Test
    void shouldRejectMalformedTypesEntry() {
        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> FileOptions.from(ResourceLocator.builder()
                        .type("file").path("unused.csv")
                        .options(Map.of("types", "amount"))
                        .build(), null));
        assertTrue(exception.getMessage().contains("expected column:TYPE"));
    }

    @Test
    void explicitTypesShouldOverrideInference() throws Exception {
        Path csv = tempDir.resolve("override.csv");
        Files.writeString(csv, "id,amount\n1,10\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString())
                .options(Map.of("types", "amount:VARCHAR"))
                .build(), null);

        SchemaDescriptor schema = FileSchemaInferrer.infer(csv, options);

        assertEquals(DataType.VARCHAR, type(schema, "amount"));
    }

    @Test
    void shouldInferExplicitColumnsForEmptyCsv() throws Exception {
        Path csv = tempDir.resolve("empty.csv");
        Files.writeString(csv, "");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString())
                .options(Map.of("columns", "id,name"))
                .build(), null);

        SchemaDescriptor schema = FileSchemaInferrer.infer(csv, options);

        assertEquals(DataType.UNKNOWN, type(schema, "id"));
        assertEquals(DataType.UNKNOWN, type(schema, "name"));
    }

    @Test
    void shouldInferUnknownForEmptyJsonl() throws Exception {
        Path jsonl = tempDir.resolve("empty.jsonl");
        Files.writeString(jsonl, "");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(jsonl.toString())
                .options(Map.of("columns", "id,name"))
                .build(), null);

        SchemaDescriptor schema = FileSchemaInferrer.infer(jsonl, options);

        assertEquals(DataType.UNKNOWN, type(schema, "id"));
        assertEquals(DataType.UNKNOWN, type(schema, "name"));
    }

    @Test
    void shouldInferUnknownForHeaderOnlyCsv() throws Exception {
        Path csv = tempDir.resolve("header-only.csv");
        Files.writeString(csv, "id,name\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString()).build(), null);

        SchemaDescriptor schema = FileSchemaInferrer.infer(csv, options);

        assertEquals(DataType.UNKNOWN, type(schema, "id"));
        assertEquals(DataType.UNKNOWN, type(schema, "name"));
    }

    @Test
    void shouldRejectUnknownParquetColumnOverride() throws Exception {
        Path parquet = tempDir.resolve("data.parquet");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("COPY (SELECT 1 AS id) TO '"
                    + parquet.toAbsolutePath().toString().replace("'", "''")
                    + "' (FORMAT PARQUET)");
        }
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(parquet.toString())
                .options(Map.of("format", "parquet", "columns", "missing"))
                .build(), null);

        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> FileSchemaInferrer.infer(parquet, options));
        assertTrue(exception.getMessage().contains("unknown column"));
    }

    @Test
    void shouldRejectMismatchedExplicitColumnCount() throws Exception {
        Path csv = tempDir.resolve("column-count.csv");
        Files.writeString(csv, "id,name\n1,a\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString())
                .options(Map.of("columns", "id"))
                .build(), null);

        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> FileSchemaInferrer.infer(csv, options));
        assertTrue(exception.getMessage().contains("declares 1 columns"));
    }

    @Test
    void shouldRejectUnknownTypeOverride() throws Exception {
        Path csv = tempDir.resolve("unknown-type.csv");
        Files.writeString(csv, "id,amount\n1,10\n");
        FileOptions options = FileOptions.from(ResourceLocator.builder()
                .type("file").path(csv.toString())
                .options(Map.of("types", "amout:VARCHAR"))
                .build(), null);

        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> FileSchemaInferrer.infer(csv, options));
        assertTrue(exception.getMessage().contains("unknown column"));
    }

    @Test
    void shouldRejectDuplicateExplicitColumns() {
        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> FileOptions.from(ResourceLocator.builder()
                        .type("file").path("unused.csv")
                        .options(Map.of("columns", "id,name,name"))
                        .build(), null));
        assertTrue(exception.getMessage().contains("Duplicate column"));
    }

    private static DataType type(SchemaDescriptor schema, String column) {
        FieldDescriptor field = schema.getFieldMap().get(column);
        return DataType.valueOf(field.getCanonicalType());
    }
}
