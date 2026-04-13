package com.consilens.connector.api.enums;

import lombok.Getter;

/**
 * Enum representing database features that may be supported.
 * Unified from infrastructure and domain modules.
 */
@Getter
public enum DatabaseFeature {
    /**
     * Common Table Expressions (WITH clauses)
     */
    CTE("Common Table Expressions"),

    /**
     * Window functions (ROW_NUMBER, RANK, etc.)
     */
    WINDOW_FUNCTIONS("Window Functions"),

    /**
     * Unique constraint support
     */
    UNIQUE_CONSTRAINTS("Unique Constraints"),

    /**
     * JSON functions and operators
     */
    JSON_FUNCTIONS("JSON Functions"),

    /**
     * FULL OUTER JOIN support
     */
    FULL_OUTER_JOIN("Full Outer Join"),

    /**
     * DISTINCT ON clause (PostgreSQL specific)
     */
    DISTINCT_ON("Distinct On"),

    /**
     * Stored procedures support
     */
    STORED_PROCEDURES("Stored Procedures"),

    /**
     * Transaction isolation levels
     */
    TRANSACTIONS("Transactions"),

    /**
     * Checkpoint and savepoint support
     */
    SAVEPOINTS("Savepoints"),

    /**
     * CHECK constraints support
     */
    CHECK_CONSTRAINTS("Check Constraints"),

    /**
     * Array functions support
     */
    ARRAY_FUNCTIONS("Array Functions"),

    /**
     * Native parallelization capabilities
     */
    NATIVE_PARALLELIZATION("Native Parallelization"),

    /**
     * Partitioning support
     */
    PARTITIONING("Table Partitioning"),

    /**
     * Index types (B-tree, Hash, GIN, etc.)
     */
    ADVANCED_INDEXING("Advanced Indexing"),

    /**
     * Materialized views
     */
    MATERIALIZED_VIEWS("Materialized Views"),

    /**
     * Recursive queries
     */
    RECURSIVE_QUERIES("Recursive Queries");

    private final String displayName;

    DatabaseFeature(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}