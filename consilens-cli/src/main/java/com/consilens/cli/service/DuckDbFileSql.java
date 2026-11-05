package com.consilens.cli.service;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.file.FileSchemaInferrer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds DuckDB table expressions for local file resources. Shared by the
 * metrics pushdown and the optional DuckDB row-diff backend.
 */
public final class DuckDbFileSql {

    /**
     * Types the Java file scanner keeps as text. Temporal/JSON/binary types are
     * included because {@code FileValueParser} does not convert them, so DuckDB
     * must compare the same textual values instead of parsing them.
     */
    private static final Set<String> TEXT_TYPES = Set.of(
            "CHAR", "VARCHAR", "TEXT", "CLOB", "LONGVARCHAR",
            "DATE", "TIME", "TIMESTAMP", "DATETIME",
            "TIMESTAMP_WITH_TIMEZONE", "TIME_WITH_TIME_ZONE",
            "UUID", "JSON", "JSONB",
            "BINARY", "VARBINARY", "BLOB", "LONGBLOB");

    private DuckDbFileSql() {
    }

    public static String tableExpression(ConnectorConfig config) {
        if (config == null || !"file".equalsIgnoreCase(config.getType())) {
            throw new IllegalArgumentException("duckdb engine requires type=file");
        }
        ResourceLocator resource = config.getResource();
        if (resource == null) {
            throw new IllegalArgumentException("duckdb engine requires a file resource");
        }
        String location = resource.getPath() != null && !resource.getPath().isBlank()
                ? resource.getPath() : resource.getName();
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("file resource requires path or name");
        }
        if (location.toLowerCase(Locale.ROOT).startsWith("s3://")
                || location.toLowerCase(Locale.ROOT).startsWith("s3a://")) {
            throw new IllegalArgumentException("duckdb engine does not support S3;"
                    + " use the default runtime for S3 resources");
        }
        Map<String, Object> options = resource.getOptions() == null ? Map.of() : resource.getOptions();
        String format = format(location, options);
        return tableExpression(location, format, options, resource);
    }

    private static String tableExpression(String location, String format,
                                          Map<String, Object> options, ResourceLocator resource) {
        String path = location;
        if (Files.isDirectory(Paths.get(path))) {
            path = path + "/**/*." + extension(format);
        }
        String literal = literal(path);
        switch (format) {
            case "parquet":
                requireNoColumnOverrides(options);
                return "read_parquet(" + literal + ", union_by_name=true)";
            case "jsonl":
                requireNoUnsupportedJsonOptions(options);
                return "read_json_auto(" + literal + jsonColumns(resource) + ")";
            case "json":
                return "read_json_auto(" + literal + ")";
            case "csv":
            default:
                StringBuilder csv = new StringBuilder("read_csv_auto(").append(literal);
                Object delimiter = options.get("delimiter");
                String delim = duckDbDelimiter(delimiter);
                if (delim != null) {
                    csv.append(", delim=").append(literal(delim));
                }
                Object hasHeader = options.get("hasHeader");
                if (hasHeader != null) {
                    csv.append(", header=").append(Boolean.parseBoolean(String.valueOf(hasHeader)));
                }
                if (isEmptySingleFile(location) && hasExplicitColumns(options)) {
                    // Empty files cannot be sniffed; columns/header/delimiter are explicit.
                    csv.append(", auto_detect=false");
                }
                Object nullValue = options.get("nullValue");
                if (nullValue != null) {
                    csv.append(", nullstr=").append(literal(String.valueOf(nullValue)));
                }
                Object skipRows = options.get("skipRows");
                if (skipRows != null) {
                    csv.append(", skip=").append(Math.max(0,
                            Integer.parseInt(String.valueOf(skipRows))));
                }
                String encoding = duckDbEncoding(options.get("charset"));
                if (encoding != null) {
                    csv.append(", encoding=").append(literal(encoding));
                }
                if (hasExplicitColumns(options) || !hasHeader(options)) {
                    appendColumns(csv, options, resource);
                } else {
                    appendTextTypes(csv, options, resource);
                }
                return csv.append(')').toString();
        }
    }

    /**
     * DuckDB's CSV sniffer turns values such as {@code 001} into integers,
     * while the Java scanner deliberately keeps leading-zero codes as text.
     * Force VARCHAR for the columns the Java inferrer classified as strings so
     * both engines compare the same values.
     */
    private static void appendTextTypes(StringBuilder csv, Map<String, Object> options,
                                        ResourceLocator resource) {
        if (!hasHeader(options) || hasExplicitColumns(options)) {
            // Without a header, or with renamed columns, DuckDB's positional
            // column names would not match the inferred names.
            return;
        }
        SchemaDescriptor schema = FileSchemaInferrer.infer(resource, null);
        List<FieldDescriptor> fields = schema.getFields();
        if (fields == null || fields.isEmpty()) {
            return;
        }
        StringBuilder types = new StringBuilder();
        for (FieldDescriptor field : fields) {
            if (field.getName() == null || !TEXT_TYPES.contains(field.getCanonicalType())) {
                continue;
            }
            if (types.length() > 0) {
                types.append(", ");
            }
            types.append(literal(field.getName())).append(": 'VARCHAR'");
        }
        if (types.length() > 0) {
            csv.append(", types={").append(types).append('}');
        }
    }

    /**
     * Explicit columns (renamed or headerless) define the names/types DuckDB
     * must expose; without them a headerless file becomes {@code column0...}
     * and the comparison SQL cannot resolve configured keys.
     */
    private static void appendColumns(StringBuilder csv, Map<String, Object> options,
                                      ResourceLocator resource) {
        if (!hasExplicitColumns(options)) {
            throw new IllegalArgumentException("duckdb engine requires resource.columns"
                    + " when hasHeader=false; use the java engine otherwise");
        }
        String columns = columnsMap(FileSchemaInferrer.infer(resource, null));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("duckdb engine could not infer columns for "
                    + resource.getName());
        }
        csv.append(columns);
    }

    /**
     * {@code read_parquet} has no schema-override parameters; ignoring them
     * would compare different columns than the Java engine.
     */
    private static void requireNoColumnOverrides(Map<String, Object> options) {
        if (hasExplicitColumns(options)) {
            throw new IllegalArgumentException("duckdb engine does not support columns for Parquet;"
                    + " use the java engine");
        }
        Object types = options.get("types");
        if (types != null && !String.valueOf(types).isBlank()) {
            throw new IllegalArgumentException("duckdb engine does not support types for Parquet;"
                    + " use the java engine");
        }
    }

    /**
     * {@code read_json_auto} has no {@code skip}/{@code nullstr} parameters;
     * silently ignoring them would diverge from the Java scanner.
     */
    private static void requireNoUnsupportedJsonOptions(Map<String, Object> options) {
        Object skip = options.get("skipRows");
        if (skip != null && Integer.parseInt(String.valueOf(skip)) > 0) {
            throw new IllegalArgumentException("duckdb engine does not support skipRows for JSONL;"
                    + " use the java engine");
        }
        Object nullValue = options.get("nullValue");
        if (nullValue != null && !String.valueOf(nullValue).isEmpty()) {
            throw new IllegalArgumentException("duckdb engine does not support nullValue for JSONL;"
                    + " use the java engine");
        }
    }

    /**
     * JSONL nested objects are STRUCTs in DuckDB but serialized JSON text in the
     * Java scanner. Specifying every column makes DuckDB emit the same compact
     * JSON text, so key order and nested values compare identically.
     */
    private static String jsonColumns(ResourceLocator resource) {
        return columnsMap(FileSchemaInferrer.infer(resource, null));
    }

    private static String columnsMap(SchemaDescriptor schema) {
        List<FieldDescriptor> fields = schema == null ? null : schema.getFields();
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        StringBuilder columns = new StringBuilder();
        for (FieldDescriptor field : fields) {
            if (field.getName() == null) {
                continue;
            }
            if (columns.length() > 0) {
                columns.append(", ");
            }
            String canonical = field.getCanonicalType();
            columns.append(literal(field.getName())).append(": ")
                    .append(literal(TEXT_TYPES.contains(canonical) ? "VARCHAR" : duckDbType(canonical)));
        }
        return columns.length() == 0 ? "" : ", columns={" + columns + "}";
    }

    private static String duckDbType(String canonicalType) {
        if (canonicalType == null) {
            return "VARCHAR";
        }
        switch (canonicalType) {
            case "TINYINT":
            case "SMALLINT":
            case "INTEGER":
            case "BIGINT":
            case "FLOAT":
            case "REAL":
            case "DOUBLE":
            case "BOOLEAN":
            case "DATE":
            case "TIME":
            case "TIMESTAMP":
            case "VARCHAR":
            case "CHAR":
            case "TEXT":
            case "CLOB":
            case "LONGVARCHAR":
                return canonicalType;
            case "DECIMAL":
            case "NUMERIC":
                return "DECIMAL(38,15)";
            default:
                return "VARCHAR";
        }
    }

    /**
     * Maps Java charset names to DuckDB's CSV {@code encoding} values. DuckDB
     * reads GBK/GB2312 through the gb18030 superset; unsupported charsets fail
     * instead of producing mojibake or a raw decoder error.
     */
    private static String duckDbEncoding(Object value) {
        if (value == null) {
            return null;
        }
        String charset = String.valueOf(value).trim();
        if (charset.isEmpty()) {
            return null;
        }
        String normalized = charset.toUpperCase(Locale.ROOT).replace("_", "-");
        switch (normalized) {
            case "UTF-8":
            case "UTF8":
                return null;
            case "GBK":
            case "GB2312":
            case "GB18030":
            case "EUC-CN":
                return "gb18030";
            case "ISO-8859-1":
            case "LATIN1":
            case "LATIN-1":
                return "latin-1";
            case "UTF-16":
            case "UTF16":
                return "utf-16";
            default:
                throw new IllegalArgumentException("duckdb engine does not support charset '"
                        + charset + "' for CSV; use the java engine");
        }
    }

    private static boolean isEmptySingleFile(String location) {
        if (location == null || location.isBlank()) {
            return false;
        }
        try {
            Path path = Paths.get(location);
            return Files.isRegularFile(path) && Files.size(path) == 0L;
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Matches the Java option parser: {@code tab}/{@code \t} means a tab,
     * otherwise only the first character is used (DuckDB requires one char).
     */
    private static String duckDbDelimiter(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return null;
        }
        if ("\\t".equals(text) || "tab".equalsIgnoreCase(text)) {
            return "\t";
        }
        return String.valueOf(text.charAt(0));
    }

    private static boolean hasHeader(Map<String, Object> options) {
        Object value = options.get("hasHeader");
        return value == null || Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean hasExplicitColumns(Map<String, Object> options) {
        Object value = options.get("columns");
        if (value == null) {
            return false;
        }
        if (value instanceof List) {
            return !((List<?>) value).isEmpty();
        }
        return !String.valueOf(value).isBlank();
    }

    private static String format(String location, Map<String, Object> options) {
        Object configured = options.get("format");
        String format;
        if (configured != null && !String.valueOf(configured).isBlank()) {
            format = String.valueOf(configured).trim().toLowerCase(Locale.ROOT);
        } else {
            String lower = location.toLowerCase(Locale.ROOT);
            if (lower.contains("*") || Files.isDirectory(Paths.get(location))) {
                throw new IllegalArgumentException("duckdb engine requires an explicit format"
                        + " option for directories/globs");
            }
            format = infer(lower);
        }
        switch (format) {
            case "ndjson":
                return "jsonl";
            case "parquet":
            case "jsonl":
            case "json":
            case "csv":
                return format;
            case "orc":
                throw new IllegalArgumentException("DuckDB does not support ORC;"
                        + " use the default java engine for ORC files");
            default:
                throw new IllegalArgumentException("DuckDB engine does not support format '"
                        + format + "'; use csv, jsonl, json or parquet");
        }
    }

    private static String infer(String location) {
        if (location.endsWith(".orc")) {
            throw new IllegalArgumentException("DuckDB does not support ORC;"
                    + " use the default java engine for ORC files");
        }
        if (location.endsWith(".parquet")) {
            return "parquet";
        }
        if (location.endsWith(".jsonl") || location.endsWith(".ndjson")
                || location.endsWith(".jsonl.gz") || location.endsWith(".ndjson.gz")) {
            return "jsonl";
        }
        if (location.endsWith(".json") || location.endsWith(".json.gz")) {
            return "json";
        }
        return "csv";
    }

    private static String extension(String format) {
        switch (format) {
            case "parquet":
                return "parquet";
            case "jsonl":
            case "json":
                return "jsonl";
            default:
                return "csv";
        }
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
