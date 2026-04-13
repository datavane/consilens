package com.consilens.connector.presto;

import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.conncetor.base.BaseCapabilityProvider;

import java.util.EnumSet;
import java.util.Set;

/**
 * Presto capability provider.
 * Presto is a distributed SQL query engine for big data.
 */
public class PrestoCapabilityProvider extends BaseCapabilityProvider {

    private static final Set<DatabaseFeature> PRESTO_FEATURES = EnumSet.of(
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
        return PRESTO_FEATURES.contains(feature);
    }

    @Override
    public Set<DatabaseFeature> getSupportedFeatures() {
        return EnumSet.copyOf(PRESTO_FEATURES);
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
