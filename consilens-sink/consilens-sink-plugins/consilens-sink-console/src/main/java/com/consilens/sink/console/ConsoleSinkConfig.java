package com.consilens.sink.console;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Console format sink configuration.
 *
 * <pre>
 * result:
 *   sinks:
 *     - format: console
 *       type: result
 *       properties:
 *         maxRows: 50
 *         showStatistics: true
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConsoleSinkConfig {

    /** Max diff rows to print; default 100. Set to -1 for unlimited. */
    private int maxRows = 100;

    /** Whether to print summary statistics; default true. */
    private boolean showStatistics = true;
}
