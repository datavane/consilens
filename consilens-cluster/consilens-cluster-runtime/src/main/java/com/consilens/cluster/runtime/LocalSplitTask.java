package com.consilens.cluster.runtime;

import com.consilens.core.diff.DiffResult;

/**
 * One deterministic unit of compare work executed by the local simulator.
 */
public interface LocalSplitTask {

    String getSplitId();

    DiffResult execute() throws Exception;
}
