package com.consilens.cli.service;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

@Slf4j
public class JdbcCheckpointStore implements CheckpointStore {

    private final String jdbcUrl;
    private final Properties properties;
    private final String tableName;

    public JdbcCheckpointStore(String jdbcUrl, Properties properties, String tableName, String driverClassName) {
        this.jdbcUrl = jdbcUrl;
        this.properties = properties != null ? properties : new Properties();
        this.tableName = tableName;
        if (driverClassName != null && !driverClassName.isBlank()) {
            try {
                Class.forName(driverClassName);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Checkpoint store driver not found: " + driverClassName, e);
            }
        }
        ensureTable();
    }

    @Override
    public Optional<CompareCheckpoint> load(String taskId) throws Exception {
        String sql = "SELECT task_id, watermark, last_start, last_end, status, owner, lease_until FROM " + tableName + " WHERE task_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(CompareCheckpoint.builder()
                        .taskId(resultSet.getString(1))
                        .watermark(toInstant(resultSet.getTimestamp(2)))
                        .lastStart(toInstant(resultSet.getTimestamp(3)))
                        .lastEnd(toInstant(resultSet.getTimestamp(4)))
                        .status(resultSet.getString(5))
                        .owner(resultSet.getString(6))
                        .leaseUntil(toInstant(resultSet.getTimestamp(7)))
                        .build());
            }
        }
    }

    @Override
    public boolean tryMarkRunning(String taskId, Instant start, Instant end, String owner, Instant leaseUntil) throws Exception {
        Instant now = Instant.now();
        if (tryUpdateRunning(taskId, start, end, owner, leaseUntil, now)) {
            return true;
        }
        try {
            insert(taskId, null, start, end, "running", owner, leaseUntil, null);
            return true;
        } catch (Exception e) {
            return tryUpdateRunning(taskId, start, end, owner, leaseUntil, now);
        }
    }

    @Override
    public void markRunning(String taskId, Instant start, Instant end) throws Exception {
        upsert(taskId, null, start, end, "running", null, null, null);
    }

    @Override
    public void markSucceeded(String taskId, Instant watermark, Instant start, Instant end) throws Exception {
        upsert(taskId, watermark, start, end, "succeeded", null, null, null);
    }

    @Override
    public void markFailed(String taskId, Instant start, Instant end, Throwable error) throws Exception {
        upsert(taskId, null, start, end, "failed", null, null, error != null ? error.getMessage() : null);
    }

    private void ensureTable() {
        String ddl = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "task_id VARCHAR(128) PRIMARY KEY, "
                + "watermark TIMESTAMP NULL, "
                + "last_start TIMESTAMP NULL, "
                + "last_end TIMESTAMP NULL, "
                + "status VARCHAR(32) NOT NULL, "
                + "owner VARCHAR(128) NULL, "
                + "lease_until TIMESTAMP NULL, "
                + "updated_at TIMESTAMP NOT NULL, "
                + "attributes TEXT NULL)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(ddl)) {
            statement.executeUpdate();
            addColumnIfMissing(connection, "owner VARCHAR(128) NULL");
            addColumnIfMissing(connection, "lease_until TIMESTAMP NULL");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize checkpoint table " + tableName, e);
        }
    }

    private void addColumnIfMissing(Connection connection, String columnDefinition) {
        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE " + tableName + " ADD COLUMN " + columnDefinition)) {
            statement.executeUpdate();
        } catch (Exception ignored) {
            // Column already exists or the database does not support this ALTER form.
        }
    }

    private void upsert(String taskId,
                        Instant watermark,
                        Instant start,
                        Instant end,
                        String status,
                        String owner,
                        Instant leaseUntil,
                        String attributes) throws Exception {
        Optional<CompareCheckpoint> existing = load(taskId);
        if (existing.isPresent()) {
            String sql = "UPDATE " + tableName
                    + " SET watermark = ?, last_start = ?, last_end = ?, status = ?, owner = ?, lease_until = ?, updated_at = ?, attributes = ? WHERE task_id = ?";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, watermark != null ? Timestamp.from(watermark)
                        : existing.get().getWatermark() != null ? Timestamp.from(existing.get().getWatermark()) : null);
                statement.setTimestamp(2, start != null ? Timestamp.from(start) : null);
                statement.setTimestamp(3, end != null ? Timestamp.from(end) : null);
                statement.setString(4, status);
                statement.setString(5, owner);
                statement.setTimestamp(6, leaseUntil != null ? Timestamp.from(leaseUntil) : null);
                statement.setTimestamp(7, Timestamp.from(Instant.now()));
                statement.setString(8, attributes);
                statement.setString(9, taskId);
                statement.executeUpdate();
            }
            return;
        }

        insert(taskId, watermark, start, end, status, owner, leaseUntil, attributes);
    }

    private boolean tryUpdateRunning(String taskId,
                                     Instant start,
                                     Instant end,
                                     String owner,
                                     Instant leaseUntil,
                                     Instant now) throws Exception {
        String sql = "UPDATE " + tableName
                + " SET last_start = ?, last_end = ?, status = ?, owner = ?, lease_until = ?, updated_at = ?, attributes = ? "
                + "WHERE task_id = ? AND (status <> ? OR lease_until IS NULL OR lease_until <= ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, start != null ? Timestamp.from(start) : null);
            statement.setTimestamp(2, end != null ? Timestamp.from(end) : null);
            statement.setString(3, "running");
            statement.setString(4, owner);
            statement.setTimestamp(5, leaseUntil != null ? Timestamp.from(leaseUntil) : null);
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setString(7, null);
            statement.setString(8, taskId);
            statement.setString(9, "running");
            statement.setTimestamp(10, Timestamp.from(now));
            return statement.executeUpdate() > 0;
        }
    }

    private void insert(String taskId,
                        Instant watermark,
                        Instant start,
                        Instant end,
                        String status,
                        String owner,
                        Instant leaseUntil,
                        String attributes) throws Exception {
        String sql = "INSERT INTO " + tableName
                + " (task_id, watermark, last_start, last_end, status, owner, lease_until, updated_at, attributes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            statement.setTimestamp(2, watermark != null ? Timestamp.from(watermark) : null);
            statement.setTimestamp(3, start != null ? Timestamp.from(start) : null);
            statement.setTimestamp(4, end != null ? Timestamp.from(end) : null);
            statement.setString(5, status);
            statement.setString(6, owner);
            statement.setTimestamp(7, leaseUntil != null ? Timestamp.from(leaseUntil) : null);
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.setString(9, attributes);
            statement.executeUpdate();
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }
}
