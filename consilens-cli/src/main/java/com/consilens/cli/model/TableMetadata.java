package com.consilens.cli.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * Metadata about the tables and columns compared in a diff operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableMetadata {

    /**
     * Name of source table.
     */
    private String sourceTable;

    /**
     * Name of target table.
     */
    private String targetTable;

    /**
     * Column names for source table.
     */
    private List<String> sourceColumns;

    /**
     * Column names for target table.
     */
    private List<String> targetColumns;
}
