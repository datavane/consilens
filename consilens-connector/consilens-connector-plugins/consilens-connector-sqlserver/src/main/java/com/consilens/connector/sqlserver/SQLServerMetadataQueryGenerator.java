package com.consilens.connector.sqlserver;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.conncetor.base.BaseMetadataQueryGenerator;

/**
 * SQL Server metadata query generator.
 */
public class SQLServerMetadataQueryGenerator extends BaseMetadataQueryGenerator {

    private final CapabilityProvider capabilityProvider;

    public SQLServerMetadataQueryGenerator(CapabilityProvider capabilityProvider) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
    }

    @Override
    public String getTableExistsSQL(String schemaName, String tableName) {
        return "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = '" + escapeString(schemaName) + "' " +
                "AND TABLE_NAME = '" + escapeString(tableName) + "'";
    }

    @Override
    public String getTableColumnsSQL(String schemaName, String tableName) {
        return "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, " +
                "CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, " +
                "DATETIME_PRECISION, COLUMN_DEFAULT " +
                "FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = '" + escapeString(schemaName) + "' " +
                "AND TABLE_NAME = '" + escapeString(tableName) + "' " +
                "ORDER BY ORDINAL_POSITION";
    }

    @Override
    public String getPrimaryKeysSQL(String schemaName, String tableName) {
        return "SELECT kcu.COLUMN_NAME, kcu.ORDINAL_POSITION " +
                "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu " +
                "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                "ON kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
                "WHERE tc.CONSTRAINT_TYPE = 'PRIMARY KEY' " +
                "AND kcu.TABLE_SCHEMA = '" + escapeString(schemaName) + "' " +
                "AND kcu.TABLE_NAME = '" + escapeString(tableName) + "' " +
                "ORDER BY kcu.ORDINAL_POSITION";
    }

    @Override
    public String getHealthCheckSQL() {
        return "SELECT 1 as status, @@VERSION as version";
    }

    @Override
    public String getDatabaseMetadataSQL() {
        return "SELECT @@VERSION as version, DB_NAME() as database, SYSTEM_USER as user";
    }

    @Override
    public String getSchemasSQL() {
        return "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA " +
                "WHERE SCHEMA_NAME NOT IN ('INFORMATION_SCHEMA', 'sys', 'guest') " +
                "ORDER BY SCHEMA_NAME";
    }

    @Override
    public String getTablesSQL(String schemaName) {
        return "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = '" + escapeString(schemaName) + "' " +
                "AND TABLE_TYPE = 'BASE TABLE' " +
                "ORDER BY TABLE_NAME";
    }

    @Override
    public String getViewsSQL(String schemaName) {
        return "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.VIEWS " +
                "WHERE TABLE_SCHEMA = '" + escapeString(schemaName) + "' " +
                "ORDER BY TABLE_NAME";
    }

    @Override
    public String getIndexesSQL(String schemaName, String tableName) {
        return "SELECT i.name as index_name, c.name as column_name, " +
                "i.is_unique, ic.key_ordinal " +
                "FROM sys.indexes i " +
                "JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id " +
                "JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id " +
                "JOIN sys.tables t ON i.object_id = t.object_id " +
                "JOIN sys.schemas s ON t.schema_id = s.schema_id " +
                "WHERE s.name = '" + escapeString(schemaName) + "' " +
                "AND t.name = '" + escapeString(tableName) + "' " +
                "ORDER BY i.name, ic.key_ordinal";
    }

    @Override
    public String getForeignKeysSQL(String schemaName, String tableName) {
        return "SELECT fk.name as constraint_name, c.name as column_name, " +
                "rt.name as referenced_table_name, rc.name as referenced_column_name " +
                "FROM sys.foreign_keys fk " +
                "JOIN sys.foreign_key_columns fkc ON fk.object_id = fkc.constraint_object_id " +
                "JOIN sys.columns c ON fkc.parent_object_id = c.object_id AND fkc.parent_column_id = c.column_id " +
                "JOIN sys.tables t ON fk.parent_object_id = t.object_id " +
                "JOIN sys.schemas s ON t.schema_id = s.schema_id " +
                "JOIN sys.tables rt ON fkc.referenced_object_id = rt.object_id " +
                "JOIN sys.columns rc ON fkc.referenced_object_id = rc.object_id AND fkc.referenced_column_id = rc.column_id "
                +
                "WHERE s.name = '" + escapeString(schemaName) + "' " +
                "AND t.name = '" + escapeString(tableName) + "' " +
                "ORDER BY fk.name";
    }

    @Override
    public String getAnalyzeTableSQL(String schemaName, String tableName) {
        return "UPDATE STATISTICS " + capabilityProvider.quote(schemaName) + "." +
                capabilityProvider.quote(tableName);
    }

    @Override
    public String getOptimizeTableSQL(String schemaName, String tableName) {
        return "ALTER INDEX ALL ON " + capabilityProvider.quote(schemaName) + "." +
                capabilityProvider.quote(tableName) + " REBUILD";
    }

    private String escapeString(String value) {
        if (value == null)
            return "";
        return value.replace("'", "''");
    }
}
