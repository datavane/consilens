package com.consilens.cli.model.normalization;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Normalization configuration
 * Supports global and database-level configuration
 * Note: Column-level configuration is not supported in current version
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizationConfig {
    
    /**
     * Global configuration (applies to all databases)
     * key: Data type name (decimal, float, date, etc.)
     * value: Normalization rule
     */
    @JsonProperty("global")
    private Map<String, TypeNormalizationRule> global;
    
    /**
     * Source specific configuration (overrides global)
     */
    @JsonProperty("source")
    private Map<String, TypeNormalizationRule> source;
    
    /**
     * Target specific configuration (overrides global)
     */
    @JsonProperty("target")
    private Map<String, TypeNormalizationRule> target;
    
    /**
     * Validate configuration validity
     */
    public void validate() {
        validatePrecision("global", global);
        validatePrecision("source", source);
        validatePrecision("target", target);
    }
    
    private void validatePrecision(String configName, Map<String, TypeNormalizationRule> rules) {
        if (rules == null) {
            return;
        }
        
        for (Map.Entry<String, TypeNormalizationRule> entry : rules.entrySet()) {
            TypeNormalizationRule rule = entry.getValue();
            if (rule.getPrecision() != null && (rule.getPrecision() < 0 || rule.getPrecision() > 10)) {
                throw new IllegalArgumentException(
                    "Invalid precision in " + configName + " for type '" + entry.getKey() + "': " + 
                    rule.getPrecision() + " (must be between 0 and 10)");
            }
        }
    }
    
    /**
     * Get configuration rule for specified data type and column name
     * Priority: database-specific > global
     * Note: Column-level configuration is not supported in current version
     * 
     * @param databaseId Database ID ("source" or "target")
     * @param dataType Data type name
     * @return Configuration rule, or null if not configured
     */
    public TypeNormalizationRule getRule(String databaseId, String dataType) {
        // 1. Check database-specific configuration
        Map<String, TypeNormalizationRule> dbRules = "source".equals(databaseId) ? source : target;
        if (dbRules != null && dbRules.containsKey(dataType)) {
            TypeNormalizationRule rule = dbRules.get(dataType);
            if (rule != null) {
                return rule;
            }
        }
        
        // 2. Check global configuration
        if (global != null && global.containsKey(dataType)) {
            return global.get(dataType);
        }
        
        // 3. No configuration found
        return null;
    }
}
