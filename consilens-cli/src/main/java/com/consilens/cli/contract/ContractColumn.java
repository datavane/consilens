package com.consilens.cli.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Normalized column definition extracted from an ODCS contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractColumn {

    private String name;
    private String logicalType;
    private String physicalType;
    private Boolean required;
    private Boolean unique;
    private Boolean primaryKey;

    /**
     * Physical type wins when present; ODCS treats it as the warehouse type and
     * logicalType as the portable type.
     */
    public String effectiveType() {
        if (physicalType != null && !physicalType.isBlank()) {
            return physicalType;
        }
        return logicalType;
    }
}
