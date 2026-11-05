package com.consilens.connector.api;



import com.consilens.connector.api.enums.DatabaseFeature;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Interface for database capability and feature detection.
 * 
 * <p>
 * This interface provides methods for querying database capabilities,
 * supported features, and configuration information.
 * It is a standalone component obtained from
 * {@link DatabaseDialect#getCapabilityProvider()}.
 * 
 * <p>
 * <b>Design Note:</b> This interface is part of the dialect system refactoring
 * to separate capability detection from other database operations.
 * 
 * @since 1.1.0
 * @see DatabaseDialect
 */
public interface CapabilityProvider {

    /**
     * Check if the database supports a specific SQL feature.
     * 
     * @param feature the feature to check
     * @return true if the feature is supported
     */
    boolean supportsFeature(DatabaseFeature feature);

    /**
     * Get set of supported features.
     * 
     * @return immutable set of supported database features
     */
    Set<DatabaseFeature> getSupportedFeatures();

    /**
     * Get the opening quote character for identifiers.
     *
     * @return opening quote character (e.g., "`" for MySQL, "\"" for PostgreSQL)
     */
    String getOpenQuote();

    /**
     * Get the closing quote character for identifiers.
     *
     * @return closing quote character
     */
    String getCloseQuote();

    default String quote(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return "";
        }
        String open = getOpenQuote();
        String close = getCloseQuote();
        // 标识符内出现闭合引号时必须转义（"→"" / `→`` / ]→]]），
        // 否则可以提前闭合标识符并注入 SQL。
        String escaped = close == null || close.isEmpty()
                ? identifier : identifier.replace(close, close + close);
        return open + escaped + close;
    }

    /**
     * Quote multiple identifiers and join with dots.
     * Useful for qualified names like schema.table.column.
     *
     * @param identifiers list of identifiers to quote and join
     * @return quoted and joined identifiers
     */
    default String quote(List<String> identifiers) {
        return identifiers.stream()
                .map(this::quote)
                .collect(Collectors.joining("."));
    }
}
