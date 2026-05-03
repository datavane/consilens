package com.consilens.connector.api.write;

import java.util.List;

public final class TableWriteCompileRequest {

    private final String tableName;
    private final boolean createTable;
    private final boolean dropIfExists;
    private final List<OutputColumnSpec> columns;

    public TableWriteCompileRequest(String tableName,
                                    boolean createTable,
                                    boolean dropIfExists,
                                    List<OutputColumnSpec> columns) {
        this.tableName = tableName;
        this.createTable = createTable;
        this.dropIfExists = dropIfExists;
        this.columns = List.copyOf(columns);
    }

    public String getTableName() {
        return tableName;
    }

    public boolean isCreateTable() {
        return createTable;
    }

    public boolean isDropIfExists() {
        return dropIfExists;
    }

    public List<OutputColumnSpec> getColumns() {
        return columns;
    }
}
