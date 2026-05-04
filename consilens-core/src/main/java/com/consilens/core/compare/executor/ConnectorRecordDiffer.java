package com.consilens.core.compare.executor;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.LegacyTypeMapper;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.CanonicalValue;
import com.consilens.connector.api.record.CloseableIterator;
import com.consilens.core.algorithm.ValueNormalizer;
import com.consilens.core.compare.CompareExecutionSettings;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
final class ConnectorRecordDiffer {

    private static final long PROGRESS_LOG_INTERVAL = 100_000L;

    DiffResult diff(CompareSegment source, CompareSegment target, CompareExecutionSettings settings) {
        PreparedSegment sourceData = prepare(source);
        PreparedSegment targetData = prepare(target);
        List<DiffRow> differences = new ArrayList<>();
        long sourceMissingCount = 0L;
        long targetMissingCount = 0L;
        long mismatchCount = 0L;
        long lastProgressLog = 0L;
        long sourceRowCount = 0L;
        long targetRowCount = 0L;

        log.info("Starting ordered streaming compare: {} <-> {}",
                resolveTablePath(source.getResource(), source.getDataset()).getFullPath(),
                resolveTablePath(target.getResource(), target.getDataset()).getFullPath());

        try (CloseableIterator<CanonicalRecord> sourceIterator = sourceData.scanner.scan(source);
             CloseableIterator<CanonicalRecord> targetIterator = targetData.scanner.scan(target)) {
            SegmentCursor sourceCursor = new SegmentCursor(sourceIterator, source.getKeySpec(), sourceData.columns, sourceData.types);
            SegmentCursor targetCursor = new SegmentCursor(targetIterator, target.getKeySpec(), targetData.columns, targetData.types);

            while (sourceCursor.hasGroup() || targetCursor.hasGroup()) {
                if (!sourceCursor.hasGroup()) {
                    for (RecordRow row : targetCursor.currentRows()) {
                        differences.add(DiffRow.added(targetCursor.currentKey().displayParts(), row.values, targetData.columns));
                        sourceMissingCount++;
                    }
                    targetCursor.advanceGroup();
                } else if (!targetCursor.hasGroup()) {
                    for (RecordRow row : sourceCursor.currentRows()) {
                        differences.add(DiffRow.removed(sourceCursor.currentKey().displayParts(), row.values, sourceData.columns));
                        targetMissingCount++;
                    }
                    sourceCursor.advanceGroup();
                } else {
                    int keyComparison = sourceCursor.currentKey().compareTo(targetCursor.currentKey());
                    if (keyComparison < 0) {
                        for (RecordRow row : sourceCursor.currentRows()) {
                            differences.add(DiffRow.removed(sourceCursor.currentKey().displayParts(), row.values, sourceData.columns));
                            targetMissingCount++;
                        }
                        sourceCursor.advanceGroup();
                    } else if (keyComparison > 0) {
                        for (RecordRow row : targetCursor.currentRows()) {
                            differences.add(DiffRow.added(targetCursor.currentKey().displayParts(), row.values, targetData.columns));
                            sourceMissingCount++;
                        }
                        targetCursor.advanceGroup();
                    } else {
                        List<RecordRow> sourceRows = sourceCursor.currentRows();
                        List<RecordRow> targetRows = targetCursor.currentRows();
                        NormalizedKey key = sourceCursor.currentKey();

                        if (settings.isValidateUniqueKeys()
                                && (sourceRows.size() > 1 || targetRows.size() > 1)) {
                            throw new ConnectorException("Duplicate primary keys found for key: " + key.sortKey());
                        }

                        if (sourceRows.size() != 1 || targetRows.size() != 1) {
                            for (RecordRow row : sourceRows) {
                                differences.add(DiffRow.removed(key.displayParts(), row.values, sourceData.columns));
                                targetMissingCount++;
                            }
                            for (RecordRow row : targetRows) {
                                differences.add(DiffRow.added(key.displayParts(), row.values, targetData.columns));
                                sourceMissingCount++;
                            }
                        } else {
                            RecordRow sourceRow = sourceRows.get(0);
                            RecordRow targetRow = targetRows.get(0);
                            if (!sourceRow.normalizedValues.equals(targetRow.normalizedValues)) {
                                differences.add(DiffRow.modified(
                                        key.displayParts(),
                                        sourceRow.values,
                                        targetRow.values,
                                        sourceData.columns,
                                        targetData.columns,
                                        changedColumns(sourceData.columns, sourceRow.normalizedValues, targetRow.normalizedValues),
                                        changedColumns(targetData.columns, sourceRow.normalizedValues, targetRow.normalizedValues)));
                                mismatchCount++;
                            }
                        }

                        sourceCursor.advanceGroup();
                        targetCursor.advanceGroup();
                    }
                }

                long processedRows = sourceCursor.rowCount() + targetCursor.rowCount();
                if (processedRows - lastProgressLog >= PROGRESS_LOG_INTERVAL) {
                    log.info("Streaming compare progress: sourceRows={}, targetRows={}, differences={}",
                            sourceCursor.rowCount(),
                            targetCursor.rowCount(),
                            sourceMissingCount + targetMissingCount + mismatchCount);
                    lastProgressLog = processedRows;
                }
            }
            sourceRowCount = sourceCursor.rowCount();
            targetRowCount = targetCursor.rowCount();
        } catch (Exception e) {
            throw e instanceof ConnectorException
                    ? (ConnectorException) e
                    : new ConnectorException("Failed during ordered streaming compare", e);
        }

        log.info("Completed ordered streaming compare: sourceRows={}, targetRows={}, differences={}",
                sourceRowCount,
                targetRowCount,
                sourceMissingCount + targetMissingCount + mismatchCount);
        return DiffResult.builder()
                .differences(differences)
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(sourceRowCount)
                        .targetRowCount(targetRowCount)
                        .sourceMissingCount(sourceMissingCount)
                        .targetMissingCount(targetMissingCount)
                        .mismatchCount(mismatchCount)
                        .totalDifferences(sourceMissingCount + targetMissingCount + mismatchCount)
                        .processingTimeMs(0L)
                        .unchangedCount(Math.max(0L, Math.min(sourceRowCount, targetRowCount) - mismatchCount))
                        .differencePercentage(0.0)
                        .build())
                .infoTree(Optional.empty())
                .completedAt(Instant.now())
                .metadata(Map.of("engine", "connector-record-diff"))
                .sourceTablePath(resolveTablePath(source.getResource(), source.getDataset()))
                .targetTablePath(resolveTablePath(target.getResource(), target.getDataset()))
                .build();
    }

    DiffResult empty(CompareSegment source, CompareSegment target, long sourceRows, long targetRows) {
        return DiffResult.builder()
                .differences(List.of())
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(sourceRows)
                        .targetRowCount(targetRows)
                        .sourceMissingCount(0L)
                        .targetMissingCount(0L)
                        .mismatchCount(0L)
                        .totalDifferences(0L)
                        .processingTimeMs(0L)
                        .unchangedCount(Math.min(sourceRows, targetRows))
                        .differencePercentage(0.0)
                        .build())
                .infoTree(Optional.empty())
                .completedAt(Instant.now())
                .metadata(Map.of("engine", "connector-record-diff"))
                .sourceTablePath(resolveTablePath(source.getResource(), source.getDataset()))
                .targetTablePath(resolveTablePath(target.getResource(), target.getDataset()))
                .build();
    }

    private PreparedSegment prepare(CompareSegment segment) {
        DatasetHandle dataset = segment.getDataset();
        if (dataset == null || dataset.getRecordScanner().isEmpty()) {
            throw new ConnectorException("RecordScanner is required for dataset diff");
        }

        SchemaDescriptor schema = segment.getSchema() != null ? segment.getSchema() : dataset.getSchema();
        List<String> columns = columns(segment.getKeySpec(), segment.getComparisons(), schema);
        Map<String, DataType> types = columnTypes(schema, columns);
        return new PreparedSegment(dataset.getRecordScanner().get(), columns, types);
    }

    private RecordRow toRecordRow(CanonicalRecord record,
                                  KeySpec keySpec,
                                  List<String> columns,
                                  Map<String, DataType> types) {
        Map<String, CanonicalValue> values = record.getValues() != null ? record.getValues() : Collections.emptyMap();
        List<Object> rawValues = new ArrayList<>(columns.size());
        List<Object> normalizedValues = new ArrayList<>(columns.size());
        List<Object> normalizedKey = new ArrayList<>();
        List<Object> rawKey = new ArrayList<>();
        List<String> keyColumns = keySpec != null && keySpec.getFields() != null ? keySpec.getFields() : List.of();

        for (String column : columns) {
            CanonicalValue value = values.get(column);
            Object rawValue = value != null ? value.getValue() : null;
            DataType dataType = types.getOrDefault(column, DataType.UNKNOWN);
            String normalized = ValueNormalizer.normalizeValue(rawValue, dataType);
            rawValues.add(normalized);
            normalizedValues.add(normalized);
            if (keyColumns.contains(column)) {
                normalizedKey.add(normalized);
                rawKey.add(rawValue);
            }
        }

        if (rawKey.isEmpty() && record.getKey() != null && record.getKey().getParts() != null) {
            rawKey.addAll(record.getKey().getParts());
        }

        return new RecordRow(new NormalizedKey(normalizedKey, rawKey), rawValues, normalizedValues);
    }

    private List<String> columns(KeySpec keySpec, ComparisonSpec comparisons, SchemaDescriptor schema) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (keySpec != null && keySpec.getFields() != null) {
            result.addAll(keySpec.getFields());
        }
        if (comparisons != null && comparisons.getFields() != null && !comparisons.getFields().isEmpty()) {
            result.addAll(comparisons.getFields());
            return List.copyOf(result);
        }
        if (schema != null && schema.getFields() != null) {
            Set<String> keys = keySpec != null && keySpec.getFields() != null
                    ? new LinkedHashSet<>(keySpec.getFields())
                    : Set.of();
            for (FieldDescriptor field : schema.getFields()) {
                if (field.getName() != null && !keys.contains(field.getName())) {
                    result.add(field.getName());
                }
            }
        }
        return List.copyOf(result);
    }

    private Map<String, DataType> columnTypes(SchemaDescriptor schema, List<String> columns) {
        Map<String, FieldDescriptor> fieldMap = schema != null && schema.getFieldMap() != null
                ? schema.getFieldMap()
                : Collections.emptyMap();
        Map<String, DataType> result = new LinkedHashMap<>();
        for (String column : columns) {
            FieldDescriptor field = fieldMap.get(column);
            String canonicalType = field != null && field.getTypeDescriptor() != null
                    ? LegacyTypeMapper.toCanonicalType(field.getTypeDescriptor())
                    : field != null ? field.getCanonicalType() : null;
            result.put(column, resolveType(canonicalType));
        }
        return result;
    }

    private DataType resolveType(String canonicalType) {
        if (canonicalType == null || canonicalType.trim().isEmpty()) {
            return DataType.UNKNOWN;
        }
        try {
            return DataType.valueOf(canonicalType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DataType.UNKNOWN;
        }
    }

    private List<String> changedColumns(List<String> columns, List<Object> sourceValues, List<Object> targetValues) {
        List<String> changed = new ArrayList<>();
        int max = Math.max(sourceValues.size(), targetValues.size());
        for (int index = 0; index < max; index++) {
            Object sourceValue = index < sourceValues.size() ? sourceValues.get(index) : null;
            Object targetValue = index < targetValues.size() ? targetValues.get(index) : null;
            if (!Objects.equals(sourceValue, targetValue) && index < columns.size()) {
                changed.add(columns.get(index));
            }
        }
        return changed;
    }

    private TablePath resolveTablePath(ResourceLocator resource, DatasetHandle dataset) {
        ResourceLocator locator = resource != null ? resource : dataset.getResource();
        if (locator == null) {
            throw new ConnectorException("ResourceLocator cannot be null");
        }
        if (locator.getName() != null && !locator.getName().trim().isEmpty()) {
            return TablePath.fromString(locator.getName().trim());
        }
        if (locator.getType() != null && "sql".equalsIgnoreCase(locator.getType())) {
            return TablePath.of("sql-resource");
        }
        if (locator.getPath() != null && !locator.getPath().trim().isEmpty()) {
            return TablePath.fromString(locator.getPath().trim());
        }
        throw new ConnectorException("ResourceLocator requires name or path");
    }

    private static final class PreparedSegment {
        private final List<String> columns;
        private final Map<String, DataType> types;
        private final com.consilens.connector.api.dataset.RecordScanner scanner;

        private PreparedSegment(com.consilens.connector.api.dataset.RecordScanner scanner,
                                List<String> columns,
                                Map<String, DataType> types) {
            this.scanner = scanner;
            this.columns = columns;
            this.types = types;
        }
    }

    private static final class RecordRow {
        private final NormalizedKey key;
        private final List<Object> values;
        private final List<Object> normalizedValues;

        private RecordRow(NormalizedKey key, List<Object> values, List<Object> normalizedValues) {
            this.key = key;
            this.values = values;
            this.normalizedValues = normalizedValues;
        }
    }

    private final class SegmentCursor {

        private final CloseableIterator<CanonicalRecord> iterator;
        private final KeySpec keySpec;
        private final List<String> columns;
        private final Map<String, DataType> types;
        private RecordRow pendingRow;
        private NormalizedKey currentKey;
        private List<RecordRow> currentRows = List.of();
        private long rowCount;

        private SegmentCursor(CloseableIterator<CanonicalRecord> iterator,
                              KeySpec keySpec,
                              List<String> columns,
                              Map<String, DataType> types) {
            this.iterator = iterator;
            this.keySpec = keySpec;
            this.columns = columns;
            this.types = types;
            advanceGroup();
        }

        private boolean hasGroup() {
            return currentKey != null;
        }

        private NormalizedKey currentKey() {
            return currentKey;
        }

        private List<RecordRow> currentRows() {
            return currentRows;
        }

        private long rowCount() {
            return rowCount;
        }

        private void advanceGroup() {
            if (pendingRow == null && !iterator.hasNext()) {
                currentKey = null;
                currentRows = List.of();
                return;
            }
            RecordRow first = pendingRow != null ? pendingRow : toRecordRow(iterator.next(), keySpec, columns, types);
            pendingRow = null;
            rowCount++;
            currentKey = first.key;
            List<RecordRow> group = new ArrayList<>();
            group.add(first);

            while (iterator.hasNext()) {
                RecordRow nextRow = toRecordRow(iterator.next(), keySpec, columns, types);
                if (currentKey.equals(nextRow.key)) {
                    group.add(nextRow);
                    rowCount++;
                } else {
                    pendingRow = nextRow;
                    break;
                }
            }
            currentRows = List.copyOf(group);
        }
    }

    private static final class NormalizedKey implements Comparable<NormalizedKey> {
        private final List<Object> parts;
        private final List<Object> rawParts;

        private NormalizedKey(List<Object> parts, List<Object> rawParts) {
            this.parts = List.copyOf(parts);
            this.rawParts = rawParts == null ? List.of() : List.copyOf(rawParts);
        }

        private String sortKey() {
            StringBuilder builder = new StringBuilder();
            for (Object part : parts) {
                if (builder.length() > 0) {
                    builder.append('\u001f');
                }
                builder.append(part == null ? "" : part.toString());
            }
            return builder.toString();
        }

        private List<Object> displayParts() {
            return parts;
        }

        @Override
        public int compareTo(NormalizedKey other) {
            int max = Math.max(parts.size(), other.parts.size());
            for (int index = 0; index < max; index++) {
                Object leftRaw = index < rawParts.size() ? rawParts.get(index) : null;
                Object rightRaw = index < other.rawParts.size() ? other.rawParts.get(index) : null;
                Object leftPart = index < parts.size() ? parts.get(index) : null;
                Object rightPart = index < other.parts.size() ? other.parts.get(index) : null;
                int comparison = comparePart(leftRaw, rightRaw, leftPart, rightPart);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }

        private int comparePart(Object leftRaw, Object rightRaw, Object leftPart, Object rightPart) {
            if (leftRaw == null && rightRaw == null) {
                return compareStrings(leftPart, rightPart);
            }
            if (leftRaw == null) {
                return -1;
            }
            if (rightRaw == null) {
                return 1;
            }
            if (leftRaw instanceof Number && rightRaw instanceof Number) {
                return new BigDecimal(leftRaw.toString()).compareTo(new BigDecimal(rightRaw.toString()));
            }
            if (leftRaw instanceof Comparable && leftRaw.getClass().isInstance(rightRaw)) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                int comparison = ((Comparable) leftRaw).compareTo(rightRaw);
                return comparison;
            }
            return compareStrings(leftPart, rightPart);
        }

        private int compareStrings(Object left, Object right) {
            String leftString = left == null ? "" : left.toString();
            String rightString = right == null ? "" : right.toString();
            return leftString.compareTo(rightString);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof NormalizedKey)) {
                return false;
            }
            return parts.equals(((NormalizedKey) other).parts);
        }

        @Override
        public int hashCode() {
            return parts.hashCode();
        }
    }
}
