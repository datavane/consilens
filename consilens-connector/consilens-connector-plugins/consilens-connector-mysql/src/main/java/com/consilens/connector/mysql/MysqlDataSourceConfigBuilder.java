package com.consilens.connector.mysql;

import com.consilens.conncetor.base.BaseDataSourceConfigBuilder;
import com.consilens.connector.api.DataSourceField;

/**
 * MySQL datasource parameter template builder.
 *
 * <p>Same as the generic JDBC template except that {@code database} is
 * optional (MySQL connections may omit the database name).
 */
public class MysqlDataSourceConfigBuilder extends BaseDataSourceConfigBuilder {

    @Override
    protected DataSourceField getDatabaseInput() {
        return field("database", "数据库", TYPE_INPUT, "请填入数据库", false, null, 0);
    }
}
