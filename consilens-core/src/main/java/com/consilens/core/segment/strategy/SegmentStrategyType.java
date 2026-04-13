package com.consilens.core.segment.strategy;

public enum SegmentStrategyType {
    AUTO,
    RANGE,
    ROW_SAMPLE,
    FALLBACK;

    public static SegmentStrategyType fromString(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        switch (value.trim().toUpperCase()) {
            case "RANGE":
                return RANGE;
            case "ROW_SAMPLE":
            case "ROWSAMPLE":
            case "ROW":
                return ROW_SAMPLE;
            case "FALLBACK":
                return FALLBACK;
            default:
                return AUTO;
        }
    }
}
