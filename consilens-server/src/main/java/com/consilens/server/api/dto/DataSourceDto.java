package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Data source summary returned to the UI. Connection parameters are returned
 * WITHOUT the password (kept server-side; edits leaving it blank preserve the
 * old value).
 *
 * <p>The id is a String: snowflake ids exceed JavaScript's safe integer range
 * (2^53), so they must travel as strings to avoid precision loss.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceDto {

    private String id;
    private String name;
    private String type;
    private Instant createdAt;
    private Instant updatedAt;

    /** Connection parameters for edit-form echo; password is never included. */
    private java.util.Map<String, Object> param;
}
