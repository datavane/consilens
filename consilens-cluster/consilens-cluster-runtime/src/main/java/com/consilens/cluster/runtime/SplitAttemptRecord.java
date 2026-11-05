package com.consilens.cluster.runtime;

import lombok.Builder;
import lombok.Value;

/**
 * Terminal record persisted in a local execution manifest.
 */
@Value
@Builder
public class SplitAttemptRecord {
    String splitId;
    int attemptNumber;
    SplitAttemptState state;
    String failureMessage;
}
