package com.consilens.cluster.runtime;

/**
 * Lifecycle state of one local split attempt.
 */
public enum SplitAttemptState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
