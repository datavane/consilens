package com.consilens.core.compare;

import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.planner.CompareRequest;

public interface ComparePlanner {

    ComparePlan plan(CompareRequest request, DatasetHandle source, DatasetHandle target);
}
