package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infers a SchemaDescriptor from a CSV/JSONL/Parquet file sample.
 */
public final class FileSchemaInferrer {

    private static final int SAMPLE_LINES = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private FileSchemaInferrer() {
    }

    public static SchemaDescriptor infer(Path path, FileOptions options) {
        return infer(List.of(path), options);
    }

    /**
     * Resolves a local file resource (file, directory or glob) and infers its
     * schema with the same rules as the streaming scanner. Used by the DuckDB
     * fast path so both engines agree on column types (for example, leading-zero
     * codes stay VARCHAR instead of being coerced to numbers by DuckDB).
     */
    public static SchemaDescriptor infer(ResourceLocator resource, ReadOptions readOptions) {
        String location = resource == null ? null
                : resource.getPath() != null && !resource.getPath().isBlank()
                ? resource.getPath() : resource.getName();
        if (location == null || location.isBlank()) {
            throw new ConnectorException("File resource.path or resource.name is required");
        }
        if (S3Location.isS3(location)) {
            throw new ConnectorException("Schema inference supports local files only: " + location);
        }
        List<Path> resolved = FilePaths.resolveAll(location);
        FileOptions options = FileOptions.from(resource, readOptions, resolved.get(0).toString());
        if (!options.explicitFormat()) {
            options = options.withFormat(FilePaths.detectCommonFormat(resolved, location));
        }
        boolean explicitFile = Files.isRegularFile(Paths.get(location));
        return infer(FilePaths.filterByFormat(resolved, options.format(), explicitFile), options);
    }

    /**
     * Infers a schema from one or more files. Text formats use the first file
     * (partitioned datasets are expected to share a header); Parquet unions
     * columns across all files.
     */
    public static SchemaDescriptor infer(List<Path> files, FileOptions options) {
        if (files == null || files.isEmpty()) {
            throw new ConnectorException("No data files resolved");
        }
        Path path = files.get(0);
        if (!Files.isRegularFile(path)) {
            throw new ConnectorException("File does not exist: " + path);
        }
        SchemaDescriptor schema;
        if (options.format() == FileFormat.PARQUET) {
            schema = applyOverrides(ParquetSupport.inferSchema(files), options);
        } else if (options.format() == FileFormat.ORC) {
            schema = applyOverrides(OrcSupport.inferSchema(files), options);
        } else {
            schema = options.format() == FileFormat.JSONL
                    ? inferJsonl(path, options)
                    : inferCsv(path, options);
        }
        validateTypeOverrides(schema, options);
        return schema;
    }

    /** A typo in resource.types would otherwise be silently ignored. */
    private static void validateTypeOverrides(SchemaDescriptor schema, FileOptions options) {
        if (options.explicitTypes().isEmpty()) {
            return;
        }
        Set<String> columns = new LinkedHashSet<>();
        if (schema.getFields() != null) {
            for (FieldDescriptor field : schema.getFields()) {
                if (field.getName() != null) {
                    columns.add(field.getName().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        for (String column : options.explicitTypes().keySet()) {
            if (!columns.contains(column.toLowerCase(java.util.Locale.ROOT))) {
                throw new ConnectorException("types references unknown column '" + column + "'");
            }
        }
    }

    /**
     * Parquet 的列/类型来自文件本身，但允许用 {@code columns}/{@code types}
     * 覆盖推断结果（例如把 DECIMAL 对齐成 DOUBLE 再与 CSV 比对）。
     */
    private static SchemaDescriptor applyOverrides(SchemaDescriptor inferred, FileOptions options) {
        if (options.explicitColumns().isEmpty() && options.explicitTypes().isEmpty()) {
            return inferred;
        }
        Map<String, FieldDescriptor> inferredByName = new LinkedHashMap<>();
        if (inferred.getFields() != null) {
            for (FieldDescriptor field : inferred.getFields()) {
                if (field.getName() != null) {
                    inferredByName.put(field.getName().toLowerCase(java.util.Locale.ROOT), field);
                }
            }
        }
        if (!options.explicitColumns().isEmpty()) {
            for (String column : options.explicitColumns()) {
                if (!inferredByName.containsKey(column.toLowerCase(java.util.Locale.ROOT))) {
                    throw new ConnectorException("columns references unknown column '" + column + "'");
                }
            }
        }
        List<String> columns = options.explicitColumns().isEmpty()
                ? new ArrayList<>(inferred.getFieldMap().keySet())
                : new ArrayList<>(options.explicitColumns());
        List<DataType> types = new ArrayList<>();
        for (String column : columns) {
            DataType explicit = options.explicitTypes().get(column);
            if (explicit != null) {
                types.add(explicit);
                continue;
            }
            FieldDescriptor field = inferredByName.get(column.toLowerCase(java.util.Locale.ROOT));
            types.add(field == null ? DataType.VARCHAR
                    : DataType.valueOf(field.getCanonicalType().toUpperCase(java.util.Locale.ROOT)));
        }
        return descriptor(columns, types);
    }

    private static SchemaDescriptor inferCsv(Path path, FileOptions options) {
        try (BufferedReader reader = FileReaders.newBufferedReader(path, options.charset())) {
            skipLines(reader, options.skipRows());
            CsvRecordReader records = new CsvRecordReader(reader, options.delimiter());
            CsvRecordReader.Record first = records.read();
            if (first == null) {
                if (!options.explicitColumns().isEmpty()) {
                    List<DataType> types = new ArrayList<>();
                    for (String column : options.explicitColumns()) {
                        DataType explicit = options.explicitTypes().get(column);
                        types.add(explicit != null ? explicit : DataType.UNKNOWN);
                    }
                    return descriptor(options.explicitColumns(), types);
                }
                return emptySchema();
            }
            List<String> header = new ArrayList<>(first.fields());
            if (options.hasHeader()) {
                if (options.explicitColumns().isEmpty()) {
                    validateAndTrimHeader(header, path);
                } else {
                    // Explicit columns resolve duplicate/empty names by position.
                    trimHeader(header);
                }
            }
            List<String> columns = options.explicitColumns().isEmpty()
                    ? (options.hasHeader() ? header : generatedColumns(header.size()))
                    : options.explicitColumns();
            if (!options.explicitColumns().isEmpty()
                    && options.explicitColumns().size() != first.fields().size()) {
                throw new ConnectorException("resource.columns declares "
                        + options.explicitColumns().size() + " columns but the CSV "
                        + (options.hasHeader() ? "header" : "first record")
                        + " has " + first.fields().size());
            }
            List<List<String>> sample = new ArrayList<>();
            if (!options.hasHeader()) {
                // 无表头时第一行是数据：把它纳入样本
                sample.add(applyNullValue(new ArrayList<>(first.fields()), options.nullValue()));
            }
            CsvRecordReader.Record record;
            while (sample.size() < SAMPLE_LINES && (record = records.read()) != null) {
                if (!record.blank()) {
                    sample.add(applyNullValue(new ArrayList<>(record.fields()), options.nullValue()));
                }
            }
            List<DataType> types = new ArrayList<>();
            for (int index = 0; index < columns.size(); index++) {
                String column = columns.get(index);
                DataType explicit = options.explicitTypes().get(column);
                types.add(explicit != null ? explicit : inferColumn(sample, index));
            }
            return descriptor(columns, types);
        } catch (IOException exception) {
            throw new ConnectorException("Failed to infer CSV schema for " + path + ": " + exception.getMessage(), exception);
        }
    }

    private static void trimHeader(List<String> header) {
        for (int index = 0; index < header.size(); index++) {
            String name = header.get(index);
            if (name != null) {
                header.set(index, name.trim());
            }
        }
    }

    private static void validateAndTrimHeader(List<String> header, Path path) {
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < header.size(); index++) {
            String name = header.get(index);
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty()) {
                throw new ConnectorException("CSV header contains an empty column name in " + path);
            }
            if (!seen.add(trimmed.toLowerCase(java.util.Locale.ROOT))) {
                throw new ConnectorException("Duplicate CSV column name '" + trimmed + "' in " + path);
            }
            header.set(index, trimmed);
        }
    }

    /** The scanner maps nullValue to NULL; inference must do the same. */
    private static List<String> applyNullValue(List<String> values, String nullValue) {
        if (nullValue == null || values.isEmpty()) {
            return values;
        }
        for (int index = 0; index < values.size(); index++) {
            if (nullValue.equals(values.get(index))) {
                values.set(index, null);
            }
        }
        return values;
    }

    private static SchemaDescriptor inferJsonl(Path path, FileOptions options) {
        List<String> columns = new ArrayList<>(options.explicitColumns());
        Map<String, DataType> inferred = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        try (BufferedReader reader = FileReaders.newBufferedReader(path, options.charset())) {
            skipLines(reader, options.skipRows());
            String line;
            int count = 0;
            int lineNumber = 0;
            while (count < SAMPLE_LINES && (line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                count++;
                JsonNode node = MAPPER.readTree(line);
                if (!node.isObject()) {
                    throw new ConnectorException("JSONL line " + lineNumber + " in " + path
                            + " is not a JSON object");
                }
                node.fields().forEachRemaining(entry -> {
                    seen.add(entry.getKey());
                    inferred.merge(entry.getKey(), inferJsonType(entry.getValue()), FileSchemaInferrer::mergeTypes);
                });
            }
        } catch (IOException exception) {
            throw new ConnectorException("Failed to infer JSONL schema for " + path + ": " + exception.getMessage(), exception);
        }
        if (columns.isEmpty()) {
            columns.addAll(seen);
        }
        List<DataType> types = new ArrayList<>();
        for (String column : columns) {
            DataType explicit = options.explicitTypes().get(column);
            types.add(explicit != null ? explicit : inferred.getOrDefault(column, DataType.UNKNOWN));
        }
        return descriptor(columns, types);
    }

    private static SchemaDescriptor descriptor(List<String> columns, List<DataType> types) {
        List<FieldDescriptor> fields = new ArrayList<>();
        Map<String, FieldDescriptor> fieldMap = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            FieldDescriptor field = FieldDescriptor.builder()
                    .name(columns.get(index))
                    .canonicalType(types.get(index).name())
                    .nullable(false)
                    .ordinal(index + 1)
                    .build();
            fields.add(field);
            fieldMap.put(field.getName(), field);
        }
        return SchemaDescriptor.builder().fields(fields).fieldMap(fieldMap).build();
    }

    private static SchemaDescriptor emptySchema() {
        return SchemaDescriptor.builder().fields(List.of()).fieldMap(Map.of()).build();
    }

    private static List<String> generatedColumns(int count) {
        List<String> columns = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            columns.add("column_" + (index + 1));
        }
        return columns;
    }

    private static DataType inferColumn(List<List<String>> sample, int index) {
        boolean allBoolean = true;
        boolean allLong = true;
        boolean allDouble = true;
        boolean anyValue = false;
        for (List<String> row : sample) {
            String raw = index < row.size() ? row.get(index) : null;
            String value = raw == null ? null : raw.trim();
            if (value == null || value.isEmpty()) {
                continue;
            }
            anyValue = true;
            allBoolean &= isBoolean(value);
            allLong &= isLong(value);
            allDouble &= isDouble(value);
        }
        if (!anyValue) {
            return DataType.UNKNOWN;
        }
        // 数字 0/1 同时满足 boolean 与 long：优先整数，避免把 id=1 的单行样本
        // 推断成 BOOLEAN，导致后续 2/3 等值无法按整数归一化。
        if (allLong) {
            return DataType.BIGINT;
        }
        if (allBoolean) {
            return DataType.BOOLEAN;
        }
        if (allDouble) {
            return DataType.DOUBLE;
        }
        return DataType.VARCHAR;
    }

    private static boolean isBoolean(String value) {
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)
                || "1".equals(value) || "0".equals(value)
                || "y".equalsIgnoreCase(value) || "n".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value);
    }

    private static boolean isLong(String value) {
        if (value.startsWith("+") || value.startsWith("-")) {
            value = value.substring(1);
        }
        // 前导零视为文本（电话号码/编码），避免 007 变成 7
        if (value.isEmpty() || (value.length() > 1 && value.charAt(0) == '0')) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean isDouble(String value) {
        // 前导零的整数串按文本处理（007/00123 不应变成 7/123.0）
        String unsigned = value.startsWith("+") || value.startsWith("-") ? value.substring(1) : value;
        if (unsigned.length() > 1 && unsigned.charAt(0) == '0' && unsigned.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static DataType inferJsonType(JsonNode node) {
        if (node == null || node.isNull()) {
            return DataType.UNKNOWN;
        }
        if (node.isBoolean()) {
            return DataType.BOOLEAN;
        }
        if (node.isIntegralNumber()) {
            return DataType.BIGINT;
        }
        if (node.isFloatingPointNumber()) {
            return DataType.DOUBLE;
        }
        return DataType.VARCHAR;
    }

    private static DataType mergeTypes(DataType left, DataType right) {
        if (left == DataType.UNKNOWN) {
            return right;
        }
        if (right == DataType.UNKNOWN) {
            return left;
        }
        if (left == right) {
            return left;
        }
        // 数值提升：BIGINT + DOUBLE → DOUBLE；其它混合一律按文本
        if ((left == DataType.BIGINT && right == DataType.DOUBLE)
                || (left == DataType.DOUBLE && right == DataType.BIGINT)) {
            return DataType.DOUBLE;
        }
        return DataType.VARCHAR;
    }

    private static void skipLines(BufferedReader reader, int count) {
        try {
            for (int index = 0; index < count; index++) {
                if (reader.readLine() == null) {
                    return;
                }
            }
        } catch (IOException exception) {
            throw new ConnectorException("Failed to skip file rows: " + exception.getMessage(), exception);
        }
    }
}
