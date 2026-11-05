package com.consilens.core.compare.executor;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.connector.api.LegacyTypeMapper;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.DerivedCompareColumns;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.CanonicalValue;
import com.consilens.connector.api.record.CloseableIterator;
import com.consilens.core.algorithm.BudgetGuard;
import com.consilens.core.algorithm.MultisetRowPairing;
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
        int decimalScale = settings != null ? settings.getDecimalScale() : ValueNormalizer.DEFAULT_DECIMAL_SCALE;
        java.time.ZoneId sessionZone = settings != null ? settings.getSessionZone() : null;
        BudgetGuard budgetGuard = new BudgetGuard(
                settings != null ? settings.getMaxDurationMs() : Long.MAX_VALUE,
                settings != null ? settings.getMaxRowsScanned() : Long.MAX_VALUE,
                Long.MAX_VALUE);
        PreparedSegment sourceData = prepare(source, decimalScale, sessionZone);
        PreparedSegment targetData = prepare(target, decimalScale, sessionZone);
        List<DiffRow> differences = new ArrayList<>();
        long sourceMissingCount = 0L;
        long targetMissingCount = 0L;
        long mismatchCount = 0L;
        long lastProgressLog = 0L;
        long countedRows = 0L;
        long sourceRowCount = 0L;
        long targetRowCount = 0L;
        long duplicateKeyGroups = 0L;
        long duplicateKeyRows = 0L;
        long startedAt = System.currentTimeMillis();

        log.info("Starting ordered streaming compare: {} <-> {}",
                resolveTablePath(source.getResource(), source.getDataset()).getFullPath(),
                resolveTablePath(target.getResource(), target.getDataset()).getFullPath());

        try (CloseableIterator<CanonicalRecord> sourceIterator = sourceData.scanner.scan(source);
             CloseableIterator<CanonicalRecord> targetIterator = targetData.scanner.scan(target)) {
            SegmentCursor sourceCursor = new SegmentCursor(sourceIterator, source.getKeySpec(),
                    sourceData.columns, sourceData.types, sourceData.handler, decimalScale, sessionZone);
            SegmentCursor targetCursor = new SegmentCursor(targetIterator, target.getKeySpec(),
                    targetData.columns, targetData.types, targetData.handler, decimalScale, sessionZone);

            while (sourceCursor.hasGroup() || targetCursor.hasGroup()) {
                if (!sourceCursor.hasGroup()) {
                    for (RecordRow row : targetCursor.currentRows()) {
                        addDifference(differences, DiffRow.added(targetCursor.currentKey().displayParts(), row.values, targetData.columns), settings);
                        sourceMissingCount++;
                    }
                    targetCursor.advanceGroup();
                } else if (!targetCursor.hasGroup()) {
                    for (RecordRow row : sourceCursor.currentRows()) {
                        addDifference(differences, DiffRow.removed(sourceCursor.currentKey().displayParts(), row.values, sourceData.columns), settings);
                        targetMissingCount++;
                    }
                    sourceCursor.advanceGroup();
                } else {
                    int keyComparison = sourceCursor.currentKey().compareTo(targetCursor.currentKey());
                    if (keyComparison < 0) {
                        for (RecordRow row : sourceCursor.currentRows()) {
                            addDifference(differences, DiffRow.removed(sourceCursor.currentKey().displayParts(), row.values, sourceData.columns), settings);
                            targetMissingCount++;
                        }
                        sourceCursor.advanceGroup();
                    } else if (keyComparison > 0) {
                        for (RecordRow row : targetCursor.currentRows()) {
                            addDifference(differences, DiffRow.added(targetCursor.currentKey().displayParts(), row.values, targetData.columns), settings);
                            sourceMissingCount++;
                        }
                        targetCursor.advanceGroup();
                    } else {
                        List<RecordRow> sourceRows = sourceCursor.currentRows();
                        List<RecordRow> targetRows = targetCursor.currentRows();
                        NormalizedKey key = sourceCursor.currentKey();

                        if (settings != null && settings.isValidateUniqueKeys()
                                && (sourceRows.size() > 1 || targetRows.size() > 1)) {
                            throw new ConnectorException("唯一键校验失败：键 " + key.displayParts()
                                    + " 在两侧分别出现 " + sourceRows.size() + " / " + targetRows.size()
                                    + " 行（validateUniqueKeys=true）。如需按多重集语义比较"
                                    + "（值相等的重复行互相抵消），请设置 executionOptions.validateUniqueKeys=false。");
                        }

                        if (sourceRows.size() != 1 || targetRows.size() != 1) {
                            // 重复键开放档：与 checksum 的归因共用 MultisetRowPairing（抵消 → 按序配对 → SM/TM），
                            // 保证两条计划对同一份数据给出完全一致的结论。
                            List<String> sourceSignatures = new ArrayList<>(sourceRows.size());
                            for (RecordRow row : sourceRows) {
                                sourceSignatures.add(MultisetRowPairing.signature(row.normalizedValues));
                            }
                            List<String> targetSignatures = new ArrayList<>(targetRows.size());
                            for (RecordRow row : targetRows) {
                                targetSignatures.add(MultisetRowPairing.signature(row.normalizedValues));
                            }
                            MultisetRowPairing.Outcome outcome = MultisetRowPairing.pair(sourceSignatures, targetSignatures);
                            duplicateKeyGroups++;
                            duplicateKeyRows += sourceRows.size() + targetRows.size();
                            // 逐组明细只在 DEBUG 可见，避免大量重复键时刷屏；组数与行数汇总在完成日志里。
                            log.debug("Duplicate-key group key={}: sourceRows={}, targetRows={}, equal={},"
                                            + " pairedMismatch={}, sourceOnly={}, targetOnly={}",
                                    key.sortKey(), sourceRows.size(), targetRows.size(), outcome.equalPairs.size(),
                                    outcome.mismatchPairs.size(), outcome.sourceOnly.size(), outcome.targetOnly.size());

                            for (int[] pair : outcome.mismatchPairs) {
                                RecordRow sourceRow = sourceRows.get(pair[0]);
                                RecordRow targetRow = targetRows.get(pair[1]);
                                DiffRow diff = DiffRow.modified(key.displayParts(),
                                        sourceRow.values, targetRow.values,
                                        sourceData.columns, targetData.columns,
                                        changedColumns(sourceData.columns, sourceRow.normalizedValues, targetRow.normalizedValues),
                                        changedColumns(targetData.columns, sourceRow.normalizedValues, targetRow.normalizedValues));
                                addDifference(differences, DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                                        outcome.sourceOccurrence[pair[0]], sourceRows.size(), targetRows.size())), settings);
                                mismatchCount++;
                            }
                            for (int index : outcome.sourceOnly) {
                                DiffRow diff = DiffRow.removed(key.displayParts(), sourceRows.get(index).values,
                                        sourceData.columns);
                                addDifference(differences, DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                                        outcome.sourceOccurrence[index], sourceRows.size(), targetRows.size())), settings);
                                targetMissingCount++;
                            }
                            for (int index : outcome.targetOnly) {
                                DiffRow diff = DiffRow.added(key.displayParts(), targetRows.get(index).values,
                                        targetData.columns);
                                addDifference(differences, DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                                        outcome.targetOccurrence[index], sourceRows.size(), targetRows.size())), settings);
                                sourceMissingCount++;
                            }
                        } else {
                            RecordRow sourceRow = sourceRows.get(0);
                            RecordRow targetRow = targetRows.get(0);
                            if (!sourceRow.normalizedValues.equals(targetRow.normalizedValues)) {
                                addDifference(differences, DiffRow.modified(
                                        key.displayParts(),
                                        sourceRow.values,
                                        targetRow.values,
                                        sourceData.columns,
                                        targetData.columns,
                                        changedColumns(sourceData.columns, sourceRow.normalizedValues, targetRow.normalizedValues),
                                        changedColumns(targetData.columns, sourceRow.normalizedValues, targetRow.normalizedValues)), settings);
                                mismatchCount++;
                            }
                        }

                        sourceCursor.advanceGroup();
                        targetCursor.advanceGroup();
                    }
                }

                long processedRows = sourceCursor.rowCount() + targetCursor.rowCount();
                if (processedRows - countedRows > 0) {
                    // 行数预算按累计扫描行数检查；流式路径不估算字节
                    budgetGuard.addRowsScanned(processedRows - countedRows);
                    budgetGuard.checkDuration();
                    countedRows = processedRows;
                }
                if (processedRows - lastProgressLog >= PROGRESS_LOG_INTERVAL) {
                    log.info("Streaming compare progress: sourceRows={}, targetRows={}, differences={}",
                            sourceCursor.rowCount(),
                            targetCursor.rowCount(),
                            sourceMissingCount + targetMissingCount + mismatchCount);
                    notifyProgress(settings, sourceCursor.rowCount(), targetCursor.rowCount(),
                            sourceMissingCount + targetMissingCount + mismatchCount, startedAt);
                    lastProgressLog = processedRows;
                }
            }
            sourceRowCount = sourceCursor.rowCount();
            targetRowCount = targetCursor.rowCount();
        } catch (Exception e) {
            log.error("Ordered streaming compare failed", e);
            // 预算超限是"业务性拒绝"：上层（RunTaskHandler）靠 BudgetExceededException 映射
            // BUDGET_EXCEEDED 且不重试。包成 ConnectorException 会被当成普通执行失败重试。
            if (e instanceof com.consilens.core.exception.BudgetExceededException) {
                throw (com.consilens.core.exception.BudgetExceededException) e;
            }
            // 把根因带进消息：否则上层只看到"Failed during ordered streaming compare"，
            // 排查需要翻 DEBUG 日志（且部分驱动异常不会打栈）。
            throw e instanceof ConnectorException
                    ? (ConnectorException) e
                    : new ConnectorException("Failed during ordered streaming compare: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }

        log.info("Completed ordered streaming compare: sourceRows={}, targetRows={}, differences={},"
                        + " duplicateKeyGroups={}, duplicateKeyRows={}",
                sourceRowCount,
                targetRowCount,
                sourceMissingCount + targetMissingCount + mismatchCount,
                duplicateKeyGroups,
                duplicateKeyRows);
        notifyProgress(settings, sourceRowCount, targetRowCount,
                sourceMissingCount + targetMissingCount + mismatchCount, startedAt);
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
                        .build())
                .infoTree(Optional.empty())
                .completedAt(Instant.now())
                .metadata(new java.util.LinkedHashMap<>(Map.of("engine", "connector-record-diff")))
                .sourceTablePath(resolveTablePath(source.getResource(), source.getDataset()))
                .targetTablePath(resolveTablePath(target.getResource(), target.getDataset()))
                .build();
    }

    private void notifyProgress(CompareExecutionSettings settings, long sourceRows,
                                long targetRows, long differences, long startedAt) {
        if (settings == null || settings.getProgressListener() == null) {
            return;
        }
        try {
            settings.getProgressListener().onProgress(
                    com.consilens.connector.api.planner.CompareProgress.builder()
                            .sourceRows(sourceRows)
                            .targetRows(targetRows)
                            .differences(differences)
                            .elapsedMs(Math.max(0L, System.currentTimeMillis() - startedAt))
                            .build());
        } catch (RuntimeException ignored) {
            // progress callbacks must not fail the comparison
        }
    }

    private void addDifference(List<DiffRow> differences, DiffRow diffRow, CompareExecutionSettings settings) {
        long maxDifferences = settings != null ? settings.getMaxDifferences() : 1_000_000L;
        if (differences.size() >= maxDifferences) {
            throw new ConnectorException("Diff result exceeds maxDifferences=" + maxDifferences
                    + ". Increase strategy.maxDifferences or narrow the comparison scope.");
        }
        differences.add(diffRow);
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
                        .build())
                .infoTree(Optional.empty())
                .completedAt(Instant.now())
                .metadata(new java.util.LinkedHashMap<>(Map.of("engine", "connector-record-diff")))
                .sourceTablePath(resolveTablePath(source.getResource(), source.getDataset()))
                .targetTablePath(resolveTablePath(target.getResource(), target.getDataset()))
                .build();
    }

    private PreparedSegment prepare(CompareSegment segment, int decimalScale,
                                    java.time.ZoneId sessionZone) {
        DatasetHandle dataset = segment.getDataset();
        if (dataset == null || dataset.getRecordScanner().isEmpty()) {
            throw new ConnectorException("RecordScanner is required for dataset diff");
        }

        SchemaDescriptor schema = segment.getSchema() != null ? segment.getSchema() : dataset.getSchema();
        List<String> columns = columns(segment.getKeySpec(), segment.getComparisons(), schema);
        Map<String, DataType> types = columnTypes(schema, columns);
        // 与 checksum 路径同一归一化口径：方言 handler 先把驱动特有表示映射到公共形态
        // （MySQL YEAR 的 Date、Oracle 的 TIMESTAMP 字符串等），再交给 ValueNormalizer。
        DataTypeHandler handler = dataset.getSupport(
                        com.consilens.connector.api.dataset.DialectProvider.class)
                .map(com.consilens.connector.api.dataset.DialectProvider::getDialect)
                .map(com.consilens.connector.api.DatabaseDialect::getDataTypeHandler)
                .orElse(null);
        return new PreparedSegment(dataset.getRecordScanner().get(), columns, types, handler, decimalScale,
                sessionZone);
    }

    private RecordRow toRecordRow(CanonicalRecord record,
                                  KeySpec keySpec,
                                  List<String> columns,
                                  Map<String, DataType> types,
                                  DataTypeHandler handler,
                                  int decimalScale,
                                  java.time.ZoneId sessionZone) {
        Map<String, CanonicalValue> values = record.getValues() != null ? record.getValues() : Collections.emptyMap();
        List<Object> rawValues = new ArrayList<>(columns.size());
        List<Object> normalizedValues = new ArrayList<>(columns.size());
        List<Object> normalizedKey = new ArrayList<>();
        List<String> keyColumns = keySpec != null && keySpec.getFields() != null ? keySpec.getFields() : List.of();

        for (String column : columns) {
            CanonicalValue value = values.get(column);
            Object rawValue = value != null ? value.getValue() : null;
            DataType dataType = types.getOrDefault(column, DataType.UNKNOWN);
            Object comparisonValue = handler != null
                    ? handler.normalizeValueForComparison(rawValue, dataType)
                    : rawValue;
            String normalized = ValueNormalizer.normalizeValue(comparisonValue, dataType, decimalScale,
                    sessionZone);
            if (normalized == null) {
                // 契约：NULL 归一为 ''。个别驱动/类型分支可能返回 null，
                // 而 NormalizedKey 用 List.copyOf（拒绝 null 元素）会直接 NPE。
                log.warn("Normalizer returned null for column '{}' (type={}, value={}), treating as empty",
                        column, dataType, rawValue);
                normalized = "";
            }
            rawValues.add(rawValue);
            normalizedValues.add(normalized);
            if (keyColumns.stream().anyMatch(key -> key != null && key.equalsIgnoreCase(column))) {
                // 键身份用精确形态：DECIMAL 键若用 4 位小数形态，1.00001/1.00002 会碰撞，
                // 归并时被当成同一个键（差异会被归因成 MISMATCH 而不是 SM/TM）。
                normalizedKey.add(ValueNormalizer.normalizeKey(comparisonValue, dataType));
            }
        }

        return new RecordRow(new NormalizedKey(normalizedKey), rawValues, normalizedValues);
    }

    private List<String> columns(KeySpec keySpec, ComparisonSpec comparisons, SchemaDescriptor schema) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (keySpec != null && keySpec.getFields() != null) {
            for (String key : keySpec.getFields()) {
                result.add(resolveColumnName(key, schema));
            }
        }
        Set<String> excluded = excludedColumns(comparisons);
        if (comparisons != null && comparisons.getFields() != null && !comparisons.getFields().isEmpty()) {
            for (String field : comparisons.getFields()) {
                if (field != null && !isExcluded(excluded, field)) {
                    result.add(resolveColumnName(field, schema));
                }
            }
            return List.copyOf(result);
        }
        if (schema != null && schema.getFields() != null) {
            Set<String> keys = new java.util.HashSet<>();
            if (keySpec != null && keySpec.getFields() != null) {
                for (String key : keySpec.getFields()) {
                    if (key != null) {
                        keys.add(key.toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            for (FieldDescriptor field : schema.getFields()) {
                if (field.getName() != null
                        && !keys.contains(field.getName().toLowerCase(java.util.Locale.ROOT))
                        && !isExcluded(excluded, field.getName())
                        && !DerivedCompareColumns.isDerived(field.getName())) {
                    result.add(field.getName());
                }
            }
        }
        return List.copyOf(result);
    }

    private static String resolveColumnName(String name, SchemaDescriptor schema) {
        if (name == null || schema == null || schema.getFields() == null) {
            return name;
        }
        for (FieldDescriptor field : schema.getFields()) {
            if (field.getName() != null && field.getName().equalsIgnoreCase(name)) {
                return field.getName();
            }
        }
        if (DerivedCompareColumns.isDerived(name)) {
            return name;
        }
        throw new ConnectorException("Column '" + name + "' is not present in the input schema");
    }

    private Set<String> excludedColumns(ComparisonSpec comparisons) {
        if (comparisons == null || comparisons.getExclude() == null || comparisons.getExclude().isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String field : comparisons.getExclude()) {
            if (field != null && !field.trim().isEmpty()) {
                result.add(field.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static boolean isExcluded(Set<String> excluded, String field) {
        return field != null && excluded.contains(field.toLowerCase(Locale.ROOT));
    }

    private Map<String, DataType> columnTypes(SchemaDescriptor schema, List<String> columns) {
        Map<String, FieldDescriptor> fieldMap = schema != null && schema.getFieldMap() != null
                ? schema.getFieldMap()
                : Collections.emptyMap();
        Map<String, FieldDescriptor> folded = new LinkedHashMap<>();
        for (Map.Entry<String, FieldDescriptor> entry : fieldMap.entrySet()) {
            folded.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        Map<String, DataType> result = new LinkedHashMap<>();
        for (String column : columns) {
            // 元数据可能给出大写列名（Oracle 全大写），任务/段用小写：
            // 大小写敏感查找会落空 → 类型 UNKNOWN → 本地归一化退化成对象 toString
            // （byte[] 变 [B@hash、TIMESTAMPTZ 变句柄），两侧必然不等，整表误报。
            FieldDescriptor field = fieldMap.get(column);
            if (field == null) {
                field = folded.get(column.toLowerCase(Locale.ROOT));
            }
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
            return tablePath(locator, locator.getName());
        }
        if (locator.getType() != null && "sql".equalsIgnoreCase(locator.getType())) {
            return TablePath.of("sql-resource");
        }
        if (locator.getPath() != null && !locator.getPath().trim().isEmpty()) {
            return tablePath(locator, locator.getPath());
        }
        throw new ConnectorException("ResourceLocator requires name or path");
    }

    /**
     * File-like resource names are paths, not dot-separated table identifiers.
     * Using {@link TablePath#fromString(String)} on "./data/source.csv" would
     * split into empty components and fail.
     */
    private TablePath tablePath(ResourceLocator locator, String value) {
        String trimmed = value.trim();
        boolean fileLike = (locator.getType() != null
                && "file".equalsIgnoreCase(locator.getType()))
                || trimmed.indexOf('/') >= 0
                || trimmed.indexOf('\\') >= 0;
        if (!fileLike) {
            return TablePath.fromString(trimmed);
        }
        String name = trimmed.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return TablePath.of(name.isBlank() ? "file" : name);
    }

    private static final class PreparedSegment {
        private final List<String> columns;
        private final Map<String, DataType> types;
        private final com.consilens.connector.api.dataset.RecordScanner scanner;
        private final DataTypeHandler handler;
        private final int decimalScale;
        private final java.time.ZoneId sessionZone;

        private PreparedSegment(com.consilens.connector.api.dataset.RecordScanner scanner,
                                List<String> columns,
                                Map<String, DataType> types,
                                DataTypeHandler handler,
                                int decimalScale,
                                java.time.ZoneId sessionZone) {
            this.scanner = scanner;
            this.columns = columns;
            this.types = types;
            this.handler = handler;
            this.decimalScale = decimalScale;
            this.sessionZone = sessionZone;
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
        private final DataTypeHandler handler;
        private final int decimalScale;
        private final java.time.ZoneId sessionZone;
        private RecordRow pendingRow;
        private NormalizedKey currentKey;
        private List<RecordRow> currentRows = List.of();
        private long rowCount;

        private SegmentCursor(CloseableIterator<CanonicalRecord> iterator,
                              KeySpec keySpec,
                              List<String> columns,
                              Map<String, DataType> types,
                              DataTypeHandler handler,
                              int decimalScale,
                              java.time.ZoneId sessionZone) {
            this.iterator = iterator;
            this.keySpec = keySpec;
            this.columns = columns;
            this.types = types;
            this.handler = handler;
            this.decimalScale = decimalScale;
            this.sessionZone = sessionZone;
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
            RecordRow first = pendingRow != null ? pendingRow
                    : toRecordRow(iterator.next(), keySpec, columns, types, handler, decimalScale, sessionZone);
            pendingRow = null;
            rowCount++;
            currentKey = first.key;
            List<RecordRow> group = new ArrayList<>();
            group.add(first);

            while (iterator.hasNext()) {
                RecordRow nextRow = toRecordRow(iterator.next(), keySpec, columns, types, handler, decimalScale,
                        sessionZone);
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

        private NormalizedKey(List<Object> parts) {
            // 必须用容忍 null 的拷贝：复合键允许 NULL 键值（edge 表即有此用例），
            // 而 List.copyOf 遇到 null 元素直接 NPE（实测 oracle edge 整个任务失败）。
            this.parts = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(parts));
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
                Object leftPart = index < parts.size() ? parts.get(index) : null;
                Object rightPart = index < other.parts.size() ? other.parts.get(index) : null;
                int comparison = comparePart(leftPart, rightPart);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }

        private int comparePart(Object leftPart, Object rightPart) {
            // 一律按"规范化字符串"比较，与 SQL 侧 ORDER BY 的字符串形态（orderByExpression）
            // 保持同一套排序语义：归并要求两侧游标的推进顺序与 compareTo 一致，
            // 一旦一侧按数字序、另一侧按字典序（或 Java 侧按数字、SQL 侧按字典），
            // 归并会错位并把同键行判成互不匹配（实测 Oracle typediff 的 NUMBER vs VARCHAR2 键）。
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
