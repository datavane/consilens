package com.consilens.connector.api.write;

import java.util.List;

public final class TableWritePlan {

    private final String connectorType;
    private final String dropTableSql;
    private final String createTableSql;
    private final String insertSql;
    private final List<WriteColumnSpec> columns;

    public TableWritePlan(String connectorType,
                          String dropTableSql,
                          String createTableSql,
                          String insertSql,
                          List<WriteColumnSpec> columns) {
        this.connectorType = connectorType;
        this.dropTableSql = dropTableSql;
        this.createTableSql = createTableSql;
        this.insertSql = insertSql;
        this.columns = List.copyOf(columns);
    }

    public String getConnectorType() {
        return connectorType;
    }

    public String getDropTableSql() {
        return dropTableSql;
    }

    public String getCreateTableSql() {
        return createTableSql;
    }

    public String getInsertSql() {
        return insertSql;
    }

    public List<WriteColumnSpec> getColumns() {
        return columns;
    }
}
