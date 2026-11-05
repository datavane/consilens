package com.consilens.connector.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A single form field of a datasource parameter template, produced by
 * {@link DataSourceConfigBuilder} per database dialect.
 *
 * <p>Serialized as JSON with lowerCamelCase keys (field, title, type,
 * placeholder, required, defaultValue, rows, options) so the frontend can
 * render the datasource form dynamically.
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceField {

    /** Parameter key stored in the datasource param map (e.g. "host"). */
    private String field;

    /** Human-readable label rendered next to the input. */
    private String title;

    /** Input control type: "input" | "number" | "textarea". */
    private String type;

    /** Placeholder text shown inside the input. */
    private String placeholder;

    /** Whether the field must be filled in. */
    private boolean required;

    /** Default value pre-filled in the form. */
    private Object defaultValue;

    /** Row count for textarea controls. */
    private int rows;

    /** Selectable options (label/value pairs) for select controls. */
    private List<Map<String, Object>> options;

    /**
     * Whether the field carries a credential (password/token/secret). Agent
     * tooling must never place such values into model-visible arguments.
     */
    private boolean sensitive;
}
