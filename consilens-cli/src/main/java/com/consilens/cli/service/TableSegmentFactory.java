package com.consilens.cli.service;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.segment.TableSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Factory for creating TableSegment instances from CLI configuration.
 */
@Slf4j
public class TableSegmentFactory {

    /**
     * Create a TableSegment for table 1 from CLI configuration.
     */
    public static TableSegment createSourceTableSegment(CliConfiguration config, DatabaseAdapter databaseAdapter) {
        return createTableSegment(
                config.getComparison().getTables().getSource(),
                config.getComparison().getKeys().getSource(),
                config.getComparison().getCompareColumns() != null ? config.getComparison().getCompareColumns().getSource() : null,
                databaseAdapter,
                config.getComparison().getWhere() != null ? config.getComparison().getWhere().getSource() : null);
    }

    /**
     * Create a TableSegment for table 2 from CLI configuration.
     */
    public static TableSegment createTargetTableSegment(CliConfiguration config, DatabaseAdapter databaseAdapter) {
        return createTableSegment(
                config.getComparison().getTables().getTarget(),
                config.getComparison().getKeys().getTarget(),
                config.getComparison().getCompareColumns() != null ? config.getComparison().getCompareColumns().getTarget() : null,
                databaseAdapter,
                config.getComparison().getWhere() != null ? config.getComparison().getWhere().getTarget() : null);
    }

    /**
     * Create a TableSegment from configuration parameters.
     */
    private static TableSegment createTableSegment(
            String tableName,
            List<String> keyColumns,
            List<String> compareColumns,
            DatabaseAdapter databaseAdapter,
            String whereClause) {

        log.info("Creating table segment for table: {}", tableName);

        TablePath tablePath = createTablePath(tableName);

        TableSegment.TableSegmentBuilder builder = TableSegment.builder()
                .database(databaseAdapter)
                .tablePath(tablePath)
                .keyColumns(new ArrayList<>(keyColumns))
                .caseSensitive(true);

        // Fetch schema first — needed for both explicit and auto-detected compare columns
        Optional<TableSchema> schemaOpt = Optional.empty();
        try {
            log.debug("Fetching table schema for: {}", tablePath);
            TableSchema tableSchema = databaseAdapter.getTableSchema(tablePath.getComponents());
            schemaOpt = Optional.of(tableSchema);
            builder.schema(schemaOpt);
            log.debug("Table schema successfully fetched for: {}", tablePath);
        } catch (Exception e) {
            log.warn("Failed to fetch table schema for {}: {}. Continuing without schema.",
                    tablePath, e.getMessage());
            builder.schema(Optional.empty());
        }

        // Determine extra columns (non-key comparison columns)
        boolean compareColumnsSpecified = compareColumns != null && !compareColumns.isEmpty();
        List<String> extraColumns;

        if (compareColumnsSpecified) {
            // Use explicitly configured compare columns, minus key columns to avoid duplication
            extraColumns = new ArrayList<>(compareColumns);
            extraColumns.removeAll(keyColumns);
            log.debug("Using configured compareColumns for {}: {}", tableName, extraColumns);
        } else if (schemaOpt.isPresent()) {
            // Auto-detect: sort all schema columns by ordinal position, exclude key columns
            extraColumns = schemaOpt.get().getColumns().values().stream()
                    .sorted(Comparator.comparingInt(ColumnInfo::getOrdinalPosition))
                    .map(ColumnInfo::getName)
                    .filter(col -> !keyColumns.contains(col))
                    .collect(Collectors.toList());
            log.info("compareColumns not specified for table '{}', auto-detected {} columns by ordinal: {}",
                    tableName, extraColumns.size(), extraColumns);
        } else {
            // Schema unavailable and no compareColumns configured — compare keys only
            extraColumns = new ArrayList<>();
            log.warn("compareColumns not specified for table '{}' and schema unavailable; only key columns will be compared.",
                    tableName);
        }

        if (!extraColumns.isEmpty()) {
            builder.extraColumns(extraColumns);
        }

        builder.updateColumn(Optional.empty());

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            log.info("Applying WHERE clause: {}", whereClause);
            builder.whereClause(Optional.of(whereClause));
        } else {
            builder.whereClause(Optional.empty());
        }

        return builder.build();
    }

    /**
     * Create a TablePath from a table name string.
     */
    private static TablePath createTablePath(String tableName) {
        String[] parts = tableName.split("\\.");
        List<String> pathParts = new ArrayList<>();

        if (parts.length == 1) {
            pathParts.add(parts[0]);
        } else if (parts.length == 2) {
            pathParts.add(parts[0]);
            pathParts.add(parts[1]);
        } else {
            for (int i = 0; i < parts.length - 1; i++) {
                pathParts.add(parts[i]);
            }
            pathParts.add(parts[parts.length - 1]);
        }

        return TablePath.of(pathParts);
    }

    /**
     * Create a table segment with additional constraints for performance.
     */
    public static TableSegment createOptimizedTableSegment(
            String tableName,
            List<String> keyColumns,
            List<String> compareColumns,
            DatabaseAdapter databaseAdapter,
            Optional<String> whereClause,
            int batchSize) {

        return createTableSegment(
                tableName,
                keyColumns,
                compareColumns,
                databaseAdapter,
                whereClause.orElse(null));
    }

    /**
     * Validate that the table segment configuration is compatible with the
     * algorithm requirements.
     */
    public static void validateTableSegmentConfiguration(TableSegment segment, String strategy) {
        if (segment.getKeyColumns() == null || segment.getKeyColumns().isEmpty()) {
            throw new IllegalArgumentException("Key columns are required for diff operations");
        }

        if ("checksum".equalsIgnoreCase(strategy)) {
            if (segment.getKeyColumns().isEmpty()) {
                throw new IllegalArgumentException("checksum strategy requires at least one key column");
            }
        } else if ("join".equalsIgnoreCase(strategy)) {
            if (segment.getKeyColumns().isEmpty()) {
                throw new IllegalArgumentException("join strategy requires key columns for joining");
            }
        }

        DatabaseAdapter adapter = segment.getDatabase();
        if (adapter == null) {
            throw new IllegalArgumentException("Database adapter is required for table segment");
        }

        log.debug("Table segment validation passed for {} with strategy {}",
                segment.getTablePath(), strategy);
    }
}
