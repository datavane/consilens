package com.consilens.cluster.runtime;

import java.util.Objects;

/**
 * Mutable state machine for exactly one execution attempt of a split.
 */
public final class SplitAttempt {

    private final String splitId;
    private final int attemptNumber;
    private SplitAttemptState state = SplitAttemptState.PENDING;
    private String failureMessage;

    public SplitAttempt(String splitId, int attemptNumber) {
        if (splitId == null || splitId.trim().isEmpty()) {
            throw new IllegalArgumentException("splitId must not be blank");
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        this.splitId = splitId;
        this.attemptNumber = attemptNumber;
    }

    public void start() {
        transition(SplitAttemptState.PENDING, SplitAttemptState.RUNNING);
    }

    public void succeed() {
        transition(SplitAttemptState.RUNNING, SplitAttemptState.SUCCEEDED);
    }

    public void fail(Exception failure) {
        Objects.requireNonNull(failure, "failure");
        transition(SplitAttemptState.RUNNING, SplitAttemptState.FAILED);
        failureMessage = failure.getMessage();
    }

    public String getSplitId() {
        return splitId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public SplitAttemptState getState() {
        return state;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    private void transition(SplitAttemptState expected, SplitAttemptState next) {
        if (state != expected) {
            throw new IllegalStateException("Split " + splitId + " attempt " + attemptNumber
                    + " cannot transition from " + state + " to " + next);
        }
        state = next;
    }
}
