package com.consilens.connector.api.enums;

import lombok.Getter;

/**
 * Enum representing supported database types for the integrated system.
 * Combines the best implementations from all modules.
 */
@Getter
public enum DatabaseType {

    // Traditional databases
    MYSQL("MySQL", "com.mysql.cj.jdbc.Driver", 3306),
    POSTGRESQL("PostgreSQL", "org.postgresql.Driver", 5432),
    ORACLE("Oracle", "oracle.jdbc.OracleDriver", 1521),
    SQL_SERVER("SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433),
    SQLITE("SQLite", "org.sqlite.JDBC", 0),
    H2("H2", "org.h2.Driver", 9092),

    // Data warehouses and cloud databases
    SNOWFLAKE("Snowflake", "net.snowflake.client.jdbc.SnowflakeDriver", 443),
    BIGQUERY("BigQuery", "com.google.cloud.bigquery.jdbc.BigQueryDriver", 443),
    REDSHIFT("Redshift", "com.amazon.redshift.jdbc.Driver", 5439),
    DATABRICKS("Databricks", "com.databricks.jdbc.DatabricksDriver", 443),

    // Analytical databases
    CLICKHOUSE("ClickHouse", "com.clickhouse.jdbc.ClickHouseDriver", 8123),
    PRESTO("Presto", "com.facebook.presto.jdbc.PrestoDriver", 8080),
    TRINO("Trino", "io.trino.jdbc.TrinoDriver", 8080),
    VERTICA("Vertica", "com.vertica.jdbc.Driver", 5433),
    DORIS("Apache Doris", "com.mysql.cj.jdbc.Driver", 9030),
    STARROCKS("StarRocks", "com.mysql.cj.jdbc.Driver", 9030),
    HIVE("Apache Hive", "org.apache.hive.jdbc.HiveDriver", 10000),

    // NoSQL databases with SQL interfaces
    DUCKDB("DuckDB", "org.duckdb.jdbc.DuckDBDriver", 0),
    TIDB("TiDB", "com.mysql.cj.jdbc.Driver", 4000),

    UNKNOWN("Unknown", null, -1);

    private final String displayName;
    private final String driverClassName;
    private final int defaultPort;

    DatabaseType(String displayName, String driverClassName, int defaultPort) {
        this.displayName = displayName;
        this.driverClassName = driverClassName;
        this.defaultPort = defaultPort;
    }

    /**
     * Check if this database type supports a specific feature.
     */
    public boolean supportsFeature(DatabaseFeature feature) {
        switch (this) {
            case MYSQL:
                return mysqlSupports(feature);
            case POSTGRESQL:
                return postgresqlSupports(feature);
            case ORACLE:
                return oracleSupports(feature);
            case SQL_SERVER:
                return sqlServerSupports(feature);
            case H2:
                return h2Supports(feature);
            case SNOWFLAKE:
                return snowflakeSupports(feature);
            case BIGQUERY:
                return bigquerySupports(feature);
            case REDSHIFT:
                return redshiftSupports(feature);
            case DATABRICKS:
                return databricksSupports(feature);
            case CLICKHOUSE:
                return clickhouseSupports(feature);
            case PRESTO:
                return prestoSupports(feature);
            case TRINO:
                return trinoSupports(feature);
            case VERTICA:
                return verticaSupports(feature);
            case DUCKDB:
                return duckdbSupports(feature);
            case TIDB:
                return tidbSupports(feature);
            default:
                return false;
        }
    }

    private boolean mysqlSupports(DatabaseFeature feature) {
        switch (feature) {
            case WINDOW_FUNCTIONS:
            case UNIQUE_CONSTRAINTS:
            case JSON_FUNCTIONS:
            case STORED_PROCEDURES:
            case TRANSACTIONS:
            case SAVEPOINTS:
                return true;
            case CTE:
            case FULL_OUTER_JOIN:
            case DISTINCT_ON:
                return false;
            default:
                return false;
        }
    }

    private boolean postgresqlSupports(DatabaseFeature feature) {
        // PostgreSQL supports almost all features
        switch (feature) {
            case DISTINCT_ON:
                return true;
            default:
                return true;
        }
    }

    private boolean oracleSupports(DatabaseFeature feature) {
        switch (feature) {
            case WINDOW_FUNCTIONS:
            case UNIQUE_CONSTRAINTS:
            case JSON_FUNCTIONS:
            case STORED_PROCEDURES:
            case TRANSACTIONS:
            case SAVEPOINTS:
                return true;
            case CTE:
            case FULL_OUTER_JOIN:
            case DISTINCT_ON:
                return false;
            default:
                return false;
        }
    }

    private boolean sqlServerSupports(DatabaseFeature feature) {
        switch (feature) {
            case CTE:
            case WINDOW_FUNCTIONS:
            case UNIQUE_CONSTRAINTS:
            case JSON_FUNCTIONS:
            case STORED_PROCEDURES:
            case TRANSACTIONS:
            case SAVEPOINTS:
                return true;
            case FULL_OUTER_JOIN:
            case DISTINCT_ON:
                return false;
            default:
                return false;
        }
    }

    private boolean h2Supports(DatabaseFeature feature) {
        // H2 supports most features for testing
        switch (feature) {
            case CTE:
            case WINDOW_FUNCTIONS:
            case UNIQUE_CONSTRAINTS:
            case JSON_FUNCTIONS:
            case TRANSACTIONS:
            case SAVEPOINTS:
                return true;
            case FULL_OUTER_JOIN:
            case DISTINCT_ON:
            case STORED_PROCEDURES:
                return false;
            default:
                return false;
        }
    }

    private boolean snowflakeSupports(DatabaseFeature feature) {
        // Snowflake has excellent support for modern SQL features
        switch (feature) {
            case WINDOW_FUNCTIONS:
            case UNIQUE_CONSTRAINTS:
            case JSON_FUNCTIONS:
            case TRANSACTIONS:
            case CTE:
            case FULL_OUTER_JOIN:
            case ARRAY_FUNCTIONS:
            case NATIVE_PARALLELIZATION:
                return true;
            case DISTINCT_ON:
            case SAVEPOINTS:
                return false;
            default:
                return true;
        }
    }

    private boolean bigquerySupports(DatabaseFeature feature) {
        // BigQuery supports most analytical features
        switch (feature) {
            case WINDOW_FUNCTIONS:
            case UNIQUE_CONSTRAINTS:
            case JSON_FUNCTIONS:
            case CTE:
            case ARRAY_FUNCTIONS:
            case NATIVE_PARALLELIZATION:
                return true;
            case FULL_OUTER_JOIN:
            case TRANSACTIONS:
            case SAVEPOINTS:
            case STORED_PROCEDURES:
                return false;
            default:
                return true;
        }
    }

    private boolean redshiftSupports(DatabaseFeature feature) {
        // Redshift is based on PostgreSQL with some limitations
        switch (feature) {
            case WINDOW_FUNCTIONS:
            case UNIQUE_CONSTRAINTS:
            case JSON_FUNCTIONS:
            case TRANSACTIONS:
            case CTE:
                return true;
            case FULL_OUTER_JOIN:
            case DISTINCT_ON:
            case NATIVE_PARALLELIZATION:
                return false;
            default:
                return true;
        }
    }

    private boolean databricksSupports(DatabaseFeature feature) {
        // Databricks SQL is similar to Spark SQL
        switch (feature) {
            case WINDOW_FUNCTIONS:
            case JSON_FUNCTIONS:
            case CTE:
            case ARRAY_FUNCTIONS:
            case NATIVE_PARALLELIZATION:
                return true;
            case UNIQUE_CONSTRAINTS:
            case TRANSACTIONS:
            case SAVEPOINTS:
            case STORED_PROCEDURES:
                return false;
            default:
                return true;
        }
    }

    private boolean clickhouseSupports(DatabaseFeature feature) {
        // ClickHouse is optimized for analytical workloads
        switch (feature) {
            case WINDOW_FUNCTIONS:
            case ARRAY_FUNCTIONS:
            case NATIVE_PARALLELIZATION:
                return true;
            case UNIQUE_CONSTRAINTS:
            case JSON_FUNCTIONS:
            case TRANSACTIONS:
            case CTE:
            case FULL_OUTER_JOIN:
            case SAVEPOINTS:
                return false;
            default:
                return false;
        }
    }

    private boolean prestoSupports(DatabaseFeature feature) {
        // Presto supports analytical SQL features
        switch (feature) {
            case WINDOW_FUNCTIONS:
            case JSON_FUNCTIONS:
            case CTE:
            case ARRAY_FUNCTIONS:
                return true;
            case UNIQUE_CONSTRAINTS:
            case TRANSACTIONS:
            case SAVEPOINTS:
            case STORED_PROCEDURES:
                return false;
            default:
                return true;
        }
    }

    private boolean trinoSupports(DatabaseFeature feature) {
        // Trino (formerly PrestoSQL) has similar capabilities to Presto
        return prestoSupports(feature);
    }

    private boolean verticaSupports(DatabaseFeature feature) {
        // Vertica is an analytical database
        switch (feature) {
            case WINDOW_FUNCTIONS:
            case UNIQUE_CONSTRAINTS:
            case TRANSACTIONS:
            case CTE:
                return true;
            case JSON_FUNCTIONS:
            case ARRAY_FUNCTIONS:
            case FULL_OUTER_JOIN:
                return false;
            default:
                return true;
        }
    }

    private boolean duckdbSupports(DatabaseFeature feature) {
        // DuckDB is an in-process analytical database
        switch (feature) {
            case WINDOW_FUNCTIONS:
            case UNIQUE_CONSTRAINTS:
            case JSON_FUNCTIONS:
            case CTE:
            case ARRAY_FUNCTIONS:
                return true;
            case TRANSACTIONS:
            case SAVEPOINTS:
            case STORED_PROCEDURES:
                return false;
            default:
                return true;
        }
    }

    private boolean tidbSupports(DatabaseFeature feature) {
        // TiDB is highly compatible with MySQL
        switch (feature) {
            case WINDOW_FUNCTIONS:
            case UNIQUE_CONSTRAINTS:
            case JSON_FUNCTIONS:
            case TRANSACTIONS:
            case CTE:
                return true;
            case FULL_OUTER_JOIN:
            case DISTINCT_ON:
            case STORED_PROCEDURES:
            case SAVEPOINTS:
                return false;
            default:
                return true;
        }
    }

    public static DatabaseType fromString(String type) {
        if (type == null) {
            return UNKNOWN;
        }

        String normalized = type.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase();

        switch (normalized) {
            case "SQLSERVER":
            case "SQL_SERVER":
            case "MSSQL":
                return SQL_SERVER;
            case "POSTGRES":
                return POSTGRESQL;
            default:
                break;
        }

        try {
            return DatabaseType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    public static DatabaseType fromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return UNKNOWN;
        }

        String lowerUrl = jdbcUrl.toLowerCase();
        
        if (lowerUrl.contains("tidb") || (lowerUrl.contains("mysql") && lowerUrl.contains(":4000"))) {
            return TIDB;
        } else if (lowerUrl.contains("doris")) {
            return DORIS;
        } else if (lowerUrl.contains("starrocks") || (lowerUrl.contains("mysql") && lowerUrl.contains(":9030"))) {
            return STARROCKS;
        } else if (lowerUrl.contains("mysql")) {
            return MYSQL;
        } else if (lowerUrl.contains("postgresql") || lowerUrl.contains("postgres")) {
            return POSTGRESQL;
        } else if (lowerUrl.contains("oracle")) {
            return ORACLE;
        } else if (lowerUrl.contains("sqlserver")) {
            return SQL_SERVER;
        } else if (lowerUrl.contains("sqlite")) {
            return SQLITE;
        } else if (lowerUrl.contains("h2")) {
            return H2;
        } else if (lowerUrl.contains("snowflake")) {
            return SNOWFLAKE;
        } else if (lowerUrl.contains("bigquery") || lowerUrl.contains("googleapis.com/bigquery")) {
            return BIGQUERY;
        } else if (lowerUrl.contains("redshift") || lowerUrl.contains("redshift.amazonaws.com")) {
            return REDSHIFT;
        } else if (lowerUrl.contains("databricks")) {
            return DATABRICKS;
        } else if (lowerUrl.contains("clickhouse")) {
            return CLICKHOUSE;
        } else if (lowerUrl.contains("presto")) {
            return PRESTO;
        } else if (lowerUrl.contains("trino")) {
            return TRINO;
        } else if (lowerUrl.contains("vertica")) {
            return VERTICA;
        } else if (lowerUrl.contains("duckdb")) {
            return DUCKDB;
        }

        return UNKNOWN;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
