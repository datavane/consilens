package com.consilens.connector.tidb;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.conncetor.base.BaseMetadataQueryGenerator;

/**
 * TiDB metadata query generator.
 * 
 * <p>
 * Generates SQL queries for retrieving TiDB metadata from information_schema.
 * TiDB is highly compatible with MySQL, so this generator shares much logic
 * with MySQLMetadataQueryGenerator.
 * </p>
 * 
 * @since 1.0.0
 */
public class TiDBMetadataQueryGenerator extends BaseMetadataQueryGenerator {

    private final CapabilityProvider capabilityProvider;

    public TiDBMetadataQueryGenerator(CapabilityProvider capabilityProvider) {
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
        return "SELECT column_name, data_type, is_nullable, " +
                "character_maximum_length, numeric_precision, numeric_scale, " +
                "datetime_precision, column_default, extra " +
                "FROM information_schema.columns " +
                "WHERE table_schema = '" + escapeString(schemaName) + "' " +
                "AND table_name = '" + escapeString(tableName) + "' " +
                "ORDER BY ordinal_position";
    }

    @Override
    public String getPrimaryKeysSQL(String schemaName, String tableName) {
        return "SELECT column_name, ordinal_position " +
                "FROM information_schema.key_column_usage " +
                "WHERE table_schema = '" + escapeString(schemaName) + "' " +
                "AND table_name = '" + escapeString(tableName) + "' " +
                "AND constraint_name = 'PRIMARY' " +
                "ORDER BY ordinal_position";
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
        // TiDB has additional system schemas
        return "SELECT schema_name FROM information_schema.schemata " +
                "WHERE schema_name NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys', 'METRICS_SCHEMA', 'INSPECTION_SCHEMA') "
                +
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
        return "SELECT index_name, column_name, non_unique, seq_in_index " +
                "FROM information_schema.statistics " +
                "WHERE table_schema = '" + escapeString(schemaName) + "' " +
                "AND table_name = '" + escapeString(tableName) + "' " +
                "ORDER BY index_name, seq_in_index";
    }

    @Override
    public String getForeignKeysSQL(String schemaName, String tableName) {
        return "SELECT constraint_name, column_name, referenced_table_name, referenced_column_name " +
                "FROM information_schema.key_column_usage " +
                "WHERE table_schema = '" + escapeString(schemaName) + "' " +
                "AND table_name = '" + escapeString(tableName) + "' " +
                "AND referenced_table_name IS NOT NULL " +
                "ORDER BY constraint_name, ordinal_position";
    }

    @Override
    public String getAnalyzeTableSQL(String schemaName, String tableName) {
        return "ANALYZE TABLE " + capabilityProvider.quote(schemaName) + "." +
                capabilityProvider.quote(tableName);
    }

    @Override
    public String getOptimizeTableSQL(String schemaName, String tableName) {
        // TiDB does not support OPTIMIZE TABLE in the same way as MySQL.
        // We return ANALYZE TABLE as a best-effort alternative for statistics update.
        return "ANALYZE TABLE " + capabilityProvider.quote(schemaName) + "." +
                capabilityProvider.quote(tableName);
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
