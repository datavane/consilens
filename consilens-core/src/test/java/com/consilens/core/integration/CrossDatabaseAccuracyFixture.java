package com.consilens.core.integration;

import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.segment.TableSegment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared accuracy contract for real cross-database comparisons.
 *
 * <p>The fixture deliberately compares every column it creates. This prevents a Connector from
 * appearing healthy when a temporal, boolean, floating-point or nullable field was merely seeded
 * but never included in the checksum and local row comparison.</p>
 */
final class CrossDatabaseAccuracyFixture {

    private static final List<String> SOURCE_COLUMNS = List.of(
            "name", "decimal_value", "floating_value", "created_date", "created_at", "is_active", "nullable_text");
    private CrossDatabaseAccuracyFixture() {
    }

    static void verify(DatabaseAdapter source, TablePath sourcePath,
                       DatabaseAdapter target, TablePath targetPath) throws Exception {
        configureComparisonSession(source);
        configureComparisonSession(target);
        String identicalSource = "accuracy_identical_source";
        String identicalTarget = targetTableName(target, "accuracy_identical_target");
        createTable(source, identicalSource);
        createTable(target, identicalTarget);
        insertRows(source, identicalSource, List.of(1, 2, 3, 4), false);
        insertRows(target, identicalTarget, List.of(1, 2, 3, 4), false);

        DiffResult identical = compare(source, sourcePath.withTableName(identicalSource), SOURCE_COLUMNS,
                target, targetPath.withTableName(identicalTarget), SOURCE_COLUMNS, 1, 6);
        assertThat(identical.getDifferences())
                .as("all seeded public type families must normalize identically")
                .isEmpty();
        assertThat(identical.getStatistics().getSourceRowCount()).isEqualTo(4);
        assertThat(identical.getStatistics().getTargetRowCount()).isEqualTo(4);

        String sourceTable = "accuracy_diff_source";
        String targetTable = targetTableName(target, "accuracy_diff_target");
        createTable(source, sourceTable);
        createTable(target, targetTable);
        insertRows(source, sourceTable, List.of(1, 2, 3), false);
        insertRows(target, targetTable, List.of(1, 2, 4), true);

        DiffResult result = compare(source, sourcePath.withTableName(sourceTable), SOURCE_COLUMNS,
                target, targetPath.withTableName(targetTable), SOURCE_COLUMNS, 1, 6);

        Map<DiffOperation, List<DiffRow>> byOperation = result.getDifferencesByOperation();
        assertThat(byOperation.getOrDefault(DiffOperation.MISMATCH, List.of()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getPrimaryKeyString()).isEqualTo("2");
                    assertThat(lowercase(row.getChangedColumns1())).containsExactlyInAnyOrderElementsOf(SOURCE_COLUMNS);
                    assertThat(lowercase(row.getChangedColumns2())).containsExactlyInAnyOrderElementsOf(SOURCE_COLUMNS);
                    assertThat(row.getSourceValues()).isPresent();
                    assertThat(row.getTargetValues()).isPresent();
                });
        assertMissing(byOperation, DiffOperation.TARGET_MISSING, "3", true, false);
        assertMissing(byOperation, DiffOperation.SOURCE_MISSING, "4", false, true);
        assertThat(result.getDifferences()).hasSize(3);
        if ("oracle".equalsIgnoreCase(target.getConnectorType())) {
            verifyOracleDateTimeSemantics(source, sourcePath, target, targetPath);
        }
    }

    private static void verifyOracleDateTimeSemantics(DatabaseAdapter source, TablePath sourcePath,
                                                      DatabaseAdapter target, TablePath targetPath) throws Exception {
        String sourceTable = "accuracy_oracle_date_source";
        String targetTable = "ACCURACY_ORACLE_DATE_TARGET";
        execute(source, "DROP TABLE IF EXISTS " + sourceTable);
        execute(source, "CREATE TABLE " + sourceTable + " (id INT PRIMARY KEY, oracle_date_value DATETIME)");
        execute(target, "BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + targetTable
                + " CASCADE CONSTRAINTS'; EXCEPTION WHEN OTHERS THEN NULL; END;");
        // Oracle DATE 在本产品口径下按 DATE（date-only）归一化；要验证时分秒必须用 TIMESTAMP。
        // 这里专门验证“秒级差异能检出、同一秒不误报”，因此 Oracle 侧使用 TIMESTAMP。
        execute(target, "CREATE TABLE " + targetTable + " (id NUMBER(10) PRIMARY KEY, oracle_date_value TIMESTAMP)");
        execute(source, "INSERT INTO " + sourceTable + " VALUES (1, '2026-01-01 01:02:03')");
        execute(source, "INSERT INTO " + sourceTable + " VALUES (2, '2026-01-01 01:02:03')");
        execute(target, "INSERT INTO " + targetTable + " VALUES (1, TIMESTAMP '2026-01-01 01:02:03')");
        execute(target, "INSERT INTO " + targetTable + " VALUES (2, TIMESTAMP '2026-01-01 01:02:04')");

        DiffResult result = compare(source, sourcePath.withTableName(sourceTable), List.of("oracle_date_value"),
                target, targetPath.withTableName(targetTable), List.of("oracle_date_value"), 1, 2);
        assertThat(result.getDifferences()).singleElement().satisfies(row -> {
            assertThat(row.getPrimaryKeyString()).isEqualTo("2");
            assertThat(lowercase(row.getChangedColumns1())).containsExactly("oracle_date_value");
        });
    }

    private static DiffResult compare(DatabaseAdapter source, TablePath sourcePath, List<String> sourceColumns,
                                      DatabaseAdapter target, TablePath targetPath, List<String> targetColumns,
                                      int minKey, int maxKey) throws Exception {
        TableSegment sourceSegment = segment(source, sourcePath, List.of("id"), sourceColumns, minKey, maxKey);
        TableSegment targetSegment = segment(target, targetPath, List.of("id"), targetColumns, minKey, maxKey);
        return CrossDatabaseITestBase.runChecksumDiff(sourceSegment, targetSegment);
    }

    private static TableSegment segment(DatabaseAdapter adapter, TablePath path, List<String> keys,
                                        List<String> columns, int minKey, int maxKey) {
        TableSchema schema = adapter.getTableSchema(path.getPathComponents());
        return TableSegment.builder()
                .database(adapter)
                .tablePath(path)
                .keyColumns(resolveColumnNames(schema, keys))
                .extraColumns(resolveColumnNames(schema, columns))
                .minKey(Optional.of(List.of(minKey)))
                .maxKey(Optional.of(List.of(maxKey)))
                .schema(Optional.of(schema))
                .build();
    }

    private static List<String> resolveColumnNames(TableSchema schema, List<String> requested) {
        return requested.stream()
                .map(name -> schema.getColumnNames().stream()
                        .filter(actual -> actual.equalsIgnoreCase(name))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Column not found in real schema: " + name)))
                .collect(Collectors.toList());
    }

    private static String targetTableName(DatabaseAdapter target, String tableName) {
        String connector = target.getConnectorType();
        return "oracle".equalsIgnoreCase(connector) || "sqlserver".equalsIgnoreCase(connector)
                ? tableName.toUpperCase()
                : tableName;
    }

    private static void assertMissing(Map<DiffOperation, List<DiffRow>> byOperation, DiffOperation operation,
                                      String expectedKey, boolean sourcePresent, boolean targetPresent) {
        assertThat(byOperation.getOrDefault(operation, List.of()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getPrimaryKeyString()).isEqualTo(expectedKey);
                    assertThat(row.getSourceValues().isPresent()).isEqualTo(sourcePresent);
                    assertThat(row.getTargetValues().isPresent()).isEqualTo(targetPresent);
                });
    }

    private static List<String> lowercase(List<String> columns) {
        return columns.stream().map(String::toLowerCase).collect(Collectors.toList());
    }

    private static void createTable(DatabaseAdapter adapter, String tableName) {
        String connector = adapter.getConnectorType().toLowerCase();
        if ("oracle".equals(connector)) {
            execute(adapter, "BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + tableName +
                    " CASCADE CONSTRAINTS'; EXCEPTION WHEN OTHERS THEN NULL; END;");
        } else {
            execute(adapter, "DROP TABLE IF EXISTS " + tableName);
        }
        execute(adapter, createTableSql(connector, tableName));
    }

    private static String createTableSql(String connector, String tableName) {
        switch (connector) {
            case "oracle":
                return "CREATE TABLE " + tableName + " (id NUMBER(10) PRIMARY KEY, name VARCHAR2(100), " +
                        "decimal_value NUMBER(18,4), floating_value BINARY_DOUBLE, created_date VARCHAR2(10), " +
                        "created_at TIMESTAMP, is_active NUMBER(1), nullable_text VARCHAR2(100))";
            case "sqlserver":
                return "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name NVARCHAR(100), " +
                        "decimal_value DECIMAL(18,4), floating_value FLOAT, created_date DATE, " +
                        "created_at DATETIME2, is_active BIT, nullable_text NVARCHAR(100))";
            case "clickhouse":
                return "CREATE TABLE " + tableName + " (id Int32, name String, decimal_value Decimal(18,4), " +
                        "floating_value Float64, created_date Date, created_at DateTime, is_active UInt8, " +
                        "nullable_text Nullable(String)) ENGINE = Memory";
            case "starrocks":
            case "doris":
                return "CREATE TABLE " + tableName + " (id INT, name VARCHAR(100), decimal_value DECIMAL(18,4), " +
                        "floating_value DOUBLE, created_date DATE, created_at DATETIME, is_active BOOLEAN, " +
                        "nullable_text VARCHAR(100)) ENGINE=OLAP UNIQUE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 " +
                        "PROPERTIES (\"replication_num\" = \"1\")";
            case "trino":
            case "presto":
                return "CREATE TABLE " + tableName + " (id INTEGER, name VARCHAR(100), " +
                        "decimal_value DECIMAL(18,4), floating_value DOUBLE, created_date DATE, " +
                        "created_at TIMESTAMP, is_active BOOLEAN, nullable_text VARCHAR(100))";
            case "postgresql":
                return "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name VARCHAR(100), " +
                        "decimal_value DECIMAL(18,4), floating_value DOUBLE PRECISION, created_date DATE, " +
                        "created_at TIMESTAMP, is_active BOOLEAN, nullable_text VARCHAR(100))";
            default:
                return "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name VARCHAR(100), " +
                        "decimal_value DECIMAL(18,4), floating_value DOUBLE, created_date DATE, " +
                        "created_at TIMESTAMP, is_active BOOLEAN, nullable_text VARCHAR(100))";
        }
    }

    private static void insertRows(DatabaseAdapter adapter, String tableName, List<Integer> ids, boolean mutateTwo) {
        for (Integer id : ids) {
            boolean changed = mutateTwo && id == 2;
            execute(adapter, insertSql(adapter.getConnectorType().toLowerCase(), tableName, id, changed));
        }
    }

    private static String insertSql(String connector, String tableName, int id, boolean changed) {
        String name = changed ? "changed" : value(id, "alpha", "beta", "gamma", "unicode_数据");
        String decimal = changed ? "777.7777" : value(id, "10.1250", "-20.5000", "0.0000", "999999.9999");
        String floating = changed ? "88.5" : value(id, "1.5", "-2.25", "0.0", "12345.125");
        String date = changed ? "2027-07-07" : value(id, "2026-01-01", "2026-02-02", "2026-03-03", "2026-04-04");
        String timestamp = changed ? "2027-07-07 07:08:09" : value(id,
                "2026-01-01 01:02:03", "2026-02-02 02:03:04", "2026-03-03 03:04:05", "2026-04-04 04:05:06");
        boolean active = changed || id == 1 || id == 3;
        String nullable = changed ? "NULL" : "'nullable_" + id + "'";
        String stringPrefix = "sqlserver".equals(connector) ? "N" : "";
        if ("sqlserver".equals(connector) && nullable.startsWith("'")) {
            nullable = "N" + nullable;
        }
        String dateLiteral = "trino".equals(connector) || "presto".equals(connector)
                ? "DATE '" + date + "'"
                : "'" + date + "'";
        String timestampLiteral = ("oracle".equals(connector) || "trino".equals(connector) || "presto".equals(connector))
                ? "TIMESTAMP '" + timestamp + "'"
                : "'" + timestamp + "'";
        String booleanLiteral = "postgresql".equals(connector)
                || "trino".equals(connector)
                || "presto".equals(connector)
                ? String.valueOf(active)
                : active ? "1" : "0";
        return "INSERT INTO " + tableName +
                " (id, name, decimal_value, floating_value, created_date, created_at, is_active, nullable_text) VALUES (" +
                id + ", " + stringPrefix + "'" + name + "', " + decimal + ", " + floating + ", " + dateLiteral + ", " +
                timestampLiteral + ", " + booleanLiteral + ", " + nullable + ")";
    }

    private static String value(int id, String one, String two, String three, String four) {
        switch (id) {
            case 1:
                return one;
            case 2:
                return two;
            case 3:
                return three;
            case 4:
                return four;
            default:
                throw new IllegalArgumentException("Unsupported fixture id: " + id);
        }
    }

    private static void execute(DatabaseAdapter adapter, String sql) {
        try {
            CrossDatabaseITestBase.executeSql(adapter, sql);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to prepare accuracy fixture for " + adapter.getConnectorType(), exception);
        }
    }

    private static void configureComparisonSession(DatabaseAdapter adapter) {
        String connector = adapter.getConnectorType().toLowerCase();
        if ("postgresql".equals(connector)) {
            execute(adapter, "SET TIME ZONE 'UTC'");
        } else if ("mysql".equals(connector) || "tidb".equals(connector)) {
            execute(adapter, "SET time_zone = '+00:00'");
        }
    }
}
