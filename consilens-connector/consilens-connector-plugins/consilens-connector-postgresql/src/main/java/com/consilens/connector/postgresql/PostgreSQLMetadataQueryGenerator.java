package com.consilens.connector.postgresql;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.conncetor.base.BaseMetadataQueryGenerator;

/**
 * PostgreSQL metadata query generator.
 * 
 * <p>
 * Generates SQL queries for retrieving PostgreSQL metadata from
 * information_schema.
 * 
 * @since 1.0.0
 */
public class PostgreSQLMetadataQueryGenerator extends BaseMetadataQueryGenerator {

    private final CapabilityProvider capabilityProvider;

    public PostgreSQLMetadataQueryGenerator(CapabilityProvider capabilityProvider) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
    }

    @Override
    public String getTableExistsSQL(String schemaName, String tableName) {
        return "SELECT EXISTS (" +
                "SELECT 1 FROM information_schema.tables " +
                "WHERE table_schema = '" + escapeString(schemaName) + "' AND table_name = '" + escapeString(tableName)
                + "')";
    }

    @Override
    public String getTableColumnsSQL(String schemaName, String tableName) {
        return "SELECT column_name, data_type, is_nullable, " +
                "character_maximum_length, numeric_precision, numeric_scale, " +
                "datetime_precision, column_default, udt_name " +
                "FROM information_schema.columns " +
                "WHERE table_schema = '" + escapeString(schemaName) + "' " +
                "AND table_name = '" + escapeString(tableName) + "' " +
                "ORDER BY ordinal_position";
    }

    @Override
    public String getPrimaryKeysSQL(String schemaName, String tableName) {
        return "SELECT kcu.column_name, kcu.ordinal_position " +
                "FROM information_schema.key_column_usage kcu " +
                "JOIN information_schema.table_constraints tc " +
                // 约束名只在 schema 内唯一：只按名字 join，跨 schema 的同名主键会把两边的列混在一起
                // （结果里出现重复列，键顺序与唯一性判断随之失真）
                "ON kcu.constraint_name = tc.constraint_name " +
                "AND kcu.constraint_schema = tc.constraint_schema " +
                "AND kcu.table_schema = tc.table_schema " +
                "AND kcu.table_name = tc.table_name " +
                "WHERE tc.constraint_type = 'PRIMARY KEY' " +
                "AND kcu.table_schema = '" + escapeString(schemaName) + "' " +
                "AND kcu.table_name = '" + escapeString(tableName) + "' " +
                "ORDER BY kcu.ordinal_position";
    }

    @Override
    public String getHealthCheckSQL() {
        return "SELECT 1, version() as version, current_database() as database";
    }

    @Override
    public String getDatabaseMetadataSQL() {
        return "SELECT version() as version, current_database() as database, current_user as user, inet_server_addr() as server_addr";
    }

    @Override
    public String getSchemasSQL() {
        return "SELECT schema_name FROM information_schema.schemata " +
                "WHERE schema_name NOT IN ('information_schema', 'pg_catalog') " +
                "ORDER BY schema_name";
    }

    @Override
    public String getDatabaseListSQL() {
        return "SELECT datname FROM pg_database " +
                "WHERE datistemplate = false " +
                "AND has_database_privilege(datname, 'CONNECT') " +
                "ORDER BY datname";
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
        return "SELECT " +
                "    i.relname as index_name, " +
                "    a.attname as column_name, " +
                "    NOT ix.indisunique as non_unique, " +
                "    array_position(ix.indkey, a.attnum) as seq_in_index " +
                "FROM " +
                "    pg_class t, " +
                "    pg_class i, " +
                "    pg_index ix, " +
                "    pg_attribute a, " +
                "    pg_namespace n " +
                "WHERE " +
                "    t.oid = ix.indrelid " +
                "    AND i.oid = ix.indexrelid " +
                "    AND a.attrelid = t.oid " +
                "    AND a.attnum = ANY(ix.indkey) " +
                "    AND t.relkind = 'r' " +
                "    AND t.relname = '" + escapeString(tableName) + "' " +
                "    AND n.oid = t.relnamespace " +
                "    AND n.nspname = '" + escapeString(schemaName) + "' " +
                "ORDER BY " +
                "    index_name, " +
                "    seq_in_index";
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
        return "ANALYZE " + capabilityProvider.quote(schemaName) + "." +
                capabilityProvider.quote(tableName);
    }

    @Override
    public String getOptimizeTableSQL(String schemaName, String tableName) {
        return "VACUUM ANALYZE " + capabilityProvider.quote(schemaName) + "." +
                capabilityProvider.quote(tableName);
    }

    /**
     * PostgreSQL 默认 standard_conforming_strings=on：反斜杠不是转义符，
     * 只把单引号翻倍即可；再翻倍反斜杠会改变实际匹配的名称。
     */
    private String escapeString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }
}
