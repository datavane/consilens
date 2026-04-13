package com.consilens.common.enums;

import lombok.Getter;

/**
 * Checksum algorithm enum.
 *
 * <ul>
 *   <li>CONCAT: Concatenate all column values and compute MD5, suitable for small datasets.</li>
 *   <li>XOR: XOR-based aggregation, better performance but may have collisions.</li>
 * </ul>
 */
@Getter
public enum ChecksumAlgorithm {
    /**
     * Concatenate column values and compute MD5. Suitable for small datasets.
     */
    CONCAT("concat", "Concat MD5"),
    
    /**
     * XOR-based row aggregation, good performance.
     */
    XOR("xor", "XOR aggregation");

    private final String code;
    private final String description;

    ChecksumAlgorithm(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns true if this is the XOR algorithm.
     */
    public boolean isXor() {
        return this == XOR;
    }

    /**
     * Converts a string code to the corresponding enum value.
     *
     * @param code algorithm code (concat/xor)
     * @return corresponding enum value
     * @throws IllegalArgumentException if the code is invalid
     */
    public static ChecksumAlgorithm fromString(String code) {
        if (code == null || code.trim().isEmpty()) {
            return CONCAT; // default
        }
        
        for (ChecksumAlgorithm algorithm : values()) {
            if (algorithm.code.equalsIgnoreCase(code)) {
                return algorithm;
            }
        }
        
        throw new IllegalArgumentException(
            "Unknown checksum algorithm: " + code + 
            ". Valid values: concat, xor");
    }
}
