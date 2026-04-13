package com.consilens.connector.api;

/**
 * Interface for database metadata query generation.
 * 
 * <p>
 * This interface provides methods for generating SQL queries to retrieve
 * database metadata information such as tables, columns, indexes, and
 * constraints.
 * It is a standalone component obtained from
 * {@link DatabaseDialect#getMetadataQueryGenerator()}.
 * 
 * @since 1.1.0
 * @see DatabaseDialect
 */
public interface MetadataQueryGenerator {

    /**
     * Generate SQL to check if a table exists.
     * 
     * @param schemaName the schema name
     * @param tableName  the table name
     * @return SQL query to check table existence
     */
    String getTableExistsSQL(String schemaName, String tableName);

    /**
     * Generate SQL to check if a column exists.
     * 
     * @param schemaName the schema name
     * @param tableName  the table name
     * @param columnName the column name
     * @return SQL query to check column existence
     */
    String getColumnExistsSQL(String schemaName, String tableName, String columnName);

    /**
     * Generate SQL to get primary key information.
     * 
     * @param schemaName the schema name
     * @param tableName  the table name
     * @return SQL query to retrieve primary key
     */
    String getPrimaryKeySQL(String schemaName, String tableName);

    /**
     * Generate SQL to fetch table column information.
     * 
     * @param schemaName the schema name
     * @param tableName  the table name
     * @return SQL query to retrieve column metadata
     */
    String getTableColumnsSQL(String schemaName, String tableName);

    /**
     * Generate SQL to fetch table primary keys with ordinal positions.
     * 
     * @param schemaName the schema name
     * @param tableName  the table name
     * @return SQL query to retrieve primary keys
     */
    String getPrimaryKeysSQL(String schemaName, String tableName);

    /**
     * Generate SQL to fetch foreign key information.
     * 
     * @param schemaName the schema name
     * @param tableName  the table name
     * @return SQL query to retrieve foreign keys
     */
    String getForeignKeysSQL(String schemaName, String tableName);

    /**
     * Generate SQL to fetch index information.
     * 
     * @param schemaName the schema name
     * @param tableName  the table name
     * @return SQL query to retrieve indexes
     */
    String getIndexesSQL(String schemaName, String tableName);

    /**
     * Generate SQL for fetching database metadata.
     * 
     * @return SQL query to retrieve database metadata
     */
    String getDatabaseMetadataSQL();

    /**
     * Generate SQL for fetching schema names.
     * 
     * @return SQL query to retrieve schema list
     */
    String getSchemasSQL();

    /**
     * Generate SQL for fetching table names in a schema.
     * 
     * @param schemaName the schema name
     * @return SQL query to retrieve table list
     */
    String getTablesSQL(String schemaName);

    /**
     * Generate SQL for fetching view names in a schema.
     * 
     * @param schemaName the schema name
     * @return SQL query to retrieve view list
     */
    String getViewsSQL(String schemaName);

    /**
     * Generate SQL for checking database connection health.
     * 
     * @return SQL query for health check
     */
    String getHealthCheckSQL();

    /**
     * Generate SQL for analyzing table statistics.
     * 
     * @param schemaName the schema name
     * @param tableName  the table name
     * @return SQL statement to analyze table
     */
    String getAnalyzeTableSQL(String schemaName, String tableName);

    /**
     * Generate SQL for optimizing/vacuuming a table.
     * 
     * @param schemaName the schema name
     * @param tableName  the table name
     * @return SQL statement to optimize table
     */
    String getOptimizeTableSQL(String schemaName, String tableName);
}
