package com.consilens.core.algorithm;

import com.consilens.connector.api.model.DataType;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffOperation;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Performs efficient in-memory comparison of row sets.
 */
@Slf4j
public class LocalDiffEngine {

    /**
     * Find differences between two sets of rows (backward compatibility).
     *
     * @param rows1         Rows from table 1
     * @param rows2         Rows from table 2
     * @param keyColumns1   Key column names for table 1
     * @param extraColumns1 Extra column names for table 1
     * @param keyColumns2   Key column names for table 2
     * @param extraColumns2 Extra column names for table 2
     * @return List of differences as DiffRow objects
     */
    public static List<DiffRow> findDifferences(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2) {
        // Call the new method without column types (backward compatibility)
        return findDifferences(rows1, rows2, keyColumns1, extraColumns1, keyColumns2, extraColumns2,
                Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * Find differences between two sets of rows (type-aware).
     *
     * @param rows1         Rows from table 1
     * @param rows2         Rows from table 2
     * @param keyColumns1   Key column names for table 1
     * @param extraColumns1 Extra column names for table 1
     * @param keyColumns2   Key column names for table 2
     * @param extraColumns2 Extra column names for table 2
     * @param columnTypes1  Column data types for table 1
     * @param columnTypes2  Column data types for table 2
     * @return List of differences as DiffRow objects
     */
    public static List<DiffRow> findDifferences(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2) {

        log.info("Finding differences between {} and {} rows", rows1.size(), rows2.size());

        // Group rows by primary keys so we can reason about ADDED/REMOVED/MODIFIED row-by-row
        Map<List<Object>, List<Object[]>> rowsByPk1 = groupRowsByPrimaryKey(rows1, keyColumns1.size());
        Map<List<Object>, List<Object[]>> rowsByPk2 = groupRowsByPrimaryKey(rows2, keyColumns2.size());

        // Find all unique primary keys
        Set<List<Object>> allPks = new HashSet<>();
        allPks.addAll(rowsByPk1.keySet());
        allPks.addAll(rowsByPk2.keySet());

        List<DiffRow> differences = new ArrayList<>();

        // Process each primary key
        for (List<Object> pk : allPks) {
            List<Object[]> pkRows1 = rowsByPk1.getOrDefault(pk, Collections.emptyList());
            List<Object[]> pkRows2 = rowsByPk2.getOrDefault(pk, Collections.emptyList());

        // Extract relevant columns (excluding ignored columns)
        List<Object[]> relevantRows1 = extractRelevantColumns(pkRows1, keyColumns1, extraColumns1);
        List<Object[]> relevantRows2 = extractRelevantColumns(pkRows2, keyColumns2, extraColumns2);

            // Determine differences for this primary key
            List<String> columnNames1 = new ArrayList<>(keyColumns1);
            columnNames1.addAll(extraColumns1);
            List<String> columnNames2 = new ArrayList<>(keyColumns2);
            columnNames2.addAll(extraColumns2);
            List<DiffRow> pkDifferences = findPrimaryKeyDifferences(pk, pkRows1, pkRows2, relevantRows1, relevantRows2,
                    columnNames1, columnNames2, columnTypes1, columnTypes2);
            differences.addAll(pkDifferences);
        }

        log.info("Found {} total differences", differences.size());
        return differences;
    }

    /**
     * Group rows by their primary key values.
     */
    private static Map<List<Object>, List<Object[]>> groupRowsByPrimaryKey(List<Object[]> rows, int keyColumnCount) {
        Map<List<Object>, List<Object[]>> groupedRows = new HashMap<>();

        for (Object[] row : rows) {
            // Copy only the PK portion so duplicate rows with the same key compress into the same bucket.
            List<Object> primaryKey = Arrays.asList(Arrays.copyOf(row, keyColumnCount));
            groupedRows.computeIfAbsent(primaryKey, k -> new ArrayList<>()).add(row);
        }

        return groupedRows;
    }

    /**
     * Extract relevant columns from rows, excluding ignored columns.
     */
    private static List<Object[]> extractRelevantColumns(List<Object[]> rows, List<String> keyColumns,
            List<String> extraColumns) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        // For this implementation, assume all columns are relevant
        // In a more complex version, this would handle ignored columns
        return rows.stream()
                .map(row -> Arrays.copyOf(row, row.length))
                .collect(Collectors.toList());
    }

    /**
     * Find differences for rows with the same primary key.
     */
    private static List<DiffRow> findPrimaryKeyDifferences(
            List<Object> primaryKey,
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<Object[]> relevantRows1,
            List<Object[]> relevantRows2,
            List<String> columnNames1,
            List<String> columnNames2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2) {

        List<DiffRow> differences = new ArrayList<>();

        // Cases to handle:
        // 1. Rows exist only in table 1 -> REMOVED
        // 2. Rows exist only in table 2 -> ADDED
        // 3. Rows exist in both but have different values -> MODIFIED
        // 4. Multiple rows with same PK in either table -> handle duplicates

        if (relevantRows1.isEmpty() && !relevantRows2.isEmpty()) {
            // Rows only in table 2, therefore ADDED
            for (Object[] row2 : rows2) {
                List<Object> values = Arrays.asList(row2);
                differences.add(DiffRow.added(primaryKey, values, columnNames2));
            }
        } else if (!relevantRows1.isEmpty() && relevantRows2.isEmpty()) {
            // Rows only in table 1, therefore REMOVED
            for (Object[] row1 : rows1) {
                List<Object> values = Arrays.asList(row1);
                differences.add(DiffRow.removed(primaryKey, values, columnNames1));
            }
        } else if (!relevantRows1.isEmpty()) {
            // Rows exist in both tables
            if (relevantRows1.size() == 1 && relevantRows2.size() == 1) {
                // Single row in each - compare values
                Object[] row1 = relevantRows1.get(0);
                Object[] row2 = relevantRows2.get(0);
                if (!rowsEqualNormalized(row1, row2, columnNames1, columnTypes1, columnTypes2)) {
                    // Values differ - identify which columns changed separately for each table
                    List<String> changedColumns1 = new ArrayList<>();
                    List<String> changedColumns2 = new ArrayList<>();
                    // Use columnNames1 for finding changed columns since both tables should have
                    // same structure for comparison
                    findChangedColumnsWithDetails(row1, row2, columnNames1, changedColumns1, changedColumns2,
                            columnTypes1, columnTypes2);
                    log.debug("Creating modified diff row with changed columns1: {}, columns2: {} for PK: {}",
                            changedColumns1, changedColumns2, primaryKey);
                    // Normalize values for output to ensure consistent formatting
                    List<Object> sourceValues = normalizeRowValues(row1, columnNames1, columnTypes1);
                    List<Object> targetValues = normalizeRowValues(row2, columnNames2, columnTypes2);
                    differences.add(DiffRow.modified(primaryKey, sourceValues, targetValues, columnNames1, columnNames2,
                            changedColumns1, changedColumns2));
                }
            } else {
                /*
                 * Multiple physical rows share the same logical primary key on one/both sides.
                 * Because this violates the uniqueness assumption, surface every instance as a
                 * diff: rows from table1 become REMOVED, rows from table2 become ADDED.
                 * This makes the conflict explicit in downstream reports instead of silently
                 * choosing an arbitrary representative.
                 */
                for (Object[] row1 : rows1) {
                    List<Object> values = Arrays.asList(row1);
                    differences.add(DiffRow.removed(primaryKey, values, columnNames1));
                }
                for (Object[] row2 : rows2) {
                    List<Object> values = Arrays.asList(row2);
                    differences.add(DiffRow.added(primaryKey, values, columnNames2));
                }
            }
        }

        return differences;
    }

    /**
     * Find which columns have changed between two rows.
     */
    private static List<Integer> findChangedColumns(Object[] row1, Object[] row2) {
        List<String> changedColumns1 = new ArrayList<>();
        List<String> changedColumns2 = new ArrayList<>();
        return findChangedColumnsWithDetails(row1, row2, null, changedColumns1, changedColumns2);
    }

    /**
     * Find which columns have changed between two rows with separate tracking for
     * each table.
     */
    private static List<Integer> findChangedColumnsWithDetails(Object[] row1, Object[] row2, List<String> columnNames,
            List<String> changedColumns1, List<String> changedColumns2) {
        List<Integer> changedColumnIndices = new ArrayList<>();

        log.debug("Finding changed columns between rows with lengths: {} and {}", row1.length, row2.length);

        if (row1.length != row2.length) {
            // If rows have different lengths, consider all columns as changed
            log.debug("Rows have different lengths, marking all columns as changed");
            for (int i = 0; i < Math.max(row1.length, row2.length); i++) {
                changedColumnIndices.add(i);
                if (columnNames != null && i < columnNames.size()) {
                    String columnName = columnNames.get(i);
                    if (i < row1.length)
                        changedColumns1.add(columnName);
                    if (i < row2.length)
                        changedColumns2.add(columnName);
                }
            }
            return changedColumnIndices;
        }

        for (int i = 0; i < row1.length; i++) {
            Object val1 = row1[i];
            Object val2 = row2[i];

            if (!valuesEqual(val1, val2)) {
                changedColumnIndices.add(i);
                if (columnNames != null && i < columnNames.size()) {
                    String columnName = columnNames.get(i);
                    changedColumns1.add(columnName);
                    changedColumns2.add(columnName);
                    log.debug("Column {} ({}) changed: '{}' -> '{}'", i, columnName, val1, val2);
                } else {
                    log.debug("Column {} changed: '{}' -> '{}'", i, val1, val2);
                }
            }
        }

        log.debug("Found {} changed columns: {}", changedColumnIndices.size(), changedColumnIndices);
        return changedColumnIndices;
    }

    /**
     * Find which columns have changed between two rows with separate tracking for
     * each table (type-aware).
     */
    private static List<Integer> findChangedColumnsWithDetails(
            Object[] row1, Object[] row2, List<String> columnNames,
            List<String> changedColumns1, List<String> changedColumns2,
            Map<String, DataType> columnTypes1, Map<String, DataType> columnTypes2) {

        List<Integer> changedColumnIndices = new ArrayList<>();

        log.debug("Finding changed columns between rows with lengths: {} and {}", row1.length, row2.length);

        if (row1.length != row2.length) {
            // If rows have different lengths, consider all columns as changed
            log.debug("Rows have different lengths, marking all columns as changed");
            for (int i = 0; i < Math.max(row1.length, row2.length); i++) {
                changedColumnIndices.add(i);
                if (columnNames != null && i < columnNames.size()) {
                    String columnName = columnNames.get(i);
                    if (i < row1.length)
                        changedColumns1.add(columnName);
                    if (i < row2.length)
                        changedColumns2.add(columnName);
                }
            }
            return changedColumnIndices;
        }

        for (int i = 0; i < row1.length; i++) {
            Object val1 = row1[i];
            Object val2 = row2[i];

            boolean changed = false;
            String columnName = (columnNames != null && i < columnNames.size()) ? columnNames.get(i) : null;

            // CRITICAL: In NONE mode, rows contain database-normalized values (strings)
            // Direct string comparison is sufficient - no need for ValueNormalizer
            // This ensures consistency with database-side normalization
            if (!valuesEqual(val1, val2)) {
                changed = true;
                log.trace("Column {} values differ: '{}' vs '{}'", columnName != null ? columnName : i, val1, val2);
            }

            if (changed) {
                changedColumnIndices.add(i);
                if (columnName != null) {
                    changedColumns1.add(columnName);
                    changedColumns2.add(columnName);
                }
            }
        }

        log.debug("Found {} changed columns: {}", changedColumnIndices.size(), changedColumnIndices);
        return changedColumnIndices;
    }

    /**
     * Check if two rows are equal.
     */
    private static boolean rowsEqual(Object[] row1, Object[] row2) {
        if (row1.length != row2.length) {
            return false;
        }

        for (int i = 0; i < row1.length; i++) {
            Object val1 = row1[i];
            Object val2 = row2[i];

            if (val1 == null && val2 == null) {
                continue;
            }
            if (val1 == null || val2 == null) {
                return false;
            }

            // Handle special comparison cases
            if (!valuesEqual(val1, val2)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Compare two values with special handling for different types.
     */
    private static boolean valuesEqual(Object val1, Object val2) {
        boolean result = compareValuesInternal(val1, val2);
        log.trace("Comparing values: '{}' vs '{}' = {}", val1, val2, result);
        return result;
    }

    /**
     * Internal method to compare two values with special handling for different
     * types.
     */
    private static boolean compareValuesInternal(Object val1, Object val2) {
        // Handle null cases
        if (val1 == null && val2 == null) {
            return true;
        }
        if (val1 == null || val2 == null) {
            return false;
        }

        // Handle YEAR column: java.sql.Date (MySQL) vs Integer (PostgreSQL)
        // MySQL YEAR returns Date like "2092-01-01", PostgreSQL returns Integer 2092
        if ((val1 instanceof java.sql.Date && val2 instanceof Number) ||
            (val1 instanceof Number && val2 instanceof java.sql.Date)) {
            int year1 = extractYear(val1);
            int year2 = extractYear(val2);
            return year1 == year2;
        }

        // Handle numeric types with tolerance
        if (val1 instanceof Number && val2 instanceof Number) {
            return compareNumericValues((Number) val1, (Number) val2);
        }

        // Handle string comparison with normalization
        if (val1 instanceof String || val2 instanceof String) {
            String str1 = normalizeStringValue(val1);
            String str2 = normalizeStringValue(val2);
            return str1.equals(str2);
        }

        // Handle temporal types
        if (val1 instanceof java.time.Instant && val2 instanceof java.time.Instant) {
            return val1.equals(val2);
        }

        if (val1 instanceof java.sql.Timestamp && val2 instanceof java.sql.Timestamp) {
            long time1 = ((java.sql.Timestamp) val1).getTime();
            long time2 = ((java.sql.Timestamp) val2).getTime();
            
            // Check for 8-hour timezone difference (28800000 ms = 8 hours)
            // This handles cross-database comparison where MySQL may apply timezone conversion
            // while PostgreSQL doesn't
            long diff = Math.abs(time1 - time2);
            if (diff == 28800000L) {
                // 8 hours difference, likely timezone issue, consider them equal
                return true;
            }
            
            return time1 == time2;
        }

        if (val1 instanceof java.util.Date && val2 instanceof java.util.Date) {
            long time1 = ((java.util.Date) val1).getTime();
            long time2 = ((java.util.Date) val2).getTime();
            
            // Check for 8-hour timezone difference
            long diff = Math.abs(time1 - time2);
            if (diff == 28800000L) {
                return true;
            }
            
            return time1 == time2;
        }

        // Handle boolean types
        if (val1 instanceof Boolean && val2 instanceof Boolean) {
            return val1.equals(val2);
        }

        // Handle binary data
        if (val1 instanceof byte[] && val2 instanceof byte[]) {
            return java.util.Arrays.equals((byte[]) val1, (byte[]) val2);
        }

        // Default comparison
        return Objects.equals(val1, val2);
    }

    /**
     * Extract year from Date or Number object.
     * Used for comparing MySQL YEAR (returns Date) with PostgreSQL SMALLINT (returns Integer).
     */
    private static int extractYear(Object value) {
        if (value instanceof java.sql.Date) {
            // MySQL YEAR returns Date like "2092-01-01"
            return ((java.sql.Date) value).toLocalDate().getYear();
        } else if (value instanceof Number) {
            // PostgreSQL SMALLINT returns Integer like 2092
            return ((Number) value).intValue();
        } else {
            throw new IllegalArgumentException("Cannot extract year from: " + value.getClass());
        }
    }

    /**
     * Compare numeric values with appropriate tolerance.
     */
    private static boolean compareNumericValues(Number num1, Number num2) {
        // Handle integer types exactly
        if (num1 instanceof Long || num2 instanceof Long ||
                num1 instanceof Integer || num2 instanceof Integer ||
                num1 instanceof Short || num2 instanceof Short ||
                num1 instanceof Byte || num2 instanceof Byte) {
            return num1.longValue() == num2.longValue();
        }

        // Handle floating point types with tolerance
        double d1 = num1.doubleValue();
        double d2 = num2.doubleValue();

        // Use relative tolerance for floating point comparison
        // Tolerance of 1e-6 allows for typical decimal precision differences (up to
        // 0.0001% relative error)
        // This accommodates differences like 24970.810011 vs 24970.81 (difference of
        // 0.000011)
        double tolerance = 1e-6;
        double diff = Math.abs(d1 - d2);
        double scale = Math.max(Math.max(Math.abs(d1), Math.abs(d2)), 1.0);

        return diff / scale <= tolerance;
    }

    /**
     * Normalize string values for consistent comparison.
     */
    private static String normalizeStringValue(Object value) {
        if (value == null) {
            return "";
        }

        String str = value.toString().trim();

        return str;
    }

    /**
     * Normalize all values in a row for consistent output formatting.
     * CRITICAL: In NONE mode, rows already contain database-normalized values (strings).
     * This method now simply returns the values as-is, since normalization
     * has already been done at the database level.
     *
     * @param row         The row to normalize
     * @param columnNames Column names
     * @param columnTypes Column data types
     * @return List of values (already normalized by database)
     */
    private static List<Object> normalizeRowValues(
            Object[] row,
            List<String> columnNames,
            Map<String, DataType> columnTypes) {

        // CRITICAL: Rows already contain database-normalized values
        // No need for additional normalization with ValueNormalizer
        List<Object> normalizedValues = new ArrayList<>(row.length);
        for (Object value : row) {
            normalizedValues.add(value);
        }
        return normalizedValues;
    }

    /**
     * Compare two rows with type-aware normalization.
     * CRITICAL: In NONE mode, rows already contain database-normalized values (strings).
     * Direct comparison is sufficient - no need for ValueNormalizer.
     *
     * @param row1         First row
     * @param row2         Second row
     * @param columnNames  Column names (used to match column types)
     * @param columnTypes1 Column data types for table 1
     * @param columnTypes2 Column data types for table 2
     * @return true if rows are equal after normalization
     */
    private static boolean rowsEqualNormalized(
            Object[] row1,
            Object[] row2,
            List<String> columnNames,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2) {

        if (row1.length != row2.length) {
            return false;
        }

        // CRITICAL: Rows already contain database-normalized values
        // Direct comparison is sufficient
        for (int i = 0; i < row1.length; i++) {
            Object val1 = row1[i];
            Object val2 = row2[i];

            if (!valuesEqual(val1, val2)) {
                String columnName = i < columnNames.size() ? columnNames.get(i) : String.valueOf(i);
                log.trace("Column {} values differ: '{}' vs '{}'", columnName, val1, val2);
                return false;
            }
        }

        return true;
    }

    /**
     * Advanced diff engine with additional optimizations.
     */
    public static class AdvancedDiffEngine {

        /**
         * Find differences with memory-efficient streaming approach for large datasets.
         */
        public static List<DiffRow> findDifferencesStreaming(
                Iterator<Object[]> rows1Iterator,
                Iterator<Object[]> rows2Iterator,
                int keyColumnCount,
                List<String> columnNames1,
                List<String> columnNames2) {

            Map<List<Object>, Object[]> rows1Map = new HashMap<>();
            Map<List<Object>, Object[]> rows2Map = new HashMap<>();

            // Stream first dataset
            while (rows1Iterator.hasNext()) {
                Object[] row = rows1Iterator.next();
                List<Object> pk = Arrays.asList(Arrays.copyOf(row, keyColumnCount));
                rows1Map.put(pk, row);
            }

            // Stream second dataset and find differences
            List<DiffRow> differences = new ArrayList<>();

            while (rows2Iterator.hasNext()) {
                Object[] row2 = rows2Iterator.next();
                List<Object> pk = Arrays.asList(Arrays.copyOf(row2, keyColumnCount));

                Object[] row1 = rows1Map.remove(pk);

                if (row1 == null) {
                    // Row only exists in table 2
                    List<Object> values = Arrays.asList(row2);
                    differences.add(DiffRow.added(pk, values, columnNames2));
                } else {
                    // Row exists in both - compare
                    if (!rowsEqual(row1, row2)) {
                        List<String> changedColumns1 = new ArrayList<>();
                        List<String> changedColumns2 = new ArrayList<>();
                        findChangedColumnsWithDetails(row1, row2, columnNames1, changedColumns1, changedColumns2);
                        log.debug(
                                "AdvancedDiffEngine: Creating modified diff row with changed columns1: {}, columns2: {} for PK: {}",
                                changedColumns1, changedColumns2, pk);
                        List<Object> sourceValues = Arrays.asList(row1);
                        List<Object> targetValues = Arrays.asList(row2);
                        differences.add(DiffRow.modified(pk, sourceValues, targetValues, columnNames1, columnNames2,
                                changedColumns1, changedColumns2));
                    }
                }
            }

            // Remaining rows only exist in table 1
            for (Map.Entry<List<Object>, Object[]> entry : rows1Map.entrySet()) {
                List<Object> values = Arrays.asList(entry.getValue());
                differences.add(DiffRow.removed(entry.getKey(), values, columnNames1));
            }

            return differences;
        }

        /**
         * Find differences with parallel processing for large datasets.
         */
        public static List<DiffRow> findDifferencesParallel(
                List<Object[]> rows1,
                List<Object[]> rows2,
                int keyColumnCount,
                int parallelism,
                List<String> columnNames1,
                List<String> columnNames2) {

            // Partition data by primary key ranges for parallel processing
            Map<Integer, List<Object[]>> partitioned1 = partitionByPkHash(rows1, keyColumnCount, parallelism);
            Map<Integer, List<Object[]>> partitioned2 = partitionByPkHash(rows2, keyColumnCount, parallelism);

            List<DiffRow> allDifferences = new ArrayList<>();

            // Process partitions in parallel
            partitioned1.entrySet().parallelStream().forEach(entry -> {
                int partitionId = entry.getKey();
                List<Object[]> partition1Rows = entry.getValue();
                List<Object[]> partition2Rows = partitioned2.getOrDefault(partitionId, Collections.emptyList());

                List<DiffRow> partitionDifferences = findDifferences(
                        partition1Rows, partition2Rows,
                        Collections.nCopies(keyColumnCount, "key" + partitionId),
                        Collections.emptyList(),
                        Collections.nCopies(keyColumnCount, "key" + partitionId),
                        Collections.emptyList());

                synchronized (allDifferences) {
                    allDifferences.addAll(partitionDifferences);
                }
            });

            return allDifferences;
        }

        /**
         * Partition rows by primary key hash for parallel processing.
         */
        private static Map<Integer, List<Object[]>> partitionByPkHash(
                List<Object[]> rows, int keyColumnCount, int partitions) {

            Map<Integer, List<Object[]>> partitioned = new HashMap<>();

            for (Object[] row : rows) {
                List<Object> pk = Arrays.asList(Arrays.copyOf(row, keyColumnCount));
                int partitionId = Math.abs(pk.hashCode() % partitions);

                partitioned.computeIfAbsent(partitionId, k -> new ArrayList<>()).add(row);
            }

            return partitioned;
        }
    }
}
