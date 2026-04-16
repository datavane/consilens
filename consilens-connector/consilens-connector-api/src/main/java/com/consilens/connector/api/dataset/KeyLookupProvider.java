package com.consilens.connector.api.dataset;

import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.RecordKey;

import java.util.List;
import java.util.Set;

public interface KeyLookupProvider {

    List<CanonicalRecord> fetchByKeys(CompareSegment segment, Set<RecordKey> keys);
}
