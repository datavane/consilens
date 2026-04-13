package com.consilens.connector.sqlserver;

import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.conncetor.base.BaseCapabilityProvider;

import java.util.EnumSet;
import java.util.Set;

/**
 * SQL Server capability provider.
 */
public class SQLServerCapabilityProvider extends BaseCapabilityProvider {

    private static final Set<DatabaseFeature> SQLSERVER_FEATURES = EnumSet.of(
            DatabaseFeature.WINDOW_FUNCTIONS,
            DatabaseFeature.UNIQUE_CONSTRAINTS,
            DatabaseFeature.JSON_FUNCTIONS,
            DatabaseFeature.STORED_PROCEDURES,
            DatabaseFeature.TRANSACTIONS,
            DatabaseFeature.SAVEPOINTS,
            DatabaseFeature.CHECK_CONSTRAINTS,
            DatabaseFeature.CTE,
            DatabaseFeature.FULL_OUTER_JOIN);

    @Override
    public String getOpenQuote() {
        return "[";
    }

    @Override
    public String getCloseQuote() {
        return "]";
    }

    @Override
    public boolean supportsFeature(DatabaseFeature feature) {
        return SQLSERVER_FEATURES.contains(feature);
    }

    @Override
    public Set<DatabaseFeature> getSupportedFeatures() {
        return EnumSet.copyOf(SQLSERVER_FEATURES);
    }

    @Override
    public String getDefaultSchema() {
        return "dbo";
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
