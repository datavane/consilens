package com.consilens.core.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-column numeric tolerance resolved from the normalization spec.
 * A value pair is equal when the absolute difference is within the absolute
 * tolerance, or the relative difference is within the relative tolerance.
 * Only floating-point / decimal columns honor tolerance; integer columns
 * always compare exactly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnTolerance {

    private Double absolute;
    private Double relative;

    public boolean isEqual(double left, double right) {
        double diff = Math.abs(left - right);
        if (absolute != null && diff <= absolute) {
            return true;
        }
        if (relative != null) {
            double magnitude = Math.max(Math.abs(left), Math.abs(right));
            if (magnitude == 0.0) {
                return diff == 0.0;
            }
            return diff / magnitude <= relative;
        }
        return false;
    }
}
