package com.consilens.core.segment;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.connector.api.model.DataType;
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
            "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "TRUE", "FALSE",
            // CAST enables typed literals (e.g. CAST('2026-05-01' AS DATE)) required by
            // strict-typed dialects (Trino/Presto) that reject date-vs-varchar comparison
            "CAST");

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

    @Builder.Default
    private boolean upperBoundInclusive = false;

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
                    .upperBoundInclusive(false)
                    .build();
            segments.add(segment);
            previousPoint = checkpoint;
        }

        // Add final segment
        TableSegment finalSegment = toBuilder()
                .minKey(Optional.of(previousPoint))
                .maxKey(maxKey)
                .upperBoundInclusive(upperBoundInclusive)
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

        // Add key range conditions. The lower and upper bounds are independent:
        // tail scans build one-sided segments (e.g. minKey=null, maxKey=<other min>),
        // and previously gating the whole block on minKey dropped the upper predicate,
        // turning that scan into a full-table scan reported as one-sided missing rows.
        boolean hasMinKey = minKey != null && minKey.isPresent();
        boolean hasMaxKey = maxKey != null && maxKey.isPresent();
        if (keyColumns != null && !keyColumns.isEmpty() && (hasMinKey || hasMaxKey)) {
            if (keyColumns.size() == 1) {
                String column = keyColumns.get(0);

                // NULL 下界（整列全 NULL 时 MIN 为 NULL）即段起点，无需下界谓词；
                // 「NULL 排在最前」口径下所有行（含 NULL 行）都在该段内。
                if (hasMinKey) {
                    Object minValue = minKey.get().get(0);
                    if (minValue != null) {
                        whereBuilder.append(String.format("%s >= %s", column,
                                formatValue(minValue, columnType(column))));
                    }
                }

                if (hasMaxKey) {
                    Object maxValue = maxKey.get().get(0);
                    String upperCondition;
                    if (maxValue != null) {
                        upperCondition = String.format("%s %s %s",
                                column,
                                upperBoundInclusive ? "<=" : "<",
                                formatValue(maxValue, columnType(column)));
                    } else if (upperBoundInclusive) {
                        // NULL 上界：<= NULL 按「NULL 排在最前」口径仅 NULL 行满足，
                        // 直接丢弃谓词会把上界之外的行也纳入本段（与复合键分支同理）。
                        upperCondition = String.format("%s IS NULL", column);
                    } else {
                        // NULL 上界：< NULL 恒假（NULL 是最小值），该段无行可选
                        upperCondition = "1 = 0";
                    }
                    if (whereBuilder.length() > 0) {
                        whereBuilder.append(" AND ");
                    }
                    whereBuilder.append(upperCondition);
                }
            } else {
                if (hasMinKey) {
                    String lowerBound = buildCompositeLowerBound(minKey.get());
                    if (whereBuilder.length() > 0) {
                        whereBuilder.append(" AND ");
                    }
                    whereBuilder.append(lowerBound == null ? "1 = 0" : lowerBound);
                }
                if (hasMaxKey) {
                    String upperBound = buildCompositeUpperBound(maxKey.get());
                    if (whereBuilder.length() > 0) {
                        whereBuilder.append(" AND ");
                    }
                    whereBuilder.append(upperBound == null ? "1 = 0" : upperBound);
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
     * Uses ANSI standard DATE/TIMESTAMP literals for temporal types to ensure
     * cross-database compatibility (especially for Presto/Trino strict type checking),
     * except for SQL Server which does not support ANSI date literals and requires
     * plain quoted strings that it can implicitly convert.
     */
    private String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        } else if (value instanceof Number) {
            return value.toString();
        } else if (value instanceof Boolean) {
            return (Boolean) value ? "TRUE" : "FALSE";
        } else if (value instanceof java.time.LocalDate) {
            return formatTemporalLiteral(value.toString());
        } else if (value instanceof java.time.LocalDateTime) {
            return formatTemporalLiteral(value.toString());
        } else if (value instanceof java.time.temporal.TemporalAccessor) {
            // Other temporal types (e.g., Instant, ZonedDateTime) - format as DATE if possible
            return formatTemporalLiteral(value.toString());
        } else if (value instanceof java.sql.Date) {
            return formatTemporalLiteral(value.toString());
        } else if (value instanceof java.sql.Timestamp) {
            return formatTemporalLiteral(value.toString());
        } else if (value instanceof java.util.Date) {
            return formatTemporalLiteral(new java.sql.Timestamp(((java.util.Date) value).getTime()).toString());
        } else if (value instanceof String) {
            // Check if the string can be parsed as a number (for cross-database compatibility)
            // Some JDBC drivers (Trino, Presto) return numeric columns as strings
            String strVal = (String) value;
            try {
                // Try to parse as a number
                if (strVal.contains(".")) {
                    Double.parseDouble(strVal);
                    return strVal; // Return without quotes
                } else {
                    Long.parseLong(strVal);
                    return strVal; // Return without quotes
                }
            } catch (NumberFormatException e) {
                // Not a number - check if it looks like a date/timestamp
                // Presto/Trino JDBC drivers may return DATE columns as strings like "2026-05-01"
                if (strVal.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    return formatTemporalLiteral(strVal);
                } else if (strVal.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}.*")) {
                    return formatTemporalLiteral(strVal);
                }
                // Regular string
                return "'" + escapeSQL(strVal) + "'";
            }
        } else {
            throw new IllegalArgumentException("Unsupported key value type: " + value.getClass().getName());
        }
    }

    /**
     * 复合键下界：{@code (k1 > min1) OR (k1 = min1 AND k2 >= min2) ...}。
     *
     * <p>边界分量可能是 NULL（键列允许 NULL 时 MIN/MAX 会取到 NULL）。SQL 中
     * {@code k > NULL} / {@code k = NULL} 恒为 UNKNOWN，整个谓词会退化为假，导致分段查询
     * 取不到任何行。按「NULL 排在最前」的分段口径改写：{@code = NULL} → {@code IS NULL}，
     * {@code > NULL} → {@code IS NOT NULL}。最后一维为 NULL 时它已经是该前缀下的最小值，
     * 下界退化为前缀等值本身（不能再生成 {@code k IS NOT NULL}，否则会排除该维为 NULL 的行）。
     */
    private String buildCompositeLowerBound(List<Object> minValues) {
        StringBuilder lowerBound = new StringBuilder("(");
        boolean hasTerm = false;
        for (int i = 0; i < keyColumns.size(); i++) {
            boolean last = i == keyColumns.size() - 1;
            String comparison;
            if (last && minValues.get(i) == null) {
                // NULL 是最后一维的最小值：前缀相等即满足 >=，不应再限制该维
                comparison = null;
            } else {
                comparison = compositeKeyComparison(keyColumns.get(i), last ? ">=" : ">", minValues.get(i));
                if (comparison == null) {
                    continue;
                }
            }
            if (comparison == null && i == 0) {
                continue;
            }
            if (hasTerm) {
                lowerBound.append(" OR (");
            }
            for (int j = 0; j < i; j++) {
                if (j > 0) {
                    lowerBound.append(" AND ");
                }
                lowerBound.append(compositeKeyComparison(keyColumns.get(j), "=", minValues.get(j)));
            }
            if (comparison != null) {
                if (i > 0) {
                    lowerBound.append(" AND ");
                }
                lowerBound.append(comparison);
            }
            if (hasTerm) {
                lowerBound.append(")");
            }
            hasTerm = true;
        }
        if (!hasTerm) {
            return null;
        }
        lowerBound.append(")");
        return lowerBound.toString();
    }

    /**
     * 复合键上界：{@code (k1 < max1) OR (k1 = max1 AND k2 <= max2) ...}。
     * 最后一位是否为闭区间由 {@link #upperBoundInclusive} 决定。
     */
    private String buildCompositeUpperBound(List<Object> maxValues) {
        StringBuilder upperBound = new StringBuilder("(");
        boolean hasTerm = false;
        for (int i = 0; i < keyColumns.size(); i++) {
            String comparison = compositeKeyComparison(keyColumns.get(i),
                    (upperBoundInclusive && i == keyColumns.size() - 1) ? "<=" : "<",
                    maxValues.get(i));
            if (comparison == null) {
                continue;
            }
            if (hasTerm) {
                upperBound.append(" OR (");
            }
            for (int j = 0; j < i; j++) {
                String prefix = compositeKeyComparison(keyColumns.get(j), "=", maxValues.get(j));
                upperBound.append(prefix).append(" AND ");
            }
            upperBound.append(comparison);
            if (hasTerm) {
                upperBound.append(")");
            }
            hasTerm = true;
        }
        if (!hasTerm) {
            return null;
        }
        upperBound.append(")");
        return upperBound.toString();
    }

    /**
     * 复合键分量的 SQL 比较片段。
     *
     * <p>边界值为 NULL 时不能用 {@code =} / {@code >} / {@code <} 表达（SQL 中与 NULL 比较恒为
     * UNKNOWN），需按「NULL 排在最前」的分段口径改写：{@code =} / {@code <= NULL} 改
     * {@code IS NULL}，{@code >} / {@code >= NULL} 改 {@code IS NOT NULL}；当该备选项恒假
     * （{@code < NULL}）时返回 {@code null} 交由调用方丢弃。
     */
    private String compositeKeyComparison(String column, String operator, Object boundValue) {
        if (boundValue != null) {
            return String.format("%s %s %s", column, operator,
                    formatValue(boundValue, columnType(column)));
        }
        if ("=".equals(operator) || "<=".equals(operator)) {
            return String.format("%s IS NULL", column);
        }
        if (">".equals(operator) || ">=".equals(operator)) {
            return String.format("%s IS NOT NULL", column);
        }
        return null;
    }

    /**
     * Format a value for SQL, quoting string literals unless the column type is numeric.
     *
     * <p>Without the type, "00123" or "1.5" strings would be emitted unquoted and a
     * VARCHAR primary key would be compared through implicit conversion, which can
     * shift segment boundaries or miss rows. When the schema is available the type
     * decides; otherwise the legacy shape-based behavior is kept for drivers that
     * return numeric columns as strings.
     */
    private String formatValue(Object value, DataType dataType) {
        if (value instanceof String && dataType != null) {
            if (isNumericType(dataType)) {
                return value.toString();
            }
            if (isCharacterType(dataType)) {
                return "'" + escapeSQL((String) value) + "'";
            }
        }
        return formatValue(value);
    }

    private boolean isNumericType(DataType dataType) {
        switch (dataType) {
            case INTEGER:
            case BIGINT:
            case SMALLINT:
            case TINYINT:
            case DECIMAL:
            case NUMERIC:
            case FLOAT:
            case DOUBLE:
            case REAL:
                return true;
            default:
                return false;
        }
    }

    private boolean isCharacterType(DataType dataType) {
        switch (dataType) {
            case VARCHAR:
            case CHAR:
            case TEXT:
            case CLOB:
            case LONGVARCHAR:
                return true;
            default:
                return false;
        }
    }

    private DataType columnType(String columnName) {
        if (schema == null || !schema.isPresent()) {
            return null;
        }
        return schema.get().getColumnType(columnName);
    }

    /**
     * Format a temporal value as a SQL literal. Dialects may opt out of ANSI
     * temporal literals (SQL Server only accepts plain quoted strings); the
     * decision belongs to the dialect, not to this segment.
     */
    private String formatTemporalLiteral(String value) {
        DatabaseDialect dialect = database != null ? database.getDialect() : null;
        if (dialect != null && !dialect.usesAnsiTemporalLiterals()) {
            return "'" + escapeSQL(value) + "'";
        }
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return "DATE '" + value + "'";
        }
        // Presto/Trino TIMESTAMP literals require a space separator, while
        // LocalDateTime.toString() uses 'T'; normalize to the ANSI form.
        return "TIMESTAMP '" + value.replace('T', ' ') + "'";
    }

    private String escapeSQL(String value) {
        if (value == null) {
            return null;
        }
        String escaped = value.replace("'", "''");
        // 反斜杠是否是字符串转义符由方言决定（MySQL 族为是，PostgreSQL/Trino/Oracle/
        // SQL Server 等标准实现为否）。无条件加倍会让这些库上的键边界变成另一个值，
        // 使边界行落不进任何分段谓词。
        DatabaseDialect dialect = database != null ? database.getDialect() : null;
        SqlQueryGenerator generator = dialect != null ? dialect.getSqlQueryGenerator() : null;
        if (generator == null || generator.backslashEscapesStringLiterals()) {
            escaped = escaped.replace("\\", "\\\\");
        }
        return escaped;
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
