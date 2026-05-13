package com.consilens.core.segmentation;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Intelligent checkpoint selector for table segmentation.
 * Implements data distribution-aware checkpoint selection strategies.
 */
@Slf4j
public class CheckpointSelector {

    private final LexicographicSpace space;
    private final int bisectionFactor;
    private final int bisectionThreshold;

    public CheckpointSelector(int bisectionFactor, int bisectionThreshold) {
        this.space = new LexicographicSpace();
        this.bisectionFactor = bisectionFactor;
        this.bisectionThreshold = bisectionThreshold;
    }

    /**
     * Choose optimal checkpoints for the given key range.
     */
    public List<KeyVector> chooseCheckpoints(KeyVector minKey, KeyVector maxKey, int preferredCount) {
        return chooseCheckpoints(minKey, maxKey, preferredCount, false);
    }

    public List<KeyVector> chooseCheckpoints(KeyVector minKey,
                                             KeyVector maxKey,
                                             int preferredCount,
                                             boolean forceSplit) {
        if (minKey.isGreaterThan(maxKey)) {
            throw new IllegalArgumentException("minKey must not be greater than maxKey");
        }
        if (minKey.equals(maxKey)) {
            return List.of(minKey, maxKey);
        }

        // Calculate the total space size
        long spaceSize = space.calculateSpaceSize(minKey, maxKey);

        // If space is too small, don't split
        if (!forceSplit && spaceSize <= bisectionThreshold) {
            return List.of(minKey, maxKey);
        }

        // Calculate optimal number of checkpoints based on dimensions
        int effectiveCount = calculateEffectiveCheckpointCount(preferredCount, minKey.getDimensions());

        // Generate uniformly distributed checkpoints
        List<KeyVector> checkpoints = space.range(minKey, maxKey, effectiveCount - 1);

        log.debug("Generated {} checkpoints for space size {}", checkpoints.size(), spaceSize);

        return checkpoints;
    }

    /**
     * Calculate effective checkpoint count based on dimensions and bisection factor.
     */
    private int calculateEffectiveCheckpointCount(int preferredCount, int dimensions) {
        // For multi-dimensional keys, use N-th root to distribute evenly across dimensions
        if (dimensions > 1) {
            // Calculate N-th root of preferred count
            double root = Math.pow(preferredCount, 1.0 / dimensions);
            return Math.max(2, (int) Math.round(root));
        }

        // For single dimension, use bisection factor
        return Math.min(preferredCount, bisectionFactor);
    }

    /**
     * Choose checkpoints with adaptive strategy based on data distribution.
     */
    public List<KeyVector> chooseAdaptiveCheckpoints(KeyVector minKey,
                                                     KeyVector maxKey,
                                                     int preferredCount,
                                                     long estimatedRows,
                                                     SamplingResult samplingResult) {
        // Start with basic uniform distribution
        List<KeyVector> basicCheckpoints = chooseCheckpoints(minKey, maxKey, preferredCount, true);

        // If we have sampling information, adjust checkpoints
        if (samplingResult != null && samplingResult.hasDistributionInfo()) {
            return adjustCheckpointsBasedOnDistribution(basicCheckpoints, samplingResult);
        }

        return basicCheckpoints;
    }

    /**
     * Adjust checkpoints based on actual data distribution from sampling.
     */
    private List<KeyVector> adjustCheckpointsBasedOnDistribution(List<KeyVector> basicCheckpoints,
                                                                SamplingResult samplingResult) {
        // This is a simplified version - in practice, you would:
        // 1. Analyze the distribution of data in each segment
        // 2. Add more checkpoints where data is dense
        // 3. Remove checkpoints where data is sparse
        // 4. Ensure uniform row count per segment where possible

        List<KeyVector> adjustedCheckpoints = new ArrayList<>(basicCheckpoints);

        // For now, return basic checkpoints
        // In a full implementation, this would use the sampling data to optimize
        log.debug("Adjusting {} checkpoints based on distribution data", basicCheckpoints.size());

        return adjustedCheckpoints;
    }

    /**
     * Validate checkpoint selection.
     */
    public boolean validateCheckpoints(List<KeyVector> checkpoints, KeyVector minKey, KeyVector maxKey) {
        if (checkpoints.isEmpty()) {
            return false;
        }

        // Check first checkpoint
        if (!checkpoints.get(0).equals(minKey)) {
            log.warn("First checkpoint should be minKey");
            return false;
        }

        // Check last checkpoint
        if (!checkpoints.get(checkpoints.size() - 1).equals(maxKey)) {
            log.warn("Last checkpoint should be maxKey");
            return false;
        }

        // Check monotonic ordering
        for (int i = 1; i < checkpoints.size(); i++) {
            if (checkpoints.get(i - 1).isGreaterThanOrEqual(checkpoints.get(i))) {
                log.warn("Checkpoints not in strictly increasing order at index {}", i);
                return false;
            }
        }

        return true;
    }

    /**
     * Estimate segment sizes for the given checkpoints.
     */
    public List<Long> estimateSegmentSizes(List<KeyVector> checkpoints) {
        List<Long> sizes = new ArrayList<>();

        for (int i = 0; i < checkpoints.size() - 1; i++) {
            KeyVector start = checkpoints.get(i);
            KeyVector end = checkpoints.get(i + 1);
            long size = space.calculateSpaceSize(start, end);
            sizes.add(size);
        }

        return sizes;
    }

    /**
     * Get default checkpoint selector configuration.
     */
    public static CheckpointSelector defaultSelector() {
        return new CheckpointSelector(32, 16384);
    }

    /**
     * Get high-performance checkpoint selector configuration.
     */
    public static CheckpointSelector highPerformanceSelector() {
        return new CheckpointSelector(64, 8192);
    }

    /**
     * Get memory-efficient checkpoint selector configuration.
     */
    public static CheckpointSelector memoryEfficientSelector() {
        return new CheckpointSelector(16, 32768);
    }
}
