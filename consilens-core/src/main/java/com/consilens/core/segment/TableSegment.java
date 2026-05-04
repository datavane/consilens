package com.consilens.core.segment;

import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.connector.api.model.TablePath;
import lombok.Builder;

import lombok.Data;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a segment of table data for comparison.
 */
@Slf4j
@Data
@Builder(toBuilder = true)
public class TableSegment {

    private static final Pattern CUSTOM_WHERE_TOKEN_PATTERN = Pattern.compile(
            "\\s+|<=|>=|<>|!=|=|<|>|\\(|\\)|,|-?\\d+(?:\\.\\d+)?|'(?:''|[^'])*'|[A-Za-z_][A-Za-z0-9_]*");

    private static final Set<String> ALLOWED_WHERE_KEYWORDS = Set.of(
            "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "TRUE", "FALSE");

    private static final Set<String> BLOCKED_WHERE_KEYWORDS = Set.of(
            "SELECT", "UNION", "DROP", "DELETE", "INSERT", "UPDATE", "ALTER", "CREATE",
            "TRUNCATE", "MERGE", "CALL", "EXEC", "EXECUTE", "FROM", "JOIN", "HAVING",
            "ORDER", "GROUP", "LIMIT", "OFFSET", "WITH");

    // Database and table identification
    private DatabaseAdapter database;

    private TablePath tablePath;

    private RelationSource relationSource;

    // Column definitions
    private List<String> keyColumns;

    @Builder.Default
    private Optional<String> updateColumn = Optional.empty();

    private List<String> extraColumns;

    // Segment bounds
    @Builder.Default
    private Optional<List<Object>> minKey = Optional.empty();

    @Builder.Default
    private Optional<List<Object>> maxKey = Optional.empty();

    @Builder.Default
    private Optional<Instant> minUpdate = Optional.empty();

    @Builder.Default
    private Optional<Instant> maxUpdate = Optional.empty();

    // Additional constraints
    @Builder.Default
    private  Optional<String> whereClause = Optional.empty();

    // Row-based segmentation support (for non-numeric keys)
    @Builder.Default
    private Optional<LimitOffset> limitOffset = Optional.empty();
    
    @Builder.Default
    private int segmentIndex = 0;

    private boolean caseSensitive;

    // Runtime data
    @Builder.Default
    private Optional<TableSchema> schema = Optional.empty();

    // Cached derived data
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    // Derived projection of key/update/extra columns used by query builders; cached lazily
    private List<String> cachedRelevantColumns;

    public List<String> getKeyColumns() {
        return keyColumns != null ? java.util.Collections.unmodifiableList(keyColumns)
                : java.util.Collections.emptyList();
    }

    public List<String> getExtraColumns() {
        return extraColumns != null ? java.util.Collections.unmodifiableList(extraColumns)
                : java.util.Collections.emptyList();
    }

    public boolean hasRelationSource() {
        return relationSource != null
                && relationSource.getFromSql() != null
                && !relationSource.getFromSql().trim().isEmpty();
    }

    public String getRelationFromSql() {
        return hasRelationSource() ? relationSource.getFromSql().trim() : null;
    }

    public String getDisplayName() {
        if (hasRelationSource()
                && relationSource.getDisplayName() != null
                && !relationSource.getDisplayName().trim().isEmpty()) {
            return relationSource.getDisplayName().trim();
        }
        return tablePath != null ? tablePath.getFullPath() : "<unknown>";
    }

    /**
     * Get all relevant columns (key + extra + update if present).
     * Result is cached for performance.
     */
    public List<String> getRelevantColumns() {
        if (cachedRelevantColumns != null) {
            return cachedRelevantColumns;
        }

        // Use LinkedHashSet to maintain order and avoid duplicates
        Set<String> columnsSet = new LinkedHashSet<>();

        if (keyColumns != null) {
            columnsSet.addAll(keyColumns);
        }

        if (updateColumn != null) {
            updateColumn.ifPresent(columnsSet::add);
        }

        if (extraColumns != null) {
            columnsSet.addAll(extraColumns);
        }

        // Persist the derived list once to avoid rebuilding it for every query builder call.
        List<String> result = java.util.Collections.unmodifiableList(new ArrayList<>(columnsSet));
        cachedRelevantColumns = result;
        return result;
    }

    /**
     * Check if the segment is bounded (has both min and max key).
     */
    public boolean isBounded() {
        return minKey != null && minKey.isPresent() && maxKey != null && maxKey.isPresent() &&
                !minKey.get().isEmpty() && !maxKey.get().isEmpty();
    }

    /**
     * Estimate the size of this segment based on key ranges.
     * For numeric keys, use range calculation.
     * For non-numeric keys, return UNKNOWN to indicate estimation is not reliable.
     */
    public long approximateSize() {
        if (!isBounded()) {
            return Long.MAX_VALUE; // Unbounded segments
        }

        List<Object> min = minKey.get();
        List<Object> max = maxKey.get();

        try {
            // Check if all keys are numeric
            boolean allNumeric = true;
            for (int i = 0; i < min.size(); i++) {
                if (!(min.get(i) instanceof Number) || !(max.get(i) instanceof Number)) {
                    allNumeric = false;
                    break;
                }
            }

            // For non-numeric keys, return UNKNOWN (use actual count instead)
            if (!allNumeric) {
                return -1; // -1 indicates unknown size, caller should use count()
            }

            // For numeric keys, calculate range-based estimation
            long estimatedRows = 1;
            for (int i = 0; i < min.size(); i++) {
                Object minValue = min.get(i);
                Object maxValue = max.get(i);

                long minVal = ((Number) minValue).longValue();
                long maxVal = ((Number) maxValue).longValue();
                long diff = Math.subtractExact(maxVal, minVal);
                long range = Math.max(1L, diff);
                if (range <= 0) {
                    return Long.MAX_VALUE;
                }
                estimatedRows = Math.multiplyExact(estimatedRows, range);
            }
            return estimatedRows;
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        } catch (Exception e) {
            log.warn("Failed to estimate segment size", e);
            return -1; // Unknown size
        }
    }

    /**
     * Choose evenly spaced checkpoints for segmentation.
     */
    public List<List<Object>> chooseCheckpoints(int count) {
        if (!isBounded()) {
            throw new IllegalStateException("Cannot choose checkpoints for unbounded segment");
        }

        List<Object> min = minKey != null && minKey.isPresent() ? minKey.get() : List.of();
        List<Object> max = maxKey != null && maxKey.isPresent() ? maxKey.get() : List.of();

        /*
         * For the core module we fall back to simple arithmetic interpolation between min and max
         * for each key dimension. IntelligentSegmenter can provide better distribution-aware points,
         * but when unavailable we ensure at least evenly spaced cut lines so recursive diff still works.
         */
        return chooseLinearCheckpoints(min, max, count);
    }

    /**
     * Create new segments based on checkpoints.
     */
    public List<TableSegment> segmentByCheckpoints(List<List<Object>> checkpoints) {
        if (checkpoints.isEmpty()) {
            return List.of(this);
        }

        List<TableSegment> segments = new java.util.ArrayList<>();

        // Create segments between consecutive checkpoints
        List<Object> previousPoint = minKey.orElse(List.of());

        for (List<Object> checkpoint : checkpoints) {
            TableSegment segment = toBuilder()
                    .minKey(Optional.of(previousPoint))
                    .maxKey(Optional.of(checkpoint))
                    .build();
            segments.add(segment);
            previousPoint = checkpoint;
        }

        // Add final segment
        TableSegment finalSegment = toBuilder()
                .minKey(Optional.of(previousPoint))
                .maxKey(maxKey)
                .build();
        segments.add(finalSegment);

        return segments;
    }

    /**
     * Count rows in this segment.
     */
    public long count() {
        return database.count(this);
    }

    /**
     * Get checksum for this segment.
     */
    public ChecksumResult countAndChecksum() {
        return database.countAndChecksum(this);
    }

    /**
     * Get count and bounds (minKey, maxKey) without computing checksum.
     * This is much faster than countAndChecksum() for initial bounds calculation.
     */
    public ChecksumResult countAndBounds() {
        return database.countAndBounds(this);
    }

    /**
     * Get all values from this segment.
     */
    public List<Object[]> getValues() {
        return database.querySegment(this);
    }

    /**
     * Get row hashes for this segment (primary key + row hash only).
     * This is much more efficient than getValues() for large segments with many columns.
     * 
     * @return Map of primary key values to row hash (MD5 hex string)
     */
    public java.util.Map<List<Object>, String> getRowHashes() {
        return database.querySegmentRowHashes(this);
    }

    /**
     * Get values for specific primary keys only.
     * This is used to fetch detailed data for rows that have differences.
     * 
     * @param primaryKeys Set of primary key values to fetch
     * @return List of row data arrays
     */
    public List<Object[]> getValuesByKeys(java.util.Set<List<Object>> primaryKeys) {
        return database.querySegmentByKeys(this, primaryKeys);
    }

    /**
     * Create a copy with updated bounds.
     */
    public TableSegment withBounds(List<Object> newMinKey, List<Object> newMaxKey) {
        return toBuilder()
                .minKey(Optional.ofNullable(newMinKey))
                .maxKey(Optional.ofNullable(newMaxKey))
                .build();
    }

    /**
     * Create a copy with schema.
     */
    public TableSegment withSchema(TableSchema schema) {
        return toBuilder().schema(Optional.ofNullable(schema)).build();
    }

    /**
     * Build SQL WHERE clause for this segment.
     */
    public String buildWhereClause() {
        // Validate column names to prevent SQL injection
        validateColumnNames(keyColumns);
        updateColumn.ifPresent(this::validateColumnName);

        StringBuilder whereBuilder = new StringBuilder();

        // Add key range conditions
        if (minKey != null && minKey.isPresent() && keyColumns != null && !keyColumns.isEmpty()) {
            List<Object> minValues = minKey.get();
            
            if (keyColumns.size() == 1) {
                // Single key: simple range check
                String column = keyColumns.get(0);
                Object minValue = minValues.get(0);
                
                if (minValue != null) {
                    whereBuilder.append(String.format("%s >= %s", column, formatValue(minValue)));
                }
                
                // Always add maxKey condition if it exists (segment upper bound)
                if (maxKey != null && maxKey.isPresent()) {
                    List<Object> maxValues = maxKey.get();
                    Object maxValue = maxValues.get(0);
                    if (maxValue != null) {
                        if (whereBuilder.length() > 0) {
                            whereBuilder.append(" AND ");
                        }
                        whereBuilder.append(String.format("%s < %s", column, formatValue(maxValue)));
                    }
                }
            } else {
                // Composite key: use proper range logic
                // Build lower bound: (key1 > min1 OR (key1 = min1 AND key2 >= min2 AND ...))
                StringBuilder lowerBound = new StringBuilder("(");
                for (int i = 0; i < keyColumns.size(); i++) {
                    if (i > 0) {
                        lowerBound.append(" OR (");
                    }
                    
                    // Add equality conditions for all previous keys
                    for (int j = 0; j < i; j++) {
                        lowerBound.append(String.format("%s = %s AND ", 
                                keyColumns.get(j), formatValue(minValues.get(j))));
                    }
                    
                    // Add comparison for current key
                    if (i == keyColumns.size() - 1) {
                        // Last key: use >=
                        lowerBound.append(String.format("%s >= %s", 
                                keyColumns.get(i), formatValue(minValues.get(i))));
                    } else {
                        // Not last key: use >
                        lowerBound.append(String.format("%s > %s", 
                                keyColumns.get(i), formatValue(minValues.get(i))));
                    }
                    
                    if (i > 0) {
                        lowerBound.append(")");
                    }
                }
                lowerBound.append(")");
                
                whereBuilder.append(lowerBound);
                
                // Always add upper bound if maxKey exists (segment upper bound)
                if (maxKey != null && maxKey.isPresent()) {
                    List<Object> maxValues = maxKey.get();
                    
                    // Build upper bound: (key1 < max1 OR (key1 = max1 AND key2 < max2 AND ...))
                    StringBuilder upperBound = new StringBuilder("(");
                    for (int i = 0; i < keyColumns.size(); i++) {
                        if (i > 0) {
                            upperBound.append(" OR (");
                        }
                        
                        // Add equality conditions for all previous keys
                        for (int j = 0; j < i; j++) {
                            upperBound.append(String.format("%s = %s AND ", 
                                    keyColumns.get(j), formatValue(maxValues.get(j))));
                        }
                        
                        // Add comparison for current key (always use <)
                        upperBound.append(String.format("%s < %s", 
                                keyColumns.get(i), formatValue(maxValues.get(i))));
                        
                        if (i > 0) {
                            upperBound.append(")");
                        }
                    }
                    upperBound.append(")");
                    
                    whereBuilder.append(" AND ").append(upperBound);
                }
            }
        }

        // Add update time range conditions
        if (updateColumn != null && updateColumn.isPresent() &&
                ((minUpdate != null && minUpdate.isPresent()) || (maxUpdate != null && maxUpdate.isPresent()))) {
            List<String> timeConditions = new java.util.ArrayList<>();
            String updateCol = updateColumn.get();

            minUpdate.ifPresent(min -> timeConditions.add(String.format("%s >= '%s'", updateCol, min)));
            maxUpdate.ifPresent(max -> timeConditions.add(String.format("%s < '%s'", updateCol, max)));

            if (!timeConditions.isEmpty()) {
                if (whereBuilder.length() > 0) {
                    whereBuilder.append(" AND ");
                }
                whereBuilder.append(String.join(" AND ", timeConditions));
            }
        }

        // Add custom where clause
        if (whereClause != null && whereClause.isPresent()) {
            String customWhere = whereClause.get();
            if (!customWhere.isBlank()) {
                validateCustomWhereClause(customWhere);
            }

            if (whereBuilder.length() > 0) {
                whereBuilder.append(" AND (");
                whereBuilder.append(customWhere);
                whereBuilder.append(")");
            } else {
                whereBuilder.append(customWhere);
            }
        }

        return whereBuilder.length() > 0 ? whereBuilder.toString() : null;
    }

    private void validateColumnNames(List<String> columns) {
        if (columns != null) {
            for (String column : columns) {
                validateColumnName(column);
            }
        }
    }

    private void validateColumnName(String column) {
        if (column == null || !column.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid column name: " + column);
        }
    }

    private void validateCustomWhereClause(String customWhere) {
        if (customWhere.contains(";") || customWhere.contains("--")
                || customWhere.contains("/*") || customWhere.contains("*/")) {
            throw new IllegalArgumentException("Invalid custom where clause");
        }

        Matcher matcher = CUSTOM_WHERE_TOKEN_PATTERN.matcher(customWhere);
        int index = 0;
        while (index < customWhere.length()) {
            if (!matcher.find(index) || matcher.start() != index) {
                throw new IllegalArgumentException("Unsupported token in custom where clause");
            }

            String token = matcher.group();
            if (!token.isBlank()) {
                validateCustomWhereToken(customWhere, token, matcher.end());
            }
            index = matcher.end();
        }
    }

    private void validateCustomWhereToken(String customWhere, String token, int nextIndex) {
        if (!Character.isLetter(token.charAt(0)) && token.charAt(0) != '_') {
            return;
        }

        String upperToken = token.toUpperCase(Locale.ROOT);
        if (BLOCKED_WHERE_KEYWORDS.contains(upperToken)) {
            throw new IllegalArgumentException("Invalid custom where clause");
        }
        if (ALLOWED_WHERE_KEYWORDS.contains(upperToken)) {
            return;
        }

        validateColumnName(token);
        int followingIndex = skipWhitespace(customWhere, nextIndex);
        if (followingIndex < customWhere.length() && customWhere.charAt(followingIndex) == '(') {
            throw new IllegalArgumentException("Function calls are not allowed in custom where clause");
        }
    }

    private int skipWhitespace(String value, int startIndex) {
        int index = startIndex;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    /**
     * Get the where clause as Optional.
     */
    public Optional<String> getWhereClause() {
        String where = buildWhereClause();
        return where != null && !where.trim().isEmpty() ? Optional.of(where) : Optional.empty();
    }

    /**
     * Validate segment configuration.
     */
    public void validate() {

        if (!hasRelationSource() && (tablePath == null || tablePath.isEmpty())) {
            throw new IllegalArgumentException("Table path cannot be null or empty");
        }

        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException("Key columns cannot be null or empty");
        }

        if (updateColumn.isPresent() && keyColumns.contains(updateColumn.get())) {
            throw new IllegalArgumentException("Update column cannot be a key column: " + updateColumn.get());
        }

        if ((minUpdate.isPresent() || maxUpdate.isPresent()) && updateColumn.isEmpty()) {
            throw new IllegalArgumentException("Update time bounds require update column to be specified");
        }

        if (minKey.isPresent() && maxKey.isPresent()) {
            List<Object> min = minKey.get();
            List<Object> max = maxKey.get();

            if (min.size() != keyColumns.size() || max.size() != keyColumns.size()) {
                throw new IllegalArgumentException("Key bounds must match key columns count");
            }

            if (min.size() != max.size()) {
                throw new IllegalArgumentException("Min and max key must have same size");
            }
        }
    }

    /**
     * Create linear checkpoints between min and max values.
     */
    private List<List<Object>> chooseLinearCheckpoints(List<Object> min, List<Object> max, int count) {
        List<List<Object>> checkpoints = new java.util.ArrayList<>();

        // Simple implementation: create evenly spaced checkpoints
        // We want 'count' checkpoints, so we divide the range into 'count + 1' segments
        for (int i = 1; i <= count; i++) {
            List<Object> checkpoint = new java.util.ArrayList<>();
            for (int j = 0; j < min.size(); j++) {
                Object minValue = min.get(j);
                Object maxValue = max.get(j);

                if (minValue instanceof Number && maxValue instanceof Number) {
                    double minVal = ((Number) minValue).doubleValue();
                    double maxVal = ((Number) maxValue).doubleValue();
                    double checkpointVal = minVal + (maxVal - minVal) * i / (count + 1);
                    checkpoint.add(checkpointVal);
                } else {
                    // For non-numeric values, skip checkpoint for this dimension
                    checkpoint.add(minValue);
                }
            }
            checkpoints.add(checkpoint);
        }

        return checkpoints;
    }

    /**
     * Format a value for SQL.
     */
    private String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        } else if (value instanceof String) {
            return "'" + escapeSQL((String) value) + "'";
        } else if (value instanceof Boolean) {
            return (Boolean) value ? "TRUE" : "FALSE";
        } else if (value instanceof java.time.temporal.TemporalAccessor) {
            return "'" + value.toString() + "'";
        } else if (value instanceof java.util.Date) {
            return "'" + new java.sql.Timestamp(((java.util.Date) value).getTime()) + "'";
        } else if (value instanceof Number) {
            return value.toString();
        } else {
            throw new IllegalArgumentException("Unsupported key value type: " + value.getClass().getName());
        }
    }

    private String escapeSQL(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("'", "''").replace("\\", "\\\\");
    }

    /**
     * Result of checksum calculation.
     */
    @Value
    @Builder
    public static class ChecksumResult {
        long count;
        String checksum;
        List<Object> minKey;
        List<Object> maxKey;

        public ChecksumResult(long count, String checksum, List<Object> minKey, List<Object> maxKey) {
            this.count = count;
            this.checksum = count > 0 ? checksum : null;
            this.minKey = minKey;
            this.maxKey = maxKey;
        }
    }
    
    /**
     * Represents LIMIT/OFFSET clause for row-based segmentation.
     * Used when key-based range segmentation is not applicable (e.g., non-numeric keys).
     */
    @Value
    public static class LimitOffset {
        long limit;
        long offset;
        
        public LimitOffset(long limit, long offset) {
            this.limit = limit;
            this.offset = offset;
        }
    }

    @Value
    public static class RelationSource {
        String fromSql;
        String displayName;
    }
}
