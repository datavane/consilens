package com.consilens.common.enums;

import lombok.Getter;

/**
 * Comparison strategy enum.
 */
@Getter
public enum ComparisonStrategy {
    CHECKSUM("checksum", "Recursive segment comparison"),
    JOIN("join", "Full-table JOIN comparison");

    private final String code;
    private final String description;

    ComparisonStrategy(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static ComparisonStrategy fromString(String code) {
        if (code == null || code.trim().isEmpty()) {
            return CHECKSUM; // default
        }
        
        for (ComparisonStrategy strategy : values()) {
            if (strategy.code.equalsIgnoreCase(code)) {
                return strategy;
            }
        }
        
        throw new IllegalArgumentException(
            "Unknown comparison strategy: " + code + 
            ". Valid values: checksum, join, local");
    }
}
