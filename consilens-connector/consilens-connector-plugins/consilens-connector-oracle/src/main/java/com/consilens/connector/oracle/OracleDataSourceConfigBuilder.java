package com.consilens.connector.oracle;

import com.consilens.conncetor.base.BaseDataSourceConfigBuilder;
import com.consilens.connector.api.DataSourceField;

/**
 * Oracle datasource parameter template builder.
 *
 * <p>Oracle uses a service name ({@code sid}) instead of a database name, so
 * the template is: host (required) / port (required) / sid (required) /
 * username (required) / password (optional) / properties (optional). No
 * {@code database} or {@code schema} fields.
 */
public class OracleDataSourceConfigBuilder extends BaseDataSourceConfigBuilder {

    /** Exclude the generic database field; Oracle uses sid instead. */
    @Override
    protected DataSourceField getDatabaseInput() {
        return null;
    }

    @Override
    protected DataSourceField getSidInput() {
        return field("sid", "sid", TYPE_INPUT, "请填入SID", true, null, 0);
    }
}
