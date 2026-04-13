package com.consilens.connector.trino;

import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.conncetor.base.BaseCapabilityProvider;

import java.util.EnumSet;
import java.util.Set;

/**
 * Trino capability provider.
 * Trino is a distributed SQL query engine for big data.
 */
public class TrinoCapabilityProvider extends BaseCapabilityProvider {

    private static final Set<DatabaseFeature> TRINO_FEATURES = EnumSet.of(
            DatabaseFeature.WINDOW_FUNCTIONS,
            DatabaseFeature.JSON_FUNCTIONS,
            DatabaseFeature.CTE,
            DatabaseFeature.ARRAY_FUNCTIONS,
            DatabaseFeature.FULL_OUTER_JOIN,
            DatabaseFeature.NATIVE_PARALLELIZATION);

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
        return TRINO_FEATURES.contains(feature);
    }

    @Override
    public Set<DatabaseFeature> getSupportedFeatures() {
        return EnumSet.copyOf(TRINO_FEATURES);
    }

    @Override
    public String getDefaultSchema() {
        return "default";
    }

    @Override
    public char getWildcardEscapeChar() {
        return '\\';
    }

    @Override
    public String getPaginationHint(long offset, long limit) {
        return "";
    }
}
