package com.consilens.core.segmentation;

import lombok.extern.slf4j.Slf4j;

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
     * Get default checkpoint selector configuration.
     */
    public static CheckpointSelector defaultSelector() {
        return new CheckpointSelector(32, 16384);
    }
}
