package com.consilens.core.segmentation;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of table sampling operation for distribution analysis.
 * Used to inform intelligent checkpoint selection.
 */
@Data
@Builder
public class SamplingResult {

    /**
     * Sampled key values.
     */
    private List<KeyVector> sampledKeys;

    /**
     * Total number of rows in the table segment.
     */
    private long totalRows;

    /**
     * Number of rows sampled.
     */
    private int sampleSize;

    /**
     * Distribution of keys across ranges.
     */
    private Map<String, Integer> distribution;

    /**
     * Minimum key value found in sample.
     */
    private KeyVector minKey;

    /**
     * Maximum key value found in sample.
     */
    private KeyVector maxKey;

    /**
     * Check if sampling provides useful distribution information.
     */
    public boolean hasDistributionInfo() {
        return sampledKeys != null && !sampledKeys.isEmpty() && distribution != null;
    }

    /**
     * Get estimated density of data in the given range.
     */
    public double getDensityInRange(KeyVector start, KeyVector end) {
        if (!hasDistributionInfo()) {
            return 1.0; // Default uniform density
        }

        // Count keys in the range
        int keysInRange = 0;
        for (KeyVector key : sampledKeys) {
            if (key.isGreaterThanOrEqual(start) && key.isLessThan(end)) {
                keysInRange++;
            }
        }

        // Calculate density
        double samplingRatio = (double) sampleSize / totalRows;
        return (keysInRange / samplingRatio) / Math.max(1, sampledKeys.size());
    }

    /**
     * Get hotspots (ranges with high data density).
     */
    public Map<String, Double> getHotspots(double densityThreshold) {
        Map<String, Double> hotspots = new HashMap<>();

        if (!hasDistributionInfo()) {
            return hotspots;
        }

        // Simple implementation - identify ranges with density above threshold
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            double density = entry.getValue() / (double) sampleSize;
            if (density > densityThreshold) {
                hotspots.put(entry.getKey(), density);
            }
        }

        return hotspots;
    }

    /**
     * Check if the data distribution is skewed.
     */
    public boolean isSkewed(double skewnessThreshold) {
        if (!hasDistributionInfo() || distribution.size() < 2) {
            return false;
        }

        // Calculate coefficient of variation
        double mean = (double) sampleSize / distribution.size();
        double sumSquaredDiff = 0.0;

        for (int count : distribution.values()) {
            double diff = count - mean;
            sumSquaredDiff += diff * diff;
        }

        double variance = sumSquaredDiff / distribution.size();
        double stdDev = Math.sqrt(variance);
        double coefficientOfVariation = stdDev / mean;

        return coefficientOfVariation > skewnessThreshold;
    }

    /**
     * Create empty sampling result.
     */
    public static SamplingResult empty() {
        return SamplingResult.builder()
                .sampledKeys(List.of())
                .totalRows(0)
                .sampleSize(0)
                .distribution(new HashMap<>())
                .build();
    }

    /**
     * Create sampling result from basic information.
     */
    public static SamplingResult fromKeys(List<KeyVector> keys, long totalRows) {
        Map<String, Integer> distribution = new HashMap<>();
        KeyVector minKey = null;
        KeyVector maxKey = null;

        for (KeyVector key : keys) {
            if (minKey == null || key.isLessThan(minKey)) {
                minKey = key;
            }
            if (maxKey == null || key.isGreaterThan(maxKey)) {
                maxKey = key;
            }

            // Simple distribution analysis - bucket by first key dimension
            String bucket = "bucket_" + key.getValue(0);
            distribution.merge(bucket, 1, Integer::sum);
        }

        return SamplingResult.builder()
                .sampledKeys(keys)
                .totalRows(totalRows)
                .sampleSize(keys.size())
                .distribution(distribution)
                .minKey(minKey)
                .maxKey(maxKey)
                .build();
    }
}