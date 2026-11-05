package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.planner.OffsetLimitSplit;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.CanonicalValue;
import com.consilens.connector.api.record.RecordKey;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parquet reader backed by DuckDB JDBC. DuckDB is an optional dependency: when
 * it is absent, Parquet usage fails with an explicit message instead of an
 * obscure {@link NoClassDefFoundError}.
 *
 * <p>Rows are materialized and sorted in memory by the same normalized-key form
 * used by the CSV/JSONL path, so the existing streaming merge plan applies.
 */
final class ParquetSupport {

    private static final String DRIVER_CLASS = "org.duckdb.DuckDBDriver";

    private ParquetSupport() {
    }

    static SchemaDescriptor inferSchema(List<Path> files) {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "DESCRIBE SELECT * FROM read_parquet(" + literal(files)
                             + ", union_by_name=true)")) {
            List<String> columns = new ArrayList<>();
            List<DataType> types = new ArrayList<>();
            while (result.next()) {
                columns.add(result.getString(1));
                types.add(mapType(result.getString(2)));
            }
            return descriptor(columns, types);
        } catch (SQLException exception) {
            throw new ConnectorException("Failed to read Parquet schema from " + files
                    + ": " + exception.getMessage(), exception);
        }
    }

    static List<CanonicalRecord> load(List<Path> files, SchemaDescriptor schema) {
        List<FieldDescriptor> fields = schema.getFields() == null ? List.of() : schema.getFields();
        List<CanonicalRecord> records = new ArrayList<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM read_parquet(" + literal(files)
                             + ", union_by_name=true)")) {
            ResultSetMetaData meta = result.getMetaData();
            Map<String, Integer> indexes = new LinkedHashMap<>();
            for (int index = 1; index <= meta.getColumnCount(); index++) {
                indexes.putIfAbsent(meta.getColumnLabel(index), index);
            }
            while (result.next()) {
                Map<String, CanonicalValue> values = new LinkedHashMap<>();
                for (FieldDescriptor field : fields) {
                    Integer index = indexes.get(field.getName());
                    if (index == null) {
                        index = indexes.get(field.getName().toLowerCase(Locale.ROOT));
                    }
                    Object raw = index == null ? null : result.getObject(index);
                    values.put(field.getName(), canonical(raw, field));
                }
                records.add(new SimpleRecord(values));
            }
        } catch (SQLException exception) {
            throw new ConnectorException("Failed to read Parquet files " + files
                    + ": " + exception.getMessage(), exception);
        }
        return records;
    }

    static void sortByKey(List<CanonicalRecord> records, CompareSegment segment, SchemaDescriptor schema) {
        if (segment == null || segment.getKeySpec() == null
                || segment.getKeySpec().getFields() == null || segment.getKeySpec().getFields().isEmpty()) {
            return;
        }
        List<String> keys = segment.getKeySpec().getFields();
        Map<String, DataType> types = keyTypes(schema, keys);
        records.sort(Comparator.comparing(record -> keyString(record, keys, types)));
    }

    static List<CanonicalRecord> applySplit(List<CanonicalRecord> records, CompareSegment segment) {
        if (segment == null || !(segment.getSplit() instanceof OffsetLimitSplit)) {
            return records;
        }
        OffsetLimitSplit split = (OffsetLimitSplit) segment.getSplit();
        int from = (int) Math.max(0L, Math.min(split.getOffset(), records.size()));
        long requested = split.getLimit() <= 0 ? Long.MAX_VALUE : split.getLimit();
        int to = (int) Math.max(from, Math.min(records.size(), from + requested));
        return records.subList(from, to);
    }

    private static Connection connect() {
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException | LinkageError error) {
            throw new ConnectorException("Parquet support requires org.duckdb:duckdb_jdbc on the classpath", error);
        }
        try {
            return DriverManager.getConnection("jdbc:duckdb:");
        } catch (SQLException exception) {
            throw new ConnectorException("Failed to open DuckDB: " + exception.getMessage(), exception);
        }
    }

    private static String literal(List<Path> files) {
        if (files == null || files.isEmpty()) {
            throw new ConnectorException("No Parquet files resolved");
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < files.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('\'').append(files.get(index).toAbsolutePath().toString()
                    .replace("'", "''")).append('\'');
        }
        return builder.append(']').toString();
    }

    private static CanonicalValue canonical(Object raw, FieldDescriptor field) {
        DataType type = type(field);
        Object value;
        if (raw instanceof byte[]) {
            value = Base64.getEncoder().encodeToString((byte[]) raw);
        } else if (raw instanceof TemporalAccessor) {
            value = raw.toString();
        } else {
            value = FileValueParser.parse(raw, type, null);
        }
        return CanonicalValue.builder().type(type.name()).value(value).build();
    }

    private static Map<String, DataType> keyTypes(SchemaDescriptor schema, List<String> keys) {
        Map<String, DataType> result = new LinkedHashMap<>();
        Map<String, FieldDescriptor> fieldMap = schema != null && schema.getFieldMap() != null
                ? schema.getFieldMap() : Map.of();
        for (String key : keys) {
            FieldDescriptor field = fieldMap.get(key);
            if (field == null) {
                field = fieldMap.get(key.toLowerCase(Locale.ROOT));
            }
            result.put(key, field != null ? type(field) : DataType.VARCHAR);
        }
        return result;
    }

    private static String keyString(CanonicalRecord record, List<String> keys, Map<String, DataType> types) {
        StringBuilder builder = new StringBuilder();
        for (String key : keys) {
            CanonicalValue value = record.getValues().get(key);
            Object raw = value != null ? value.getValue() : null;
            builder.append(FileKeyNormalizer.normalizeKey(raw, types.getOrDefault(key, DataType.VARCHAR)))
                    .append('\u0001');
        }
        return builder.toString();
    }

    private static DataType type(FieldDescriptor field) {
        try {
            return DataType.valueOf(field.getCanonicalType().toUpperCase(Locale.ROOT));
        } catch (Exception exception) {
            return DataType.VARCHAR;
        }
    }

    private static SchemaDescriptor descriptor(List<String> columns, List<DataType> types) {
        List<FieldDescriptor> fields = new ArrayList<>();
        Map<String, FieldDescriptor> fieldMap = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            FieldDescriptor field = FieldDescriptor.builder()
                    .name(columns.get(index))
                    .canonicalType(types.get(index).name())
                    .nullable(true)
                    .ordinal(index + 1)
                    .build();
            fields.add(field);
            fieldMap.put(field.getName(), field);
        }
        return SchemaDescriptor.builder().fields(fields).fieldMap(fieldMap).build();
    }

    private static DataType mapType(String duckDbType) {
        String type = duckDbType == null ? "" : duckDbType.toUpperCase(Locale.ROOT);
        if (type.startsWith("DECIMAL") || type.startsWith("NUMERIC")) {
            return DataType.DECIMAL;
        }
        if (type.startsWith("TIMESTAMP WITH TIME ZONE") || type.startsWith("TIMESTAMPTZ")) {
            return DataType.TIMESTAMP_WITH_TIMEZONE;
        }
        if (type.startsWith("TIME WITH TIME ZONE") || type.startsWith("TIMETZ")) {
            return DataType.TIME_WITH_TIME_ZONE;
        }
        if (type.startsWith("TIMESTAMP")) {
            return DataType.TIMESTAMP;
        }
        if (type.startsWith("TIME")) {
            return DataType.TIME;
        }
        switch (type) {
            case "BOOLEAN":
            case "BIT":
                return DataType.BOOLEAN;
            case "TINYINT":
            case "SMALLINT":
            case "INTEGER":
            case "INT":
            case "BIGINT":
            case "UTINYINT":
            case "USMALLINT":
            case "UINTEGER":
            case "UBIGINT":
                return DataType.BIGINT;
            case "FLOAT":
            case "REAL":
            case "DOUBLE":
                return DataType.DOUBLE;
            case "DATE":
                return DataType.DATE;
            case "BLOB":
            case "BYTEA":
                return DataType.BLOB;
            case "UUID":
                return DataType.UUID;
            case "JSON":
                return DataType.JSON;
            case "VARCHAR":
            case "TEXT":
            case "STRING":
                return DataType.VARCHAR;
            default:
                return DataType.VARCHAR;
        }
    }

    private static final class SimpleRecord implements CanonicalRecord {
        private final RecordKey key = RecordKey.builder().parts(Collections.emptyList()).build();
        private final Map<String, CanonicalValue> values;

        private SimpleRecord(Map<String, CanonicalValue> values) {
            this.values = values;
        }

        @Override
        public RecordKey getKey() {
            return key;
        }

        @Override
        public Map<String, CanonicalValue> getValues() {
            return values;
        }
    }
}
