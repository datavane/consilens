package com.consilens.connector.api;

/**
 * Interface for database transaction management operations.
 * 
 * <p>
 * This interface provides methods for generating SQL statements related to
 * transaction control including begin, commit, rollback, and savepoint
 * operations.
 * It is a standalone component obtained from
 * {@link DatabaseDialect#getTransactionManager()}.
 * 
 * @since 1.1.0
 * @see DatabaseDialect
 */
public interface TransactionManager {

    /**
     * Generate SQL for starting a transaction.
     * 
     * @return SQL statement to begin transaction
     */
    String getBeginTransactionSQL();

    /**
     * Generate SQL for committing a transaction.
     * 
     * @return SQL statement to commit transaction
     */
    String getCommitTransactionSQL();

    /**
     * Generate SQL for rolling back a transaction.
     * 
     * @return SQL statement to rollback transaction
     */
    String getRollbackTransactionSQL();

    /**
     * Generate SQL for creating a savepoint.
     * 
     * @param savepointName the savepoint name
     * @return SQL statement to create savepoint
     */
    String getCreateSavepointSQL(String savepointName);

    /**
     * Generate SQL for rolling back to a savepoint.
     * 
     * @param savepointName the savepoint name
     * @return SQL statement to rollback to savepoint
     */
    String getRollbackToSavepointSQL(String savepointName);

    /**
     * Generate SQL for releasing a savepoint.
     * 
     * @param savepointName the savepoint name
     * @return SQL statement to release savepoint
     */
    String getReleaseSavepointSQL(String savepointName);

    /**
     * Generate SQL for setting transaction isolation level.
     * 
     * @param isolationLevel the isolation level
     * @return SQL statement to set isolation level
     */
    String getSetTransactionIsolationSQL(String isolationLevel);
}
