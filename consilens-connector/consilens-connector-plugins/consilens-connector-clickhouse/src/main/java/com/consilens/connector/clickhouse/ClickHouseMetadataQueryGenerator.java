package com.consilens.connector.clickhouse;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.conncetor.base.BaseMetadataQueryGenerator;

/**
 * ClickHouse metadata query generator.
 * 
 * <p>
 * Generates SQL queries for retrieving ClickHouse metadata from
 * information_schema.
 * 
 * @since 1.0.0
 */
public class ClickHouseMetadataQueryGenerator extends BaseMetadataQueryGenerator {

    private final CapabilityProvider capabilityProvider;

    public ClickHouseMetadataQueryGenerator(CapabilityProvider capabilityProvider) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
    }

    @Override
    public String getTableExistsSQL(String schemaName, String tableName) {
        return "SELECT COUNT(*) FROM system.tables " +
                "WHERE database = '" + escapeString(schemaName) + "' " +
                "AND name = '" + escapeString(tableName) + "'";
    }

    @Override
    public String getTableColumnsSQL(String schemaName, String tableName) {
        return "SELECT name, type, default_kind, default_expression " +
                "FROM system.columns " +
                "WHERE database = '" + escapeString(schemaName) + "' " +
                "AND table = '" + escapeString(tableName) + "' " +
                "ORDER BY position";
    }

    @Override
    public String getPrimaryKeysSQL(String schemaName, String tableName) {
        return "SELECT name as column_name, position as ordinal_position " +
                "FROM system.columns " +
                "WHERE database = '" + escapeString(schemaName) + "' " +
                "AND table = '" + escapeString(tableName) + "' " +
                "AND is_in_primary_key = 1 " +
                "ORDER BY position";
    }

    @Override
    public String getHealthCheckSQL() {
        return "SELECT 1 as status, VERSION() as version";
    }

    @Override
    public String getDatabaseMetadataSQL() {
        return "SELECT VERSION() as version, DATABASE() as database, USER() as user";
    }

    @Override
    public String getSchemasSQL() {
        return "SELECT name FROM system.databases " +
                "WHERE name NOT IN ('system', 'information_schema', 'INFORMATION_SCHEMA') " +
                "ORDER BY name";
    }

    @Override
    public String getTablesSQL(String schemaName) {
        return "SELECT name FROM system.tables " +
                "WHERE database = '" + escapeString(schemaName) + "' " +
                "ORDER BY name";
    }

    @Override
    public String getViewsSQL(String schemaName) {
        // ClickHouse views are stored in system.tables with engine = 'View'
        return "SELECT name FROM system.tables " +
                "WHERE database = '" + escapeString(schemaName) + "' " +
                "AND engine = 'View' " +
                "ORDER BY name";
    }

    @Override
    public String getIndexesSQL(String schemaName, String tableName) {
        // Return data skipping indices
        return "SELECT name as index_name, expr as column_name, 1 as non_unique, 1 as seq_in_index " +
                "FROM system.data_skipping_indices " +
                "WHERE database = '" + escapeString(schemaName) + "' " +
                "AND table = '" + escapeString(tableName) + "' " +
                "ORDER BY name";
    }

    @Override
    public String getForeignKeysSQL(String schemaName, String tableName) {
        // ClickHouse does not support foreign keys in the traditional sense
        return "SELECT '' as constraint_name, '' as column_name, '' as referenced_table_name, '' as referenced_column_name "
                +
                "FROM system.tables " +
                "WHERE 1=0";
    }

    @Override
    public String getAnalyzeTableSQL(String schemaName, String tableName) {
        // ClickHouse doesn't have explicit ANALYZE
        return "SELECT 'ClickHouse automatically manages table statistics' as status";
    }

    @Override
    public String getOptimizeTableSQL(String schemaName, String tableName) {
        // ClickHouse OPTIMIZE TABLE for MergeTree engines
        return "OPTIMIZE TABLE " + capabilityProvider.quote(schemaName) + "." +
                capabilityProvider.quote(tableName) + " FINAL";
    }

    /**
     * Escape string values to prevent SQL injection.
     */
    private String escapeString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("'", "''");
    }
}
