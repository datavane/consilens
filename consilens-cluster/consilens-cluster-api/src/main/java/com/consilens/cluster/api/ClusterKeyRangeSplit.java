package com.consilens.cluster.api;

import com.consilens.connector.api.planner.KeyRangeSplit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * Portable single-column numeric half-open key-range split.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterKeyRangeSplit implements Serializable {

    private static final long serialVersionUID = 1L;

    private String splitId;

    private BigDecimal startKey;

    private BigDecimal endKey;

    public static ClusterKeyRangeSplit from(String splitId, KeyRangeSplit split) {
        if (split == null) {
            throw new IllegalArgumentException("split is required for cluster submission");
        }
        BigDecimal startKey = encodeStartKey(split.getStartKey());
        BigDecimal endKey = encodeEndKey(split.getEndKey());
        validateRange(startKey, endKey);
        return ClusterKeyRangeSplit.builder()
                .splitId(splitId)
                .startKey(startKey)
                .endKey(endKey)
                .build();
    }

    public KeyRangeSplit toKeyRangeSplit() {
        validateRange(startKey, endKey);
        return KeyRangeSplit.builder()
                .startKey(List.of(startKey))
                .endKey(endKey == null ? null : List.of(endKey))
                .build();
    }

    private static BigDecimal encodeStartKey(List<Object> boundaries) {
        if (boundaries == null || boundaries.isEmpty()) {
            throw new IllegalArgumentException("startKey must contain one numeric key");
        }
        return encodeBoundary(boundaries, "startKey");
    }

    private static BigDecimal encodeEndKey(List<Object> boundaries) {
        if (boundaries == null) {
            return null;
        }
        if (boundaries.isEmpty()) {
            throw new IllegalArgumentException("endKey must contain one numeric key or be null");
        }
        return encodeBoundary(boundaries, "endKey");
    }

    private static BigDecimal encodeBoundary(List<Object> boundaries, String keyName) {
        if (boundaries.size() != 1) {
            throw new IllegalArgumentException(keyName + " must contain exactly one key");
        }
        Object boundary = boundaries.get(0);
        if (!(boundary instanceof Number)) {
            throw new IllegalArgumentException(keyName + " must be numeric");
        }
        if (boundary instanceof BigDecimal) {
            return (BigDecimal) boundary;
        }
        return new BigDecimal(boundary.toString());
    }

    private static void validateRange(BigDecimal startKey, BigDecimal endKey) {
        if (startKey == null) {
            throw new IllegalArgumentException("startKey is required for cluster key range split");
        }
        if (endKey != null && endKey.compareTo(startKey) <= 0) {
            throw new IllegalArgumentException("endKey must be greater than startKey");
        }
    }
}
