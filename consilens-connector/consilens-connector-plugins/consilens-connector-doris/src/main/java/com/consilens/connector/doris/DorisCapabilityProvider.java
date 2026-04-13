package com.consilens.connector.doris;

import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.conncetor.base.BaseCapabilityProvider;

import java.util.EnumSet;
import java.util.Set;

/**
 * Doris capability provider.
 * 
 * <p>
 * Defines Doris-specific features and configuration:
 * <ul>
 * <li>Identifier quotes: backticks `</li>
 * <li>Supported SQL features</li>
 * <li>Default schema</li>
 * </ul>
 * 
 * @since 1.0.0
 */
public class DorisCapabilityProvider extends BaseCapabilityProvider {

    private static final Set<DatabaseFeature> DORIS_FEATURES = EnumSet.of(
            DatabaseFeature.WINDOW_FUNCTIONS,
            DatabaseFeature.UNIQUE_CONSTRAINTS,
            DatabaseFeature.JSON_FUNCTIONS,
            DatabaseFeature.STORED_PROCEDURES,
            DatabaseFeature.TRANSACTIONS,
            DatabaseFeature.SAVEPOINTS,
            DatabaseFeature.CHECK_CONSTRAINTS
    // Note: Doris does not natively support FULL_OUTER_JOIN
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
        return DORIS_FEATURES.contains(feature);
    }

    @Override
    public Set<DatabaseFeature> getSupportedFeatures() {
        return EnumSet.copyOf(DORIS_FEATURES);
    }

    @Override
    public String getDefaultSchema() {
        return "doris";
    }

    @Override
    public char getWildcardEscapeChar() {
        return '\\';
    }

    @Override
    public String getPaginationHint(long offset, long limit) {
        return ""; // Doris doesn't need pagination hints
    }
}
