package com.consilens.conncetor.base;

import com.consilens.connector.api.DataSourceConfigBuilder;
import com.consilens.connector.api.DataSourceField;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic JDBC datasource parameter template builder.
 *
 * <p>Default template (6 fields):
 * <ol>
 * <li>host (required, input)</li>
 * <li>port (required, number)</li>
 * <li>database (required, input)</li>
 * <li>username (required, input)</li>
 * <li>password (optional, input)</li>
 * <li>properties (optional, textarea, rows 3)</li>
 * </ol>
 *
 * <p>Dialect-specific builders extend this class and override individual
 * {@code getXxxInput()} hooks (or {@link #getSchemaInput()} /
 * {@link #getSidInput()}) to customize the template. Returning {@code null}
 * from a hook excludes the field from the template.
 */
public class BaseDataSourceConfigBuilder implements DataSourceConfigBuilder {

    /** Input control type constants exposed for subclass reuse. */
    protected static final String TYPE_INPUT = "input";
    protected static final String TYPE_NUMBER = "number";
    protected static final String TYPE_TEXTAREA = "textarea";

    @Override
    public List<DataSourceField> build() {
        List<DataSourceField> fields = new ArrayList<>();
        fields.add(getHostInput());
        fields.add(getPortInput());
        DataSourceField database = getDatabaseInput();
        if (database != null) {
            fields.add(database);
        }
        DataSourceField sid = getSidInput();
        if (sid != null) {
            fields.add(sid);
        }
        DataSourceField schema = getSchemaInput();
        if (schema != null) {
            fields.add(schema);
        }
        fields.add(getUserInput());
        fields.add(getPasswordInput());
        fields.add(getPropertiesInput());
        return fields;
    }

    // ========== Field hooks (overridable) ==========

    protected DataSourceField getHostInput() {
        return field("host", "地址", TYPE_INPUT, "请填入连接地址", true, null, 0);
    }

    protected DataSourceField getPortInput() {
        return field("port", "端口", TYPE_NUMBER, "请填入端口号", true, null, 0);
    }

    protected DataSourceField getDatabaseInput() {
        return field("database", "数据库", TYPE_INPUT, "请填入数据库", true, null, 0);
    }

    /**
     * Service name field (Oracle). Returns {@code null} by default, which
     * excludes it from the template; Oracle overrides this hook.
     *
     * @return sid field, or null to omit
     */
    protected DataSourceField getSidInput() {
        return null;
    }

    /**
     * Schema field (PostgreSQL / SQL Server). Returns {@code null} by default,
     * which excludes it from the template.
     *
     * @return schema field, or null to omit
     */
    protected DataSourceField getSchemaInput() {
        return null;
    }

    protected DataSourceField getUserInput() {
        return field("username", "用户名", TYPE_INPUT, "请填入用户名", true, null, 0);
    }

    protected DataSourceField getPasswordInput() {
        return field("password", "密码", TYPE_INPUT, "请填入密码", false, null, 0);
    }

    protected DataSourceField getPropertiesInput() {
        return field("properties", "参数", TYPE_TEXTAREA, "key=value&key2=value2", false, null, 3);
    }

    // ========== Helper ==========

    protected DataSourceField field(String field, String title, String type,
                                    String placeholder, boolean required,
                                    String defaultValue, int rows) {
        return DataSourceField.builder()
                .field(field)
                .title(title)
                .type(type)
                .placeholder(placeholder)
                .required(required)
                .defaultValue(defaultValue)
                .rows(rows)
                .build();
    }
}
