package com.consilens.core.compare;

import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.diff.DiffResult;

public interface CompareRuntime {

    DiffResult execute(CompareRequest request) throws Exception;
}
