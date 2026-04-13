package com.consilens.core;

import com.consilens.core.algorithm.LocalDiffEngine;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffOperation;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * Simple demonstration of consilens functionality.
 */
@Slf4j
public class ConsilensDemo {

    public static void main(String[] args) {
        log.info("Starting consilens demonstration...");

        // Demo data - simulate two versions of a user table
        List<Object[]> table1Data = Arrays.asList(
                new Object[]{1, "Alice", "Engineering", 75000.00, "2023-01-15"},
                new Object[]{2, "Bob", "Marketing", 65000.00, "2023-02-20"},
                new Object[]{3, "Charlie", "Engineering", 80000.00, "2023-03-10"},
                new Object[]{4, "Diana", "Sales", 70000.00, "2023-04-05"}
        );

        List<Object[]> table2Data = Arrays.asList(
                new Object[]{1, "Alice", "Engineering", 78000.00, "2023-01-15"}, // salary changed
                new Object[]{2, "Bob", "Marketing", 65000.00, "2023-02-20"},
                new Object[]{3, "Charlie", "Engineering", 80000.00, "2023-03-10"},
                new Object[]{5, "Eve", "Engineering", 85000.00, "2023-05-12"}  // new employee
        );

        // Define table structure
        List<String> keyColumns = Arrays.asList("id");
        List<String> extraColumns = Arrays.asList("name", "department", "salary", "hire_date");

        log.info("Table 1 (Source): {} rows", table1Data.size());
        log.info("Table 2 (Target): {} rows", table2Data.size());

        // Perform diff analysis
        long startTime = System.currentTimeMillis();
        List<DiffRow> differences = LocalDiffEngine.findDifferences(
                table1Data, table2Data,
                keyColumns, extraColumns,
                keyColumns, extraColumns
        );
        long endTime = System.currentTimeMillis();

        // Display results
        log.info("Diff Analysis Results:");
        log.info("======================");
        log.info("Processing time: {} ms", (endTime - startTime));
        log.info("Total differences found: {}", differences.size());

        // Categorize differences
        long sourceMissingCount = 0, targetMissingCount = 0, mismatchCount = 0;

        for (DiffRow diff : differences) {
            switch (diff.getOperation()) {
                case SOURCE_MISSING:
                    sourceMissingCount++;
                    List<Object> targetData = diff.getTargetValues().get();
                    log.info("SOURCE_MISSING: User ID={}, Name={}, Salary={}",
                            targetData.get(0), targetData.get(1), targetData.get(3));
                    break;
                case TARGET_MISSING:
                    targetMissingCount++;
                    List<Object> sourceData = diff.getSourceValues().get();
                    log.info("TARGET_MISSING: User ID={}, Name={}, Salary={}",
                            sourceData.get(0), sourceData.get(1), sourceData.get(3));
                    break;
                case MISMATCH:
                    mismatchCount++;
                    List<Object> modifiedData = diff.getSourceValues().get();
                    log.info("MISMATCH: User ID={}, Name={}, Salary={}",
                            modifiedData.get(0), modifiedData.get(1), modifiedData.get(3));
                    break;
            }
        }

        log.info("======================");
        log.info("Summary:");
        log.info("  Source Missing: {} rows", sourceMissingCount);
        log.info("  Target Missing: {} rows", targetMissingCount);
        log.info("  Mismatched: {} rows", mismatchCount);
        log.info("  Unchanged: {} rows",
                Math.min(table1Data.size(), table2Data.size()) - mismatchCount);

        // Calculate difference percentage
        int totalRows = Math.max(table1Data.size(), table2Data.size());
        double differencePercentage = ((double) differences.size() / totalRows) * 100;
        log.info("  Difference: {:.2f}%", differencePercentage);

        log.info("consilens demonstration completed successfully!");

        // Demonstrate advanced features
        demonstrateAdvancedFeatures();
    }

    private static void demonstrateAdvancedFeatures() {
        log.info("\n=== Advanced Features Demo ===");

        // Test with compound keys
        log.info("Testing compound key comparison...");
        List<Object[]> compoundKey1 = Arrays.asList(
                new Object[]{1, "A", "Value1", 100},
                new Object[]{1, "B", "Value2", 200}
        );

        List<Object[]> compoundKey2 = Arrays.asList(
                new Object[]{1, "A", "Value1", 105}, // Updated
                new Object[]{2, "A", "Value3", 300}  // Added
        );

        List<String> compoundKeyColumns = Arrays.asList("id", "type");
        List<String> compoundExtraColumns = Arrays.asList("name", "value");

        List<DiffRow> compoundDifferences = LocalDiffEngine.findDifferences(
                compoundKey1, compoundKey2,
                compoundKeyColumns, compoundExtraColumns,
                compoundKeyColumns, compoundExtraColumns
        );

        log.info("Compound key diff found {} differences", compoundDifferences.size());
        for (DiffRow diff : compoundDifferences) {
            log.info("  {} operation", diff.getOperation());
        }

        // Performance test with larger dataset
        log.info("Testing performance with larger dataset...");
        performanceTest();
    }

    private static void performanceTest() {
        int size = 1000;
        List<Object[]> largeDataset1 = generateTestDataset(size, "original");
        List<Object[]> largeDataset2 = generateTestDataset(size + 50, "modified");

        long startTime = System.currentTimeMillis();
        List<DiffRow> largeDifferences = LocalDiffEngine.findDifferences(
                largeDataset1, largeDataset2,
                Arrays.asList("id"), Arrays.asList("name", "value", "status"),
                Arrays.asList("id"), Arrays.asList("name", "value", "status")
        );
        long endTime = System.currentTimeMillis();

        log.info("Performance test results:");
        log.info("  Dataset 1: {} rows", size);
        log.info("  Dataset 2: {} rows", size + 50);
        log.info("  Differences: {}", largeDifferences.size());
        log.info("  Processing time: {} ms", (endTime - startTime));
        log.info("  Throughput: {:.2f} rows/ms", (size * 2.0) / (endTime - startTime));
    }

    private static List<Object[]> generateTestDataset(int size, String prefix) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> new Object[]{
                        i + 1,
                        prefix + "_user_" + i,
                        i * 100,
                        i % 2 == 0 ? "active" : "inactive"
                })
                .collect(java.util.stream.Collectors.toList());
    }
}