package com.consilens.connector.clickhouse;

import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.conncetor.base.BaseCapabilityProvider;

import java.util.EnumSet;
import java.util.Set;

/**
 * ClickHouse capability provider.
 * 
 * <p>
 * Defines ClickHouse-specific features and configuration:
 * <ul>
 * <li>Identifier quotes: double quotes "</li>
 * <li>Supported SQL features</li>
 * <li>Default schema</li>
 * </ul>
 * 
 * @since 1.0.0
 */
public class ClickHouseCapabilityProvider extends BaseCapabilityProvider {

    private static final Set<DatabaseFeature> CLICKHOUSE_FEATURES = EnumSet.of(
            DatabaseFeature.WINDOW_FUNCTIONS,
            DatabaseFeature.UNIQUE_CONSTRAINTS,
            DatabaseFeature.JSON_FUNCTIONS,
            DatabaseFeature.STORED_PROCEDURES,
            DatabaseFeature.TRANSACTIONS,
            DatabaseFeature.SAVEPOINTS,
            DatabaseFeature.CHECK_CONSTRAINTS,
            DatabaseFeature.CTE,
            DatabaseFeature.FULL_OUTER_JOIN, // ClickHouse DOES support native FULL OUTER JOIN!
            DatabaseFeature.DISTINCT_ON, // ClickHouse-specific feature
            DatabaseFeature.ARRAY_FUNCTIONS // ClickHouse array support
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
        return CLICKHOUSE_FEATURES.contains(feature);
    }

    @Override
    public Set<DatabaseFeature> getSupportedFeatures() {
        return EnumSet.copyOf(CLICKHOUSE_FEATURES);
    }

    @Override
    public String getDefaultSchema() {
        return "public"; // ClickHouse default schema is "public"
    }

    @Override
    public char getWildcardEscapeChar() {
        return '\\';
    }

    @Override
    public String getPaginationHint(long offset, long limit) {
        return ""; // ClickHouse doesn't need pagination hints
    }
}
