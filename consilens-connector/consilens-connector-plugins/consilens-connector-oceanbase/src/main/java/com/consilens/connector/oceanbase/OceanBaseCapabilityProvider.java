package com.consilens.connector.oceanbase;

import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.conncetor.base.BaseCapabilityProvider;

import java.util.EnumSet;
import java.util.Set;

/**
 * OceanBase capability provider.
 *
 * <p>
 * Defines OceanBase-specific features and configuration:
 * <ul>
 * <li>Identifier quotes: backticks ` (MySQL mode)</li>
 * <li>Supported SQL features</li>
 * <li>Default schema</li>
 * </ul>
 *
 * <p>
 * OceanBase in MySQL mode is highly compatible with MySQL, but also supports
 * some additional features like FULL OUTER JOIN and CTE that MySQL lacks.
 *
 * @since 1.0.0
 */
public class OceanBaseCapabilityProvider extends BaseCapabilityProvider {

    private static final Set<DatabaseFeature> OCEANBASE_FEATURES = EnumSet.of(
            DatabaseFeature.WINDOW_FUNCTIONS,
            DatabaseFeature.UNIQUE_CONSTRAINTS,
            DatabaseFeature.JSON_FUNCTIONS,
            DatabaseFeature.FULL_OUTER_JOIN,
            DatabaseFeature.CTE,
            DatabaseFeature.STORED_PROCEDURES,
            DatabaseFeature.TRANSACTIONS,
            DatabaseFeature.SAVEPOINTS,
            DatabaseFeature.CHECK_CONSTRAINTS,
            DatabaseFeature.PARTITIONING,
            DatabaseFeature.ADVANCED_INDEXING,
            DatabaseFeature.MATERIALIZED_VIEWS,
            DatabaseFeature.RECURSIVE_QUERIES
    );

    @Override
    public String getOpenQuote() {
        return "`";
    }

    @Override
    public String getCloseQuote() {
        return "`";
    }

    @Override
    public boolean supportsFeature(DatabaseFeature feature) {
        return OCEANBASE_FEATURES.contains(feature);
    }

    @Override
    public Set<DatabaseFeature> getSupportedFeatures() {
        return EnumSet.copyOf(OCEANBASE_FEATURES);
    }
}
