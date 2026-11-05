package com.consilens.connector.trino;

import com.consilens.conncetor.base.BaseDataSourceConfigBuilder;
import com.consilens.connector.api.DataSourceField;

/**
 * Trino datasource parameter template builder.
 *
 * <p>{@code database} and {@code password} are both optional (Trino
 * catalogs/schemas may be used without a fixed database and without
 * authentication).
 */
public class TrinoDataSourceConfigBuilder extends BaseDataSourceConfigBuilder {

    @Override
    protected DataSourceField getDatabaseInput() {
        return field("database", "数据库", TYPE_INPUT, "请填入数据库", false, null, 0);
    }

    @Override
    protected DataSourceField getPasswordInput() {
        return field("password", "密码", TYPE_INPUT, "请填入密码", false, null, 0);
    }
}
