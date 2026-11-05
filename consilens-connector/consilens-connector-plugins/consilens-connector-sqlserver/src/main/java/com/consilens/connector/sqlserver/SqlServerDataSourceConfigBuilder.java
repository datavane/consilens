package com.consilens.connector.sqlserver;

import com.consilens.conncetor.base.BaseDataSourceConfigBuilder;
import com.consilens.connector.api.DataSourceField;

/**
 * SQL Server datasource parameter template builder.
 *
 * <p>Adds a required {@code schema} field (default "dbo") on top of the
 * generic JDBC template.
 */
public class SqlServerDataSourceConfigBuilder extends BaseDataSourceConfigBuilder {

    @Override
    protected DataSourceField getSchemaInput() {
        return field("schema", "模式", TYPE_INPUT, "请填入模式", true, "dbo", 0);
    }
}
