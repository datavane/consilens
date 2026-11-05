package com.consilens.connector.postgresql;

import com.consilens.conncetor.base.BaseDataSourceConfigBuilder;
import com.consilens.connector.api.DataSourceField;

/**
 * PostgreSQL datasource parameter template builder.
 *
 * <p>Adds a required {@code schema} field (default "public") on top of the
 * generic JDBC template.
 */
public class PostgreSQLDataSourceConfigBuilder extends BaseDataSourceConfigBuilder {

    @Override
    protected DataSourceField getSchemaInput() {
        return field("schema", "模式", TYPE_INPUT, "请填入模式", true, "public", 0);
    }
}
