package com.consilens.connector.api;

import java.util.List;

/**
 * Builds the datasource parameter template (the ordered list of form fields)
 * for a specific database dialect.
 *
 * <p>A dialect may expose its builder through
 * {@link DatabaseDialect#getDataSourceConfigBuilder()}; when it returns
 * {@code null} the server falls back to the generic JDBC template.
 *
 * @since 1.0.0
 */
public interface DataSourceConfigBuilder {

    /**
     * Build the ordered list of datasource form fields for this dialect.
     *
     * @return datasource parameter template fields
     */
    List<DataSourceField> build();
}
