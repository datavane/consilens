package com.consilens.core.segmentation;

import lombok.Builder;
import lombok.Data;

/**
 * Statistics about table segmentation results.
 */
@Data
@Builder
public class SegmentStatistics {

    /**
     * Number of segments created.
     */
    private int segmentCount;

    /**
     * Total estimated rows across all segments.
     */
    private long totalEstimatedRows;

    /**
     * Average segment size (rows).
     */
    private double averageSegmentSize;

    /**
     * Maximum segment size (rows).
     */
    private long maxSegmentSize;

    /**
     * Minimum segment size (rows).
     */
    private long minSegmentSize;

    /**
     * Calculate segmentation quality score (0-1, higher is better).
     */
    public double getQualityScore() {
        if (segmentCount <= 1 || totalEstimatedRows == 0) {
            return 1.0;
        }

        // Calculate coefficient of variation (lower is better)
        double variance = ((maxSegmentSize - averageSegmentSize) * (maxSegmentSize - averageSegmentSize) +
                          (minSegmentSize - averageSegmentSize) * (minSegmentSize - averageSegmentSize)) / 2.0;
        double stdDev = Math.sqrt(variance);
        double coefficientOfVariation = stdDev / averageSegmentSize;

        // Convert to quality score (1 - CV, bounded between 0 and 1)
        return Math.max(0.0, Math.min(1.0, 1.0 - coefficientOfVariation));
    }

    /**
     * Check if segmentation is balanced (uniform segment sizes).
     */
    public boolean isBalanced(double tolerance) {
        return getQualityScore() >= (1.0 - tolerance);
    }

    /**
     * Get size variance.
     */
    public double getSizeVariance() {
        if (segmentCount <= 1) {
            return 0.0;
        }

        double variance = ((maxSegmentSize - averageSegmentSize) * (maxSegmentSize - averageSegmentSize) +
                          (minSegmentSize - averageSegmentSize) * (minSegmentSize - averageSegmentSize)) / 2.0;
        return variance;
    }

    /**
     * Get segmentation efficiency (ideal vs actual segment count).
     */
    public double getEfficiency(int idealSegmentCount) {
        if (idealSegmentCount <= 0) {
            return 1.0;
        }

        double ratio = (double) segmentCount / idealSegmentCount;
        return Math.max(0.0, 1.0 - Math.abs(1.0 - ratio));
    }

    /**
     * Create summary string.
     */
    public String getSummary() {
        return String.format(
                "Segments: %d, Total rows: %d, Avg size: %.1f, Range: %d-%d, Quality: %.2f",
                segmentCount, totalEstimatedRows, averageSegmentSize,
                minSegmentSize, maxSegmentSize, getQualityScore()
        );
    }

    /**
     * Check if segmentation is optimal for parallel processing.
     */
    public boolean isOptimalForParallelProcessing(int availableCores) {
        // Optimal if we have at least as many segments as cores
        // but not too many more (2x is a good heuristic)
        return segmentCount >= availableCores && segmentCount <= availableCores * 2;
    }

    /**
     * Get recommended parallelism level.
     */
    public int getRecommendedParallelism() {
        // Recommend parallelism based on segment count and quality
        if (segmentCount <= 1) {
            return 1;
        }

        int baseParallelism = Math.min(segmentCount, Runtime.getRuntime().availableProcessors());

        // Adjust based on quality
        double qualityFactor = getQualityScore();
        return Math.max(1, (int) (baseParallelism * qualityFactor));
    }
}