package com.consilens.conncetor.base;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.MetadataQueryGenerator;

public class BaseMetadataQueryGenerator implements MetadataQueryGenerator {

    private final CapabilityProvider capabilityProvider;

    public BaseMetadataQueryGenerator(CapabilityProvider capabilityProvider) {
        this.capabilityProvider = capabilityProvider;
    }

    @Override
    public String getTableExistsSQL(String schemaName, String tableName) {
        return "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = ? AND table_name = ?";
    }

    @Override
    public String getColumnExistsSQL(String schemaName, String tableName, String columnName) {
        return "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? AND column_name = ?";
    }

    @Override
    public String getPrimaryKeySQL(String schemaName, String tableName) {
        return "SELECT column_name FROM information_schema.key_column_usage " +
                "WHERE table_schema = ? AND table_name = ? AND constraint_name = 'PRIMARY'";
    }

    @Override
    public String getTableColumnsSQL(String schemaName, String tableName) {
        return "SELECT column_name, data_type, is_nullable, " +
                "character_maximum_length, numeric_precision, numeric_scale, " +
                "datetime_precision, column_default " +
                "FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
    }

    @Override
    public String getPrimaryKeysSQL(String schemaName, String tableName) {
        return "SELECT kcu.column_name, kcu.ordinal_position " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu " +
                "ON tc.constraint_name = kcu.constraint_name " +
                "WHERE tc.constraint_type = 'PRIMARY KEY' " +
                "AND tc.table_schema = ? AND tc.table_name = ? " +
                "ORDER BY kcu.ordinal_position";
    }

    @Override
    public String getForeignKeysSQL(String schemaName, String tableName) {
        return "SELECT kcu.column_name, ccu.table_schema AS foreign_table_schema, " +
                "ccu.table_name AS foreign_table_name, ccu.column_name AS foreign_column_name " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu " +
                "ON tc.constraint_name = kcu.constraint_name " +
                "JOIN information_schema.constraint_column_usage ccu " +
                "ON tc.constraint_name = ccu.constraint_name " +
                "WHERE tc.constraint_type = 'FOREIGN KEY' " +
                "AND tc.table_schema = ? AND tc.table_name = ?";
    }

    @Override
    public String getIndexesSQL(String schemaName, String tableName) {
        return "SELECT index_name, column_name, non_unique " +
                "FROM information_schema.statistics " +
                "WHERE table_schema = ? AND table_name = ? ORDER BY index_name, seq_in_index";
    }

    @Override
    public String getDatabaseMetadataSQL() {
        return "SELECT CURRENT_DATABASE(), CURRENT_USER(), VERSION()";
    }

    @Override
    public String getSchemasSQL() {
        return "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name";
    }

    @Override
    public String getTablesSQL(String schemaName) {
        return "SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schemaName
                + "' ORDER BY table_name";
    }

    @Override
    public String getViewsSQL(String schemaName) {
        return "SELECT table_name FROM information_schema.views WHERE table_schema = '" + schemaName
                + "' ORDER BY table_name";
    }

    @Override
    public String getHealthCheckSQL() {
        return "SELECT 1";
    }

    @Override
    public String getAnalyzeTableSQL(String schemaName, String tableName) {
        return "ANALYZE TABLE " + capabilityProvider.quote(schemaName) + "." + capabilityProvider.quote(tableName);
    }

    @Override
    public String getOptimizeTableSQL(String schemaName, String tableName) {
        return getAnalyzeTableSQL(schemaName, tableName);
    }
}