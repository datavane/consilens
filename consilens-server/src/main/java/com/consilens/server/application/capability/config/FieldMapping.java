package com.consilens.server.application.capability.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One-to-one column mapping between the source and target tables.
 * When present, the engine compares source column against target column
 * positionally (column names may differ between the two sides).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldMapping {

    private String source;

    private String target;
}
