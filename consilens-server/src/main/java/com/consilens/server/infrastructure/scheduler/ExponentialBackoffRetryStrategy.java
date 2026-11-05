package com.consilens.server.infrastructure.scheduler;

import java.util.concurrent.ThreadLocalRandom;

public class ExponentialBackoffRetryStrategy {

    private static final int INITIAL_BACKOFF_MS = 1000;
    private static final int MAX_BACKOFF_MS = 60000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final double JITTER_FACTOR = 0.1;

    private final int maxRetries;

    public ExponentialBackoffRetryStrategy(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getBackoffTime(int retryCount) throws MaxRetriesExceededException {
        if (retryCount >= maxRetries) {
            throw new MaxRetriesExceededException("Max retries exceeded: " + maxRetries);
        }

        long backoff = Math.min(
                (long) (INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, retryCount)),
                MAX_BACKOFF_MS);
        long jitter = (long) (backoff * JITTER_FACTOR);
        long randomJitter = ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        return Math.max(0, backoff + randomJitter);
    }

    public int reset() {
        return 0;
    }
}
