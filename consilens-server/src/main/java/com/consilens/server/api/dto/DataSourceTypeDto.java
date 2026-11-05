package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A connector type discovered via the connector SPI (DatabaseDialectProvider).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceTypeDto {

    private String type;
    private int defaultPort;
    private String driverClass;
}
