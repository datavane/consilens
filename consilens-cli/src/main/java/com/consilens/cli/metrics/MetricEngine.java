package com.consilens.cli.metrics;

/**
 * Metric aggregation engine.
 */
public enum MetricEngine {
    /** Scan CanonicalRecords through the connector SPI. */
    JAVA,
    /** Push aggregation SQL to a local DuckDB instance (file sources only). */
    DUCKDB;

    public static MetricEngine from(String value) {
        if (value == null || value.isBlank() || "java".equalsIgnoreCase(value)) {
            return JAVA;
        }
        if ("duckdb".equalsIgnoreCase(value)) {
            return DUCKDB;
        }
        throw new IllegalArgumentException("Unsupported metric engine: " + value
                + " (supported: java, duckdb)");
    }
}
