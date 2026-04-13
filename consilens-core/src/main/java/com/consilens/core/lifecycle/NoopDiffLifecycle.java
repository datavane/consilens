package com.consilens.core.lifecycle;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;

import java.util.List;

/**
 * No-op implementation used when no sink is configured.
 */
public class NoopDiffLifecycle implements DiffLifecycle {

    @Override
    public void onDiffStart(DiffContext context) {}

    @Override
    public void onSegmentComplete(SegmentResult result) {}

    @Override
    public void onDifferencesFound(List<DiffRow> diffs, DiffContext context) {}

    @Override
    public void onDiffComplete(DiffResult result, DiffContext context) {}

    @Override
    public void onDiffError(DiffContext context, Throwable error) {}
}
