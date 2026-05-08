package com.consilens.performance;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for performance tests.
 */
@Data
@Builder
public class PerformanceTestConfig {

    /** Test name */
    private String testName;

    /** Number of warmup iterations */
    @Builder.Default
    private int warmupIterations = 5;

    /** Number of test iterations */
    @Builder.Default
    private int testIterations = 10;

    /** Concurrency level (number of threads) */
    @Builder.Default
    private int concurrencyLevel = 1;

    /** Load pattern */
    @Builder.Default
    private LoadPattern loadPattern = LoadPattern.CONSTANT;

    /** Test duration (null for iteration-based tests) */
    private Duration testDuration;

    /** Custom test parameters */
    @Builder.Default
    private Map<String, Object> testParameters = new HashMap<>();

    /** Database configuration */
    private DatabaseConfig databaseConfig;

    /** Enable detailed logging */
    @Builder.Default
    private boolean detailedLogging = false;

    /** Resource monitoring interval in milliseconds */
    @Builder.Default
    private long monitoringIntervalMs = 100;

    /**
     * Load pattern for performance tests.
     */
    public enum LoadPattern {
        /** Constant load throughout the test */
        CONSTANT,

        /** Gradually increasing load */
        RAMP_UP,

        /** Gradually decreasing load */
        RAMP_DOWN,

        /** Spike pattern with sudden load increases */
        SPIKE,

        /** Step pattern with discrete load levels */
        STEP
    }

    /**
     * Database configuration for performance tests.
     */
    @Data
    @Builder
    public static class DatabaseConfig {
        private String jdbcUrl;
        private String username;
        private String password;
        private String databaseType;

        /** Source table configuration */
        private TableConfig sourceTable;

        /** Target table configuration */
        private TableConfig targetTable;

        /** Connection pool size */
        @Builder.Default
        private int poolSize = 10;

        /** Query timeout in seconds */
        @Builder.Default
        private int queryTimeoutSeconds = 30;
    }

    /**
     * Table configuration.
     */
    @Data
    @Builder
    public static class TableConfig {
        private String schema;
        private String tableName;
        private String[] keyColumns;
        private String updateColumn;

        /** Number of rows to process (0 = all rows) */
        @Builder.Default
        private long rowLimit = 0;

        /** WHERE clause filter */
        private String whereClause;
    }

    /**
     * Validate the configuration.
     */
    public void validate() {
        if (testName == null || testName.trim().isEmpty()) {
            throw new IllegalArgumentException("Test name cannot be null or empty");
        }

        if (loadPattern == null) {
            throw new IllegalArgumentException("Load pattern cannot be null");
        }

        if (warmupIterations < 0) {
            throw new IllegalArgumentException("Warmup iterations cannot be negative");
        }

        if (testIterations <= 0) {
            throw new IllegalArgumentException("Test iterations must be positive");
        }

        if (concurrencyLevel <= 0) {
            throw new IllegalArgumentException("Concurrency level must be positive");
        }

        if (monitoringIntervalMs <= 0) {
            throw new IllegalArgumentException("Monitoring interval must be positive");
        }

        if (testDuration != null && (testDuration.isZero() || testDuration.isNegative())) {
            throw new IllegalArgumentException("Test duration must be positive");
        }

        if (testParameters == null) {
            throw new IllegalArgumentException("Test parameters cannot be null");
        }

        if (databaseConfig != null) {
            validateDatabaseConfig(databaseConfig);
        }
    }

    /**
     * Validate database configuration.
     */
    private void validateDatabaseConfig(DatabaseConfig config) {
        if (config.getJdbcUrl() == null || config.getJdbcUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("JDBC URL cannot be null or empty");
        }

        if (config.getUsername() == null || config.getUsername().trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }

        if (config.getPoolSize() <= 0) {
            throw new IllegalArgumentException("Pool size must be positive");
        }

        if (config.getQueryTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("Query timeout must be positive");
        }

        validateTableConfig("sourceTable", config.getSourceTable());
        validateTableConfig("targetTable", config.getTargetTable());
    }

    /**
     * Validate optional table configuration.
     */
    private void validateTableConfig(String name, TableConfig config) {
        if (config == null) {
            return;
        }
        if (config.getTableName() == null || config.getTableName().trim().isEmpty()) {
            throw new IllegalArgumentException(name + ".tableName cannot be null or empty");
        }
        if (config.getKeyColumns() == null || config.getKeyColumns().length == 0) {
            throw new IllegalArgumentException(name + ".keyColumns cannot be empty");
        }
        for (String keyColumn : config.getKeyColumns()) {
            if (keyColumn == null || keyColumn.trim().isEmpty()) {
                throw new IllegalArgumentException(name + ".keyColumns cannot contain blank values");
            }
        }
        if (config.getRowLimit() < 0) {
            throw new IllegalArgumentException(name + ".rowLimit cannot be negative");
        }
    }
}
