package com.consilens.connector.file;

import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.CanonicalValue;
import com.consilens.connector.api.record.CloseableIterator;
import com.consilens.connector.api.record.RecordKey;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.planner.CompareSegment;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.consilens.connector.api.ConnectorException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Streams CanonicalRecords from one or more local CSV/JSONL/Parquet files.
 *
 * <p>The existing StreamingMergePlan expects both sides sorted by key. Files are
 * loaded and sorted in memory by the same normalized-key form used by the merge
 * comparator; extremely large files should use a split-aware connector.
 */
public class FileRecordScanner implements com.consilens.connector.api.dataset.RecordScanner {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final List<Path> paths;
    private final FileOptions options;
    private final Supplier<SchemaDescriptor> schemaSupplier;

    public FileRecordScanner(List<Path> paths, FileOptions options, Supplier<SchemaDescriptor> schemaSupplier) {
        if (paths == null || paths.isEmpty()) {
            throw new ConnectorException("No data files resolved");
        }
        this.paths = paths;
        this.options = options;
        this.schemaSupplier = schemaSupplier;
    }

    @Override
    public CloseableIterator<CanonicalRecord> scan(CompareSegment segment) {
        SchemaDescriptor schema = segment != null && segment.getSchema() != null
                ? segment.getSchema() : schemaSupplier.get();
        List<CanonicalRecord> records;
        if (options.format() == FileFormat.PARQUET) {
            records = ParquetSupport.load(paths, schema);
        } else if (options.format() == FileFormat.ORC) {
            records = OrcSupport.load(paths, schema);
        } else {
            records = new ArrayList<>();
            for (Path path : paths) {
                if (options.format() == FileFormat.JSONL) {
                    loadJsonl(path, schema, records);
                } else {
                    loadCsv(path, schema, records);
                }
            }
        }
        if (options.format() == FileFormat.PARQUET || options.format() == FileFormat.ORC) {
            ParquetSupport.sortByKey(records, segment, schema);
            records = ParquetSupport.applySplit(records, segment);
        } else {
            sortByKey(records, segment, schema);
            records = applySplit(records, segment);
        }
        return new ListIterator(records);
    }

    private List<CanonicalRecord> applySplit(List<CanonicalRecord> records, CompareSegment segment) {
        if (segment == null || !(segment.getSplit() instanceof com.consilens.connector.api.planner.OffsetLimitSplit)) {
            return records;
        }
        com.consilens.connector.api.planner.OffsetLimitSplit split =
                (com.consilens.connector.api.planner.OffsetLimitSplit) segment.getSplit();
        int from = (int) Math.max(0L, Math.min(split.getOffset(), records.size()));
        long requested = split.getLimit() <= 0 ? Long.MAX_VALUE : split.getLimit();
        int to = (int) Math.max(from, Math.min(records.size(), from + requested));
        return records.subList(from, to);
    }

    private void loadCsv(Path path, SchemaDescriptor schema, List<CanonicalRecord> records) {
        List<FieldDescriptor> fields = schema.getFields() == null ? List.of() : schema.getFields();
        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        try (BufferedReader reader = FileReaders.newBufferedReader(path, options.charset())) {
            skipLines(reader, options.skipRows());
            CsvRecordReader recordReader = new CsvRecordReader(reader, options.delimiter());
            CsvRecordReader.Record first = recordReader.read();
            if (first == null) {
                return;
            }
            if (options.hasHeader()) {
                List<String> header = first.fields();
                for (int index = 0; index < header.size(); index++) {
                    headerIndex.putIfAbsent(header.get(index).trim().toLowerCase(Locale.ROOT), index);
                }
            } else {
                records.add(toRecord(first.fields(), fields, headerIndex));
            }
            CsvRecordReader.Record record;
            while ((record = recordReader.read()) != null) {
                if (!record.blank()) {
                    records.add(toRecord(record.fields(), fields, headerIndex));
                }
            }
        } catch (IOException exception) {
            throw new ConnectorException("Failed to read CSV file " + path + ": " + exception.getMessage(), exception);
        }
    }

    private CanonicalRecord toRecord(List<String> row, List<FieldDescriptor> fields,
                                     Map<String, Integer> headerIndex) {
        Map<String, CanonicalValue> values = new LinkedHashMap<>();
        for (FieldDescriptor field : fields) {
            String name = field.getName();
            Integer index = headerIndex.get(name.toLowerCase(Locale.ROOT));
            if (index == null) {
                int ordinal = field.getOrdinal() == null ? -1 : field.getOrdinal() - 1;
                index = ordinal >= 0 && ordinal < row.size() ? ordinal : -1;
            }
            String raw = index >= 0 && index < row.size() ? row.get(index) : null;
            values.put(name, value(raw, field));
        }
        return new SimpleCanonicalRecord(values);
    }

    private void loadJsonl(Path path, SchemaDescriptor schema, List<CanonicalRecord> records) {
        List<FieldDescriptor> fields = schema.getFields() == null ? List.of() : schema.getFields();
        try (BufferedReader reader = FileReaders.newBufferedReader(path, options.charset())) {
            skipLines(reader, options.skipRows());
            String line;
            int lineNumber = options.skipRows();
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(line);
                if (!node.isObject()) {
                    throw new ConnectorException("JSONL line " + lineNumber + " in " + path
                            + " is not a JSON object");
                }
                Map<String, CanonicalValue> values = new LinkedHashMap<>();
                for (FieldDescriptor field : fields) {
                    JsonNode value = node.get(field.getName());
                    values.put(field.getName(), value(value, field));
                }
                records.add(new SimpleCanonicalRecord(values));
            }
        } catch (IOException exception) {
            throw new ConnectorException("Failed to read JSONL file " + path + ": " + exception.getMessage(), exception);
        }
    }

    private CanonicalValue value(Object raw, FieldDescriptor field) {
        DataType type = type(field);
        Object converted = FileValueParser.parse(raw, type, options.nullValue());
        return CanonicalValue.builder().type(type.name()).value(converted).build();
    }

    private void sortByKey(List<CanonicalRecord> records, CompareSegment segment, SchemaDescriptor schema) {
        if (segment == null || segment.getKeySpec() == null
                || segment.getKeySpec().getFields() == null || segment.getKeySpec().getFields().isEmpty()) {
            return;
        }
        List<String> keys = segment.getKeySpec().getFields();
        Map<String, DataType> types = keyTypes(schema, keys);
        records.sort(Comparator.comparing(record -> keyString(record, keys, types)));
    }

    private Map<String, DataType> keyTypes(SchemaDescriptor schema, List<String> keys) {
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

    private String keyString(CanonicalRecord record, List<String> keys, Map<String, DataType> types) {
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

    private static void skipLines(BufferedReader reader, int count) throws IOException {
        for (int index = 0; index < count; index++) {
            if (reader.readLine() == null) {
                return;
            }
        }
    }

    private static final class SimpleCanonicalRecord implements CanonicalRecord {
        private final RecordKey key = RecordKey.builder().parts(Collections.emptyList()).build();
        private final Map<String, CanonicalValue> values;

        private SimpleCanonicalRecord(Map<String, CanonicalValue> values) {
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

    private static final class ListIterator implements CloseableIterator<CanonicalRecord> {
        private final java.util.Iterator<CanonicalRecord> delegate;

        private ListIterator(List<CanonicalRecord> records) {
            this.delegate = records.iterator();
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public CanonicalRecord next() {
            return delegate.next();
        }

        @Override
        public void close() {
            // no resources
        }
    }
}
