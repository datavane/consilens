package com.consilens.connector.mysql;

import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.conncetor.base.BaseCapabilityProvider;

import java.util.EnumSet;
import java.util.Set;

/**
 * MySQL capability provider.
 * 
 * <p>
 * Defines MySQL-specific features and configuration:
 * <ul>
 * <li>Identifier quotes: backticks `</li>
 * <li>Supported SQL features</li>
 * <li>Default schema</li>
 * </ul>
 * 
 * @since 1.0.0
 */
public class MySQLCapabilityProvider extends BaseCapabilityProvider {

    private static final Set<DatabaseFeature> MYSQL_FEATURES = EnumSet.of(
            DatabaseFeature.WINDOW_FUNCTIONS,
            DatabaseFeature.UNIQUE_CONSTRAINTS,
            DatabaseFeature.JSON_FUNCTIONS,
            DatabaseFeature.STORED_PROCEDURES,
            DatabaseFeature.TRANSACTIONS,
            DatabaseFeature.SAVEPOINTS,
            DatabaseFeature.CHECK_CONSTRAINTS
    // Note: MySQL does not natively support FULL_OUTER_JOIN
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
        return MYSQL_FEATURES.contains(feature);
    }

    @Override
    public Set<DatabaseFeature> getSupportedFeatures() {
        return EnumSet.copyOf(MYSQL_FEATURES);
    }

    @Override
    public String getDefaultSchema() {
        return "mysql";
    }

    @Override
    public String getPaginationHint(long offset, long limit) {
        return ""; // MySQL doesn't need pagination hints
    }
}
