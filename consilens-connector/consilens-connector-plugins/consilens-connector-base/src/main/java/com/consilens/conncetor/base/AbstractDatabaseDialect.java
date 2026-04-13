package com.consilens.conncetor.base;

import com.consilens.connector.api.*;
import com.consilens.connector.api.enums.DatabaseType;

/**
 * Abstract base class for database dialect implementations.
 * 
 * <p>
 * This class implements all dialect interfaces following the Interface
 * Segregation Principle (ISP):
 * <ul>
 * <li>{@link DatabaseDialect} - Core database identification and quoting</li>
 * <li>{@link SqlQueryGenerator} - SQL query generation</li>
 * <li>{@link MetadataQueryGenerator} - Metadata query generation</li>
 * <li>{@link DataTypeHandler} - Data type handling and conversion</li>
 * <li>{@link TransactionManager} - Transaction management</li>
 * <li>{@link CapabilityProvider} - Database capability detection</li>
 * </ul>
 * 
 * <p>
 * Concrete dialect implementations should extend this class and override
 * methods
 * as needed for database-specific behavior.
 * 
 * @since 1.0.0
 */
public abstract class AbstractDatabaseDialect
        implements DatabaseDialect {

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.UNKNOWN;
    }


    @Override
    public MetadataQueryGenerator getMetadataQueryGenerator() {
        return new BaseMetadataQueryGenerator(getCapabilityProvider());
    }

    @Override
    public DataTypeHandler getDataTypeHandler() {
        return new BaseDataTypeHandler(getCapabilityProvider());
    }

    @Override
    public TransactionManager getTransactionManager() {
        return new BaseTransactionManager();
    }

    @Override
    public CapabilityProvider getCapabilityProvider() {
        return new BaseCapabilityProvider();
    }

    @Override
    public ConnectionPoolOptimizer getConnectionPoolOptimizer() {
        return new BaseConnectionPoolOptimizer();
    }
}