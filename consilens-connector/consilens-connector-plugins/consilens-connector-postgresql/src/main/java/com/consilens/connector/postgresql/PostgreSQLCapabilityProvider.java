package com.consilens.connector.postgresql;

import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.conncetor.base.BaseCapabilityProvider;

import java.util.EnumSet;
import java.util.Set;

/**
 * PostgreSQL capability provider.
 * 
 * <p>
 * Defines PostgreSQL-specific features and configuration:
 * <ul>
 * <li>Identifier quotes: double quotes "</li>
 * <li>Supported SQL features</li>
 * <li>Default schema</li>
 * </ul>
 * 
 * @since 1.0.0
 */
public class PostgreSQLCapabilityProvider extends BaseCapabilityProvider {

    private static final Set<DatabaseFeature> POSTGRESQL_FEATURES = EnumSet.of(
            DatabaseFeature.WINDOW_FUNCTIONS,
            DatabaseFeature.UNIQUE_CONSTRAINTS,
            DatabaseFeature.JSON_FUNCTIONS,
            DatabaseFeature.STORED_PROCEDURES,
            DatabaseFeature.TRANSACTIONS,
            DatabaseFeature.SAVEPOINTS,
            DatabaseFeature.CHECK_CONSTRAINTS,
            DatabaseFeature.CTE,
            DatabaseFeature.FULL_OUTER_JOIN, // PostgreSQL DOES support native FULL OUTER JOIN!
            DatabaseFeature.DISTINCT_ON, // PostgreSQL-specific feature
            DatabaseFeature.ARRAY_FUNCTIONS // PostgreSQL array support
    );

    @Override
    public String getOpenQuote() {
        return "\"";
    }

    @Override
    public String getCloseQuote() {
        return "\"";
    }

    @Override
    public boolean supportsFeature(DatabaseFeature feature) {
        return POSTGRESQL_FEATURES.contains(feature);
    }

    @Override
    public Set<DatabaseFeature> getSupportedFeatures() {
        return EnumSet.copyOf(POSTGRESQL_FEATURES);
    }

    @Override
    public String getDefaultSchema() {
        return "public"; // PostgreSQL default schema is "public"
    }

    @Override
    public char getWildcardEscapeChar() {
        return '\\';
    }

    @Override
    public String getPaginationHint(long offset, long limit) {
        return ""; // PostgreSQL doesn't need pagination hints
    }
}
