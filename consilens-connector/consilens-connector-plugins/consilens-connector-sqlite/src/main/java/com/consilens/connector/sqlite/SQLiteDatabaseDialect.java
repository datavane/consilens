package com.consilens.connector.sqlite;

import com.consilens.common.enums.ChecksumAlgorithm;
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
        this.dataTypeHandler = new SQLiteDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new SQLiteSqlQueryGenerator(capabilityProvider, dataTypeHandler);
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

    private static final class SQLiteDataTypeHandler extends BaseDataTypeHandler {

        private SQLiteDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
            super(capabilityProvider, normalizationConfig);
        }

        @Override
        protected String normalizeString(String quotedCol) {
            return "COALESCE(TRIM(CAST(" + quotedCol + " AS TEXT)), '')";
        }

        @Override
        protected String normalizeInteger(String quotedCol) {
            return "COALESCE(TRIM(CAST(CAST(" + quotedCol + " AS INTEGER) AS TEXT)), '0')";
        }

        @Override
        protected String normalizeDecimal(String quotedCol) {
            return normalizeReal(quotedCol, getPrecision("decimal", 4), getRounding("decimal", true));
        }

        @Override
        protected String normalizeFloat(String quotedCol) {
            return normalizeReal(quotedCol, getPrecision("float", 6), getRounding("float", true));
        }

        @Override
        protected String normalizeDate(String quotedCol) {
            return "COALESCE(strftime('%Y-%m-%d', " + quotedCol + "), '')";
        }

        @Override
        protected String normalizeTime(String quotedCol) {
            return "COALESCE(strftime('%H:%M:%S', " + quotedCol + "), '')";
        }

        @Override
        protected String normalizeDateTime(String quotedCol) {
            return "COALESCE(strftime('%Y-%m-%d %H:%M:%S', " + quotedCol + "), '')";
        }

        @Override
        protected String normalizeTimestamp(String quotedCol) {
            return normalizeDateTime(quotedCol);
        }

        @Override
        protected String normalizeTimestampWithTimezone(String quotedCol) {
            return normalizeDateTime(quotedCol);
        }

        @Override
        protected String normalizeBoolean(String quotedCol) {
            return "CASE WHEN LOWER(TRIM(CAST(" + quotedCol
                    + " AS TEXT))) IN ('1', 'true', 't', 'y', 'yes') THEN '1' ELSE '0' END";
        }

        @Override
        protected String normalizeBlob(String quotedCol) {
            return "COALESCE(HEX(" + quotedCol + "), '')";
        }

        @Override
        protected String normalizeJson(String quotedCol) {
            return "COALESCE(CAST(" + quotedCol + " AS TEXT), '')";
        }

        @Override
        protected String normalizeDefault(String quotedCol) {
            return "COALESCE(TRIM(CAST(" + quotedCol + " AS TEXT)), '')";
        }

        @Override
        public DataType convertToDataType(String sourceType) {
            if (sourceType == null) {
                return DataType.UNKNOWN;
            }

            String type = sourceType.toLowerCase().trim();
            int parenIndex = type.indexOf('(');
            if (parenIndex >= 0) {
                type = type.substring(0, parenIndex).trim();
            }

            switch (type) {
                case "int":
                case "integer":
                case "int4":
                case "mediumint":
                    return DataType.INTEGER;
                case "tinyint":
                    return DataType.TINYINT;
                case "smallint":
                case "int2":
                    return DataType.SMALLINT;
                case "bigint":
                case "int8":
                    return DataType.BIGINT;
                case "decimal":
                case "numeric":
                case "dec":
                    return DataType.DECIMAL;
                case "float":
                    return DataType.FLOAT;
                case "real":
                    return DataType.REAL;
                case "double":
                case "double precision":
                    return DataType.DOUBLE;
                case "char":
                case "character":
                case "nchar":
                    return DataType.CHAR;
                case "varchar":
                case "character varying":
                case "varying character":
                case "nvarchar":
                    return DataType.VARCHAR;
                case "text":
                case "clob":
                    return DataType.TEXT;
                case "date":
                    return DataType.DATE;
                case "time":
                    return DataType.TIME;
                case "timetz":
                case "time with time zone":
                    return DataType.TIME_WITH_TIME_ZONE;
                case "datetime":
                    return DataType.DATETIME;
                case "timestamp":
                    return DataType.TIMESTAMP;
                case "timestamptz":
                case "timestamp with time zone":
                    return DataType.TIMESTAMP_WITH_TIMEZONE;
                case "boolean":
                case "bool":
                    return DataType.BOOLEAN;
                case "json":
                    return DataType.JSON;
                case "jsonb":
                    return DataType.JSONB;
                case "blob":
                    return DataType.BLOB;
                case "binary":
                    return DataType.BINARY;
                case "varbinary":
                    return DataType.VARBINARY;
                case "uuid":
                    return DataType.UUID;
                default:
                    return super.convertToDataType(sourceType);
            }
        }

        private String normalizeReal(String quotedCol, int precision, boolean rounding) {
            if (precision == 0) {
                String numericExpression = rounding
                        ? "ROUND(CAST(" + quotedCol + " AS REAL), 0)"
                        : "CAST(CAST(" + quotedCol + " AS REAL) AS INTEGER)";
                return "COALESCE(TRIM(CAST(" + numericExpression + " AS TEXT)), '0')";
            }

            String scale = "1" + "0".repeat(precision);
            String format = "% ." + precision + "f";
            String defaultValue = "0." + "0".repeat(precision);
            String integerSuffix = "." + "0".repeat(precision);
            String numericExpression = rounding
                    ? "ROUND(CAST(" + quotedCol + " AS REAL), " + precision + ")"
                    : "(CAST((CAST(" + quotedCol + " AS REAL) * " + scale + ") AS INTEGER) / " + scale + ".0)";

            return "CASE WHEN " + quotedCol + " IS NULL THEN '" + defaultValue + "' "
                    + "WHEN typeof(" + quotedCol + ") = 'integer' THEN CAST(CAST(" + quotedCol + " AS INTEGER) AS TEXT) || '"
                    + integerSuffix + "' ELSE printf('" + format.replace(" ", "") + "', " + numericExpression + ") END";
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

        private final CapabilityProvider capabilityProvider;
        private final DataTypeHandler dataTypeHandler;

        private SQLiteSqlQueryGenerator(CapabilityProvider capabilityProvider, DataTypeHandler dataTypeHandler) {
            super(capabilityProvider);
            this.capabilityProvider = capabilityProvider;
            this.dataTypeHandler = dataTypeHandler;
        }

        @Override
        public String getChecksumSQL(String schemaName, String tableName,
                                     List<String> keyColumns,
                                     List<String> columns,
                                     Map<String, DataType> columnDataTypes,
                                     String whereClause,
                                     ChecksumAlgorithm checksumAlgorithm) {
            throw new UnsupportedOperationException(
                    "SQLite checksum SQL generation is not supported because SQLite hashes are computed in code");
        }

        @Override
        public String getRowHashSQL(String schemaName, String tableName,
                                    List<String> primaryKeys,
                                    List<String> columns,
                                    Map<String, DataType> columnDataTypes,
                                    String whereClause) {
            throw new UnsupportedOperationException(
                    "SQLite row-hash SQL generation is not supported because SQLite hashes are computed in code");
        }

        private void appendTableReference(StringBuilder sql, String schemaName, String tableName) {
            if (schemaName != null && !schemaName.trim().isEmpty()) {
                sql.append(capabilityProvider.quote(schemaName)).append('.');
            }
            sql.append(capabilityProvider.quote(tableName));
        }

        private void appendWhereClause(StringBuilder sql, String whereClause) {
            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql.append(" WHERE ").append(whereClause);
            }
        }

        private String buildKeyExpression(List<String> keyColumns, Map<String, DataType> columnDataTypes) {
            if (keyColumns == null || keyColumns.isEmpty()) {
                return "'1'";
            }
            return buildDelimitedExpression(keyColumns, columnDataTypes);
        }

        private String buildDelimitedExpression(List<String> columns, Map<String, DataType> columnDataTypes) {
            if (columns == null || columns.isEmpty()) {
                return "''";
            }

            StringBuilder expression = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    expression.append(" || '|' || ");
                }
                String column = columns.get(i);
                DataType dataType = columnDataTypes == null ? null : columnDataTypes.get(column);
                expression.append(dataTypeHandler.normalizeColumn(column, dataType));
            }
            return expression.toString();
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
