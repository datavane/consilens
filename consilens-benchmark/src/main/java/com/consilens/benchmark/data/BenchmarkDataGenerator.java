package com.consilens.benchmark.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 以流式批量方式生成数据库基准数据。单次只保留一个 batch，适用于千万和亿级数据量。
 */
public final class BenchmarkDataGenerator {

    private static final String CREATE_TABLE_TEMPLATE =
            "CREATE TABLE IF NOT EXISTS %s (id BIGINT NOT NULL PRIMARY KEY, key_group BIGINT NOT NULL, "
                    + "key_part BIGINT NOT NULL, benchmark_seq BIGINT NOT NULL UNIQUE, "
                    + "payload VARCHAR(128) NOT NULL, amount BIGINT NOT NULL)";
    private static final String INSERT_SQL_TEMPLATE =
            "INSERT INTO %s (id, key_group, key_part, benchmark_seq, payload, amount) VALUES (?, ?, ?, ?, ?, ?)";

    public BenchmarkDataReport generate(BenchmarkDataOptions options) throws SQLException {
        try (Connection connection = DriverManager.getConnection(options.getJdbcUrl(),
                options.getUsername(), options.getPassword())) {
            connection.setAutoCommit(false);
            if (!options.isResume()) {
                recreateTable(connection, options.getSourceTable());
                recreateTable(connection, options.getTargetTable());
            } else {
                ensureTable(connection, options.getSourceTable());
                ensureTable(connection, options.getTargetTable());
            }

            long sourceRows = count(connection, options.getSourceTable());
            long targetRows = count(connection, options.getTargetTable());
            long start = options.isResume() ? Math.max(maxSequence(connection, options.getSourceTable()),
                    maxSequence(connection, options.getTargetTable())) + 1 : 1;
            if (start <= options.getRows()) {
                insertRange(connection, options, start, options.getRows());
            }
            connection.commit();

            sourceRows = count(connection, options.getSourceTable());
            targetRows = count(connection, options.getTargetTable());
            long mismatchRows = countMismatches(connection, options);
            long sourceMissingRows = countMissing(connection, options.getTargetTable(), options.getSourceTable());
            long targetMissingRows = countMissing(connection, options.getSourceTable(), options.getTargetTable());
            BenchmarkDataReport report = new BenchmarkDataReport(options.getRows(), sourceRows, targetRows,
                    mismatchRows, sourceMissingRows, targetMissingRows, options.getDiffRatio(),
                    options.getSourceMissingRatio(), options.getTargetMissingRatio(), options.getSeed());
            report.setKeyDistribution(options.getKeyDistribution());
            report.write(options.getReportPath());
            return report;
        }
    }

    private void recreateTable(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + safeIdentifier(table));
            statement.executeUpdate(String.format(CREATE_TABLE_TEMPLATE, safeIdentifier(table)));
        }
    }

    private void ensureTable(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(String.format(CREATE_TABLE_TEMPLATE, safeIdentifier(table)));
        }
    }

    private void insertRange(Connection connection, BenchmarkDataOptions options, long start, long end)
            throws SQLException {
        String sourceSql = String.format(INSERT_SQL_TEMPLATE, safeIdentifier(options.getSourceTable()));
        String targetSql = String.format(INSERT_SQL_TEMPLATE, safeIdentifier(options.getTargetTable()));
        try (PreparedStatement source = connection.prepareStatement(sourceSql);
             PreparedStatement target = connection.prepareStatement(targetSql)) {
            int pending = 0;
            for (long id = start; id <= end; id++) {
                if (!isTargetMissing(id, options)) {
                    addBatch(target, id, options, false);
                }
                if (!isSourceMissing(id, options)) {
                    addBatch(source, id, options, true);
                }
                pending++;
                if (pending == options.getBatchSize()) {
                    source.executeBatch();
                    target.executeBatch();
                    connection.commit();
                    pending = 0;
                }
            }
            if (pending > 0) {
                source.executeBatch();
                target.executeBatch();
            }
        }
    }

    private void addBatch(PreparedStatement statement, long sequence, BenchmarkDataOptions options,
                          boolean source) throws SQLException {
        long id = businessKey(sequence, options.getKeyDistribution());
        statement.setLong(1, id);
        statement.setLong(2, Math.floorMod(sequence, 1_000L));
        statement.setLong(3, (sequence - 1) / 1_000L);
        statement.setLong(4, sequence);
        boolean mismatch = isMismatch(sequence, options);
        String payload = "row-" + sequence + (mismatch && !source ? "-changed" : "");
        long amount = sequence * 31L + (mismatch && !source ? 1L : 0L);
        statement.setString(5, payload);
        statement.setLong(6, amount);
        statement.addBatch();
    }

    private long businessKey(long sequence, String distribution) {
        if ("sparse".equals(distribution)) {
            return sequence * 10L;
        }
        if ("skewed".equals(distribution)) {
            // sequence <= BenchmarkDataOptions.MAX_SKEWED_ROWS is validated at parse time;
            // multiplyExact keeps the invariant explicit if this is ever called directly.
            return Math.multiplyExact(sequence, sequence);
        }
        return sequence;
    }

    private boolean isMismatch(long id, BenchmarkDataOptions options) {
        return bucket(id, options.getSeed(), 10_000) < Math.round(options.getDiffRatio() * 10_000);
    }

    private boolean isSourceMissing(long id, BenchmarkDataOptions options) {
        return bucket(id, options.getSeed() + 1, 10_000)
                < Math.round(options.getSourceMissingRatio() * 10_000);
    }

    private boolean isTargetMissing(long id, BenchmarkDataOptions options) {
        return bucket(id, options.getSeed() + 2, 10_000)
                < Math.round(options.getTargetMissingRatio() * 10_000);
    }

    private long bucket(long id, long seed, long bound) {
        long value = id * 1_103_515_245L + seed * 12_345L + 1_011L;
        return Math.floorMod(value, bound);
    }

    private long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + safeIdentifier(table))) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private long maxSequence(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COALESCE(MAX(benchmark_seq), 0) FROM "
                     + safeIdentifier(table))) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private long countMismatches(Connection connection, BenchmarkDataOptions options) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + safeIdentifier(options.getSourceTable()) + " s JOIN "
                + safeIdentifier(options.getTargetTable()) + " t ON s.id=t.id "
                + "WHERE s.payload<>t.payload OR s.amount<>t.amount";
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private long countMissing(Connection connection, String left, String right) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + safeIdentifier(left) + " l LEFT JOIN "
                + safeIdentifier(right) + " r ON l.id=r.id WHERE r.id IS NULL";
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private String safeIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("unsafe SQL identifier: " + identifier);
        }
        return identifier;
    }
}
