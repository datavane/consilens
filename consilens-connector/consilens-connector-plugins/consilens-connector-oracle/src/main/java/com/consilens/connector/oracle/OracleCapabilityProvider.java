package com.consilens.connector.oracle;

import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.conncetor.base.BaseCapabilityProvider;

import java.util.EnumSet;
import java.util.Set;

/**
 * Oracle capability provider.
 */
public class OracleCapabilityProvider extends BaseCapabilityProvider {

    private static final Set<DatabaseFeature> ORACLE_FEATURES = EnumSet.of(
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
        return "\"";
    }

    @Override
    public String getCloseQuote() {
        return "\"";
    }

    /**
     * Oracle folds unquoted identifiers to upper-case. Only all-lower-case names in
     * Oracle's unquoted-identifier form ({@code [a-z][a-z0-9_$#]*}) are folded and
     * quoted upper-case so they resolve exactly like the unquoted name.
     *
     * <p>Any other identifier is quoted exactly as given: mixed-case names exist only
     * when the object was created quoted, and folding them would make those genuinely
     * case-sensitive (quoted, mixed-case) objects unreachable (e.g. a table created
     * as "MyTable" is not "MYTABLE").
     *
     * <p>Escape hatch for an all-lower-case object that was created quoted
     * ({@code create table "users"}): configure the name already wrapped in double
     * quotes ({@code "users"}) and it is quoted exactly, without folding.
     */
    @Override
    public String quote(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return "";
        }
        if (isAlreadyQuoted(identifier)) {
            // 不能原样透传：内部引号必须先反转义再转义，否则可提前闭合标识符注入 SQL。
            String inner = identifier.substring(1, identifier.length() - 1)
                    .replace("\"\"", "\"");
            return getOpenQuote() + inner.replace("\"", "\"\"") + getCloseQuote();
        }
        if (identifier.matches("[a-z][a-z0-9_$#]*")) {
            return getOpenQuote() + identifier.toUpperCase(java.util.Locale.ROOT) + getCloseQuote();
        }
        return getOpenQuote() + identifier.replace("\"", "\"\"") + getCloseQuote();
    }

    private boolean isAlreadyQuoted(String identifier) {
        return identifier.length() >= 3
                && identifier.startsWith(getOpenQuote())
                && identifier.endsWith(getCloseQuote());
    }

    @Override
    public boolean supportsFeature(DatabaseFeature feature) {
        return ORACLE_FEATURES.contains(feature);
    }

    @Override
    public Set<DatabaseFeature> getSupportedFeatures() {
        return EnumSet.copyOf(ORACLE_FEATURES);
    }

}
