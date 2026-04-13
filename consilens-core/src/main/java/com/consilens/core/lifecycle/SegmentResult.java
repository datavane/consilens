package com.consilens.core.lifecycle;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;

/**
 * Result of comparing a single segment, passed to DiffLifecycle.onSegmentComplete.
 */
@Getter
@Builder
public class SegmentResult {

    private final int segmentIndex;

    private final long sourceRowsScanned;

    private final long targetRowsScanned;

    private final int differencesFound;

    private final long totalDifferencesAccumulated;

    private final Duration duration;

    private final DiffContext context;
}
