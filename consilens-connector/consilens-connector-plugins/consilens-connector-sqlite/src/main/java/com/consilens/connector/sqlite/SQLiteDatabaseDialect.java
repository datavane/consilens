package com.consilens.connector.sqlite;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.connector.api.MetadataQueryGenerator;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.conncetor.base.AbstractDatabaseDialect;
import com.consilens.conncetor.base.BaseConnectionPoolOptimizer;
import com.consilens.conncetor.base.BaseDataTypeHandler;
import com.consilens.conncetor.base.BaseMetadataQueryGenerator;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class SQLiteDatabaseDialect extends AbstractDatabaseDialect {

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;
    private final SqlQueryGenerator sqlQueryGenerator;
    private final MetadataQueryGenerator metadataQueryGenerator;
    private final ConnectionPoolOptimizer connectionPoolOptimizer;

    public SQLiteDatabaseDialect() {
        this(null);
    }

    public SQLiteDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new SQLiteCapabilityProvider();
        this.dataTypeHandler = new BaseDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new SQLiteSqlQueryGenerator(capabilityProvider);
        this.metadataQueryGenerator = new SQLiteMetadataQueryGenerator(capabilityProvider);
        this.connectionPoolOptimizer = new SQLiteConnectionPoolOptimizer();
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.SQLITE;
    }

    @Override
    public CapabilityProvider getCapabilityProvider() {
        return capabilityProvider;
    }

    @Override
    public SqlQueryGenerator getSqlQueryGenerator() {
        return sqlQueryGenerator;
    }

    @Override
    public MetadataQueryGenerator getMetadataQueryGenerator() {
        return metadataQueryGenerator;
    }

    @Override
    public DataTypeHandler getDataTypeHandler() {
        return dataTypeHandler;
    }

    @Override
    public ConnectionPoolOptimizer getConnectionPoolOptimizer() {
        return connectionPoolOptimizer;
    }

    private static final class SQLiteCapabilityProvider implements CapabilityProvider {

        private static final Set<DatabaseFeature> SUPPORTED_FEATURES = Collections.unmodifiableSet(
                EnumSet.of(DatabaseFeature.TRANSACTIONS, DatabaseFeature.SAVEPOINTS));

        @Override
        public boolean supportsFeature(DatabaseFeature feature) {
            return SUPPORTED_FEATURES.contains(feature);
        }

        @Override
        public Set<DatabaseFeature> getSupportedFeatures() {
            return SUPPORTED_FEATURES;
        }

        @Override
        public String getDefaultSchema() {
            return "main";
        }

        @Override
        public String getCatalogSeparator() {
            return ".";
        }

        @Override
        public boolean supportsSchemas() {
            return false;
        }

        @Override
        public String getPaginationHint(long offset, long limit) {
            return "";
        }

        @Override
        public char getWildcardEscapeChar() {
            return '\\';
        }

        @Override
        public String escapePattern(String pattern) {
            if (pattern == null) {
                return null;
            }
            return pattern.replace("'", "''");
        }

        @Override
        public String getOpenQuote() {
            return "\"";
        }

        @Override
        public String getCloseQuote() {
            return "\"";
        }
    }

    private static final class SQLiteMetadataQueryGenerator extends BaseMetadataQueryGenerator {
        private final CapabilityProvider capabilityProvider;

        private SQLiteMetadataQueryGenerator(CapabilityProvider capabilityProvider) {
            super(capabilityProvider);
            this.capabilityProvider = capabilityProvider;
        }

        @Override
        public String getTableExistsSQL(String schemaName, String tableName) {
            return "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table', 'view') AND name = ?";
        }

        @Override
        public String getColumnExistsSQL(String schemaName, String tableName, String columnName) {
            return "SELECT COUNT(*) FROM pragma_table_info('" + escapeIdentifier(tableName) + "') WHERE name = ?";
        }

        @Override
        public String getPrimaryKeySQL(String schemaName, String tableName) {
            return "SELECT name FROM pragma_table_info('" + escapeIdentifier(tableName) + "') WHERE pk > 0 ORDER BY pk";
        }

        @Override
        public String getTableColumnsSQL(String schemaName, String tableName) {
            return "SELECT name AS column_name, type AS data_type, CASE WHEN \"notnull\" = 0 THEN 'YES' ELSE 'NO' END AS is_nullable, "
                    + "NULL AS character_maximum_length, NULL AS numeric_precision, NULL AS numeric_scale, "
                    + "NULL AS datetime_precision, dflt_value AS column_default "
                    + "FROM pragma_table_info('" + escapeIdentifier(tableName) + "') ORDER BY cid";
        }

        @Override
        public String getPrimaryKeysSQL(String schemaName, String tableName) {
            return "SELECT name AS column_name, pk AS ordinal_position FROM pragma_table_info('"
                    + escapeIdentifier(tableName) + "') WHERE pk > 0 ORDER BY pk";
        }

        @Override
        public String getForeignKeysSQL(String schemaName, String tableName) {
            return "SELECT \"from\" AS column_name, '' AS foreign_table_schema, \"table\" AS foreign_table_name, \"to\" AS foreign_column_name "
                    + "FROM pragma_foreign_key_list('" + escapeIdentifier(tableName) + "')";
        }

        @Override
        public String getIndexesSQL(String schemaName, String tableName) {
            return "SELECT il.name AS index_name, ii.name AS column_name, CASE WHEN il.unique = 1 THEN 0 ELSE 1 END AS non_unique "
                    + "FROM pragma_index_list('" + escapeIdentifier(tableName) + "') il "
                    + "JOIN pragma_index_info(il.name) ii ORDER BY il.name, ii.seqno";
        }

        @Override
        public String getDatabaseMetadataSQL() {
            return "SELECT sqlite_version() AS version";
        }

        @Override
        public String getSchemasSQL() {
            return "SELECT 'main' AS schema_name";
        }

        @Override
        public String getTablesSQL(String schemaName) {
            return "SELECT name AS table_name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name";
        }

        @Override
        public String getViewsSQL(String schemaName) {
            return "SELECT name AS table_name FROM sqlite_master WHERE type = 'view' ORDER BY name";
        }

        @Override
        public String getAnalyzeTableSQL(String schemaName, String tableName) {
            return "ANALYZE " + capabilityProvider.quote(tableName);
        }

        @Override
        public String getOptimizeTableSQL(String schemaName, String tableName) {
            return getAnalyzeTableSQL(schemaName, tableName);
        }

        private String escapeIdentifier(String identifier) {
            return identifier == null ? "" : identifier.replace("'", "''");
        }
    }

    private static final class SQLiteSqlQueryGenerator extends BaseSqlQueryGenerator {

        private SQLiteSqlQueryGenerator(CapabilityProvider capabilityProvider) {
            super(capabilityProvider);
        }

        @Override
        public String getChecksumSQL(String schemaName, String tableName,
                                     List<String> keyColumns,
                                     List<String> columns,
                                     Map<String, DataType> columnDataTypes,
                                     String whereClause,
                                     com.consilens.common.enums.ChecksumAlgorithm checksumAlgorithm) {
            throw new UnsupportedOperationException("SQLite checksum SQL generation is not implemented");
        }

        @Override
        public String getRowHashSQL(String schemaName, String tableName,
                                    List<String> primaryKeys,
                                    List<String> columns,
                                    Map<String, DataType> columnDataTypes,
                                    String whereClause) {
            throw new UnsupportedOperationException("SQLite row hash SQL generation is not implemented");
        }
    }

    private static final class SQLiteConnectionPoolOptimizer extends BaseConnectionPoolOptimizer {

        @Override
        public Properties getOptimizationProperties(boolean useSSL) {
            return new Properties();
        }

        @Override
        public PoolConfiguration getDefaultConfiguration() {
            PoolConfiguration config = new PoolConfiguration();
            config.setDatabaseType(DatabaseType.SQLITE);
            config.setMaxPoolSize(1);
            config.setMinIdle(1);
            config.setMaxLifetime(0);
            config.setIdleTimeout(0);
            config.setConnectionTimeout(30000);
            config.setLeakDetectionThreshold(0);
            config.setValidationQuery("SELECT 1");
            return config;
        }
    }
}
