package com.consilens.cli.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, normalized view of an ODCS data contract. Only schema-level
 * information needed for comparison is kept.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataContract {

    private String id;
    private String name;
    private String version;
    private List<ContractColumn> columns;

    public Map<String, ContractColumn> columnMap() {
        Map<String, ContractColumn> result = new LinkedHashMap<>();
        if (columns != null) {
            for (ContractColumn column : columns) {
                if (column != null && column.getName() != null) {
                    result.putIfAbsent(column.getName(), column);
                }
            }
        }
        return result;
    }
}
