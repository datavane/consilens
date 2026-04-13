package com.consilens.connector.oracle;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.conncetor.base.BaseMetadataQueryGenerator;

/**
 * Oracle metadata query generator.
 */
public class OracleMetadataQueryGenerator extends BaseMetadataQueryGenerator {

    private final CapabilityProvider capabilityProvider;

    public OracleMetadataQueryGenerator(CapabilityProvider capabilityProvider) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
    }

    @Override
    public String getTableExistsSQL(String schemaName, String tableName) {
        return "SELECT COUNT(*) FROM ALL_TABLES " +
                "WHERE OWNER = '" + escapeString(schemaName) + "' " +
                "AND TABLE_NAME = '" + escapeString(tableName) + "'";
    }

    @Override
    public String getTableColumnsSQL(String schemaName, String tableName) {
        return "SELECT COLUMN_NAME, DATA_TYPE, NULLABLE, " +
                "DATA_LENGTH, DATA_PRECISION, DATA_SCALE, " +
                "DATA_DEFAULT " +
                "FROM ALL_TAB_COLUMNS " +
                "WHERE OWNER = '" + escapeString(schemaName) + "' " +
                "AND TABLE_NAME = '" + escapeString(tableName) + "' " +
                "ORDER BY COLUMN_ID";
    }

    @Override
    public String getPrimaryKeysSQL(String schemaName, String tableName) {
        return "SELECT cols.COLUMN_NAME, cols.POSITION " +
                "FROM ALL_CONSTRAINTS cons " +
                "JOIN ALL_CONS_COLUMNS cols ON cons.CONSTRAINT_NAME = cols.CONSTRAINT_NAME " +
                "WHERE cons.CONSTRAINT_TYPE = 'P' " +
                "AND cons.OWNER = '" + escapeString(schemaName) + "' " +
                "AND cons.TABLE_NAME = '" + escapeString(tableName) + "' " +
                "ORDER BY cols.POSITION";
    }

    @Override
    public String getHealthCheckSQL() {
        return "SELECT 1 as status, BANNER as version FROM V$VERSION WHERE ROWNUM = 1";
    }

    @Override
    public String getDatabaseMetadataSQL() {
        return "SELECT BANNER as version, SYS_CONTEXT('userenv','db_name') as database, USER as username FROM V$VERSION WHERE ROWNUM = 1";
    }

    @Override
    public String getSchemasSQL() {
        return "SELECT USERNAME FROM ALL_USERS " +
                "WHERE USERNAME NOT IN ('SYS', 'SYSTEM', 'OUTLN', 'DBSNMP') " +
                "ORDER BY USERNAME";
    }

    @Override
    public String getTablesSQL(String schemaName) {
        return "SELECT TABLE_NAME FROM ALL_TABLES " +
                "WHERE OWNER = '" + escapeString(schemaName) + "' " +
                "ORDER BY TABLE_NAME";
    }

    @Override
    public String getViewsSQL(String schemaName) {
        return "SELECT VIEW_NAME FROM ALL_VIEWS " +
                "WHERE OWNER = '" + escapeString(schemaName) + "' " +
                "ORDER BY VIEW_NAME";
    }

    @Override
    public String getIndexesSQL(String schemaName, String tableName) {
        return "SELECT ind.INDEX_NAME, col.COLUMN_NAME, ind.UNIQUENESS, col.COLUMN_POSITION " +
                "FROM ALL_INDEXES ind " +
                "JOIN ALL_IND_COLUMNS col ON ind.INDEX_NAME = col.INDEX_NAME " +
                "WHERE ind.OWNER = '" + escapeString(schemaName) + "' " +
                "AND ind.TABLE_NAME = '" + escapeString(tableName) + "' " +
                "ORDER BY ind.INDEX_NAME, col.COLUMN_POSITION";
    }

    @Override
    public String getForeignKeysSQL(String schemaName, String tableName) {
        return "SELECT cons.CONSTRAINT_NAME, cols.COLUMN_NAME, " +
                "rcons.TABLE_NAME as REFERENCED_TABLE_NAME, rcols.COLUMN_NAME as REFERENCED_COLUMN_NAME " +
                "FROM ALL_CONSTRAINTS cons " +
                "JOIN ALL_CONS_COLUMNS cols ON cons.CONSTRAINT_NAME = cols.CONSTRAINT_NAME " +
                "JOIN ALL_CONSTRAINTS rcons ON cons.R_CONSTRAINT_NAME = rcons.CONSTRAINT_NAME " +
                "JOIN ALL_CONS_COLUMNS rcols ON rcons.CONSTRAINT_NAME = rcols.CONSTRAINT_NAME " +
                "WHERE cons.CONSTRAINT_TYPE = 'R' " +
                "AND cons.OWNER = '" + escapeString(schemaName) + "' " +
                "AND cons.TABLE_NAME = '" + escapeString(tableName) + "' " +
                "ORDER BY cons.CONSTRAINT_NAME";
    }

    @Override
    public String getAnalyzeTableSQL(String schemaName, String tableName) {
        return "ANALYZE TABLE " + capabilityProvider.quote(schemaName) + "." +
                capabilityProvider.quote(tableName) + " COMPUTE STATISTICS";
    }

    @Override
    public String getOptimizeTableSQL(String schemaName, String tableName) {
        return "ALTER TABLE " + capabilityProvider.quote(schemaName) + "." +
                capabilityProvider.quote(tableName) + " MOVE";
    }

    private String escapeString(String value) {
        if (value == null)
            return "";
        return value.replace("'", "''");
    }
}
