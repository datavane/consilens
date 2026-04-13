package com.consilens.conncetor.base;

import com.consilens.connector.api.TransactionManager;

public class BaseTransactionManager implements TransactionManager {

    @Override
    public String getSetTransactionIsolationSQL(String isolationLevel) {
        return "SET TRANSACTION ISOLATION LEVEL " + isolationLevel;
    }

    @Override
    public String getBeginTransactionSQL() {
        return "BEGIN";
    }

    @Override
    public String getCommitTransactionSQL() {
        return "COMMIT";
    }

    @Override
    public String getRollbackTransactionSQL() {
        return "ROLLBACK";
    }

    @Override
    public String getCreateSavepointSQL(String savepointName) {
        return "SAVEPOINT " + savepointName;
    }

    @Override
    public String getRollbackToSavepointSQL(String savepointName) {
        return "ROLLBACK TO SAVEPOINT " + savepointName;
    }

    @Override
    public String getReleaseSavepointSQL(String savepointName) {
        return "RELEASE SAVEPOINT " + savepointName;
    }
}
