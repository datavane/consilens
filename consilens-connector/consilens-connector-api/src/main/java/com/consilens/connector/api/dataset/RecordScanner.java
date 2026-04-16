package com.consilens.connector.api.dataset;

import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.CloseableIterator;

public interface RecordScanner {

    CloseableIterator<CanonicalRecord> scan(CompareSegment segment);
}
