package com.consilens.core.diff;

import lombok.Getter;

/**
 * Enumeration representing different types of diff operations.
 */
@Getter
public enum DiffOperation {

    /**
     * Data mismatch - row exists in both tables but with different values.
     */
    MISMATCH("mismatch", "MISMATCH", "数据不一致"),

    /**
     * Source missing - row exists only in target table (missing in source).
     */
    SOURCE_MISSING("source_missing", "SOURCE_MISSING", "源端缺失"),

    /**
     * Target missing - row exists only in source table (missing in target).
     */
    TARGET_MISSING("target_missing", "TARGET_MISSING", "目标缺失");

    /**
     * -- GETTER --
     *  Get the code representation of this operation.
     */
    private final String code;
    
    /**
     * -- GETTER --
     *  Get the display name of this operation.
     */
    private final String displayName;
    
    /**
     * -- GETTER --
     *  Get the description of this operation.
     */
    private final String description;

    DiffOperation(String code, String displayName, String description) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * Get the operation from its code.
     */
    public static DiffOperation fromCode(String code) {
        for (DiffOperation operation : values()) {
            if (operation.code.equals(code)) {
                return operation;
            }
        }
        throw new IllegalArgumentException("Unknown diff operation code: " + code);
    }

    /**
     * Get the operation from its display name.
     */
    public static DiffOperation fromDisplayName(String displayName) {
        for (DiffOperation operation : values()) {
            if (operation.displayName.equals(displayName)) {
                return operation;
            }
        }
        throw new IllegalArgumentException("Unknown diff operation display name: " + displayName);
    }

    @Override
    public String toString() {
        return displayName;
    }
}