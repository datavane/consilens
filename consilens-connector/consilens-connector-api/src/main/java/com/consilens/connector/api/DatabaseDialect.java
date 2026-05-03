package com.consilens.connector.api;

import com.consilens.connector.api.write.TableWriteCompiler;

/**
 * Database dialect interface - main entry point for database-specific
 * operations.
 * 
 * <p>
 * This interface follows the composition pattern, providing access to
 * specialized
 * component interfaces through getter methods:
 * <ul>
 * <li>{@link SqlQueryGenerator} - SQL query generation</li>
 * <li>{@link MetadataQueryGenerator} - Metadata query generation</li>
 * <li>{@link DataTypeHandler} - Data type handling and conversion</li>
 * <li>{@link TransactionManager} - Transaction management</li>
 * <li>{@link CapabilityProvider} - Database capability detection</li>
 * </ul>
 * 
 * <p>
 * <b>Usage Example:</b>
 * 
 * <pre>{@code
 * DatabaseDialect dialect = DialectFactory.getDialect("mysql");
 * 
 * // Get SQL query component
 * SqlQueryGenerator queryGen = dialect.getSqlQueryGenerator();
 * String sql = queryGen.getCountSQL("myschema", "mytable", null);
 * 
 * // Get metadata component
 * MetadataQueryGenerator metadataGen = dialect.getMetadataQueryGenerator();
 * String existsSQL = metadataGen.getTableExistsSQL("myschema", "mytable");
 * }</pre>
 * 
 * @since 1.0.0
 */
public interface DatabaseDialect {

    // ========== Core Properties ==========

    /**
     * Get the connector type identifier this dialect supports (e.g. "mysql", "postgresql").
     *
     * @return lowercase connector type string
     */
    String getConnectorType();

    // ========== Component Access Methods ==========

    /**
     * Get the SQL query generator component.
     * 
     * @return SQL query generator for this dialect
     */
    SqlQueryGenerator getSqlQueryGenerator();

    /**
     * Get the metadata query generator component.
     * 
     * @return metadata query generator for this dialect
     */
    MetadataQueryGenerator getMetadataQueryGenerator();

    /**
     * Get the data type handler component.
     * 
     * @return data type handler for this dialect
     */
    DataTypeHandler getDataTypeHandler();

    /**
     * Get the transaction manager component.
     * 
     * @return transaction manager for this dialect
     */
    TransactionManager getTransactionManager();

    /**
     * Get the capability provider component.
     * 
     * @return capability provider for this dialect
     */
    CapabilityProvider getCapabilityProvider();

    /**
     * Get the connection pool optimizer component.
     * 
     * @return connection pool optimizer for this dialect
     */
    ConnectionPoolOptimizer getConnectionPoolOptimizer();

    default TableWriteCompiler getTableWriteCompiler() {
        throw new UnsupportedOperationException("Table sink write is not supported for connectorType=" + getConnectorType());
    }
}
