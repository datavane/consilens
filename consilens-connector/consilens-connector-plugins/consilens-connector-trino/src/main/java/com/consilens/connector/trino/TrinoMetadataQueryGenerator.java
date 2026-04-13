package com.consilens.connector.trino;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.conncetor.base.BaseMetadataQueryGenerator;

/**
 * Trino metadata query generator.
 * Trino uses information_schema for metadata.
 */
public class TrinoMetadataQueryGenerator extends BaseMetadataQueryGenerator {

    private final CapabilityProvider capabilityProvider;

    public TrinoMetadataQueryGenerator(CapabilityProvider capabilityProvider) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
    }

    @Override
    public String getTableExistsSQL(String schemaName, String tableName) {
        return "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = '" + escapeString(schemaName) + "' " +
                "AND table_name = '" + escapeString(tableName) + "'";
    }

    @Override
    public String getTableColumnsSQL(String schemaName, String tableName) {
        return "SELECT column_name, data_type, is_nullable " +
                "FROM information_schema.columns " +
                "WHERE table_schema = '" + escapeString(schemaName) + "' " +
                "AND table_name = '" + escapeString(tableName) + "' " +
                "ORDER BY ordinal_position";
    }

    @Override
    public String getPrimaryKeysSQL(String schemaName, String tableName) {
        // Trino doesn't have primary key constraints in information_schema
        return "SELECT '' as column_name, 0 as ordinal_position WHERE 1=0";
    }

    @Override
    public String getHealthCheckSQL() {
        return "SELECT 1 as status";
    }

    @Override
    public String getDatabaseMetadataSQL() {
        return "SELECT current_catalog as catalog, current_schema as schema, current_user as user";
    }

    @Override
    public String getSchemasSQL() {
        return "SELECT schema_name FROM information_schema.schemata " +
                "WHERE schema_name NOT IN ('information_schema') " +
                "ORDER BY schema_name";
    }

    @Override
    public String getTablesSQL(String schemaName) {
        return "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = '" + escapeString(schemaName) + "' " +
                "AND table_type = 'BASE TABLE' " +
                "ORDER BY table_name";
    }

    @Override
    public String getViewsSQL(String schemaName) {
        return "SELECT table_name FROM information_schema.views " +
                "WHERE table_schema = '" + escapeString(schemaName) + "' " +
                "ORDER BY table_name";
    }

    @Override
    public String getIndexesSQL(String schemaName, String tableName) {
        // Trino doesn't have traditional indexes
        return "SELECT '' as index_name, '' as column_name WHERE 1=0";
    }

    @Override
    public String getForeignKeysSQL(String schemaName, String tableName) {
        // Trino doesn't enforce foreign keys
        return "SELECT '' as constraint_name, '' as column_name, '' as referenced_table_name, '' as referenced_column_name WHERE 1=0";
    }

    @Override
    public String getAnalyzeTableSQL(String schemaName, String tableName) {
        return "ANALYZE " + capabilityProvider.quote(schemaName) + "." +
                capabilityProvider.quote(tableName);
    }

    @Override
    public String getOptimizeTableSQL(String schemaName, String tableName) {
        // Trino doesn't have OPTIMIZE TABLE
        return "-- No optimize for Trino";
    }

    private String escapeString(String value) {
        if (value == null)
            return "";
        return value.replace("'", "''");
    }
}
