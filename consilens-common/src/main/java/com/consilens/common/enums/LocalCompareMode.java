package com.consilens.common.enums;

import lombok.Getter;

/**
 * Local comparison mode used after checksum recursion converges to a small segment.
 */
@Getter
public enum LocalCompareMode {
    /**
     * Pull full rows from both sides and compare them locally.
     */
    FULL("full", "Full row comparison"),

    /**
     * Pull primary key plus row hash first, then fetch full rows only for mismatched keys.
     */
    ROW_HASH("row-hash", "Row hash pre-filter comparison");

    private final String code;
    private final String description;

    LocalCompareMode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public boolean isRowHash() {
        return this == ROW_HASH;
    }

    public static LocalCompareMode fromString(String code) {
        if (code == null || code.trim().isEmpty()) {
            return FULL;
        }

        for (LocalCompareMode mode : values()) {
            if (mode.code.equalsIgnoreCase(code.trim())) {
                return mode;
            }
        }

        throw new IllegalArgumentException(
                "Unknown local compare mode: " + code + ". Valid values: full, row-hash");
    }
}
