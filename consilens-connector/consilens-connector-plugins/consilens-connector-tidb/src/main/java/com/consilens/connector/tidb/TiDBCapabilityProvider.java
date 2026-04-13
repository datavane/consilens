package com.consilens.connector.tidb;

import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.conncetor.base.BaseCapabilityProvider;

import java.util.EnumSet;
import java.util.Set;

/**
 * TiDB capability provider.
 * 
 * <p>
 * Defines TiDB-specific features and configuration:
 * <ul>
 * <li>Identifier quotes: backticks `</li>
 * <li>Supported SQL features</li>
 * <li>Default schema</li>
 * </ul>
 * 
 * @since 1.0.0
 */
public class TiDBCapabilityProvider extends BaseCapabilityProvider {

    private static final Set<DatabaseFeature> TIDB_FEATURES = EnumSet.of(
            DatabaseFeature.WINDOW_FUNCTIONS,
            DatabaseFeature.UNIQUE_CONSTRAINTS,
            DatabaseFeature.JSON_FUNCTIONS,
            DatabaseFeature.TRANSACTIONS,
            DatabaseFeature.CTE,
            DatabaseFeature.CHECK_CONSTRAINTS
    // Note: TiDB does not natively support FULL_OUTER_JOIN, STORED_PROCEDURES,
    // SAVEPOINTS
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
        return TIDB_FEATURES.contains(feature);
    }

    @Override
    public Set<DatabaseFeature> getSupportedFeatures() {
        return EnumSet.copyOf(TIDB_FEATURES);
    }

    @Override
    public String getDefaultSchema() {
        return "test"; // TiDB default schema is often 'test'
    }

    @Override
    public char getWildcardEscapeChar() {
        return '\\';
    }

    @Override
    public String getPaginationHint(long offset, long limit) {
        return ""; // TiDB uses LIMIT offset, limit syntax, no hint needed
    }
}
