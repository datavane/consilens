package com.consilens.core.diff;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Execution evidence for one comparison run, derived from the existing
 * {@link DiffResult.InfoTree} and metadata. It does not add new collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffEvidence {

    private String planType;

    private long queryCount;

    private long rowsScanned;

    private long bytesFetched;

    private long segmentCount;

    private long skippedSegments;

    private long localCompareSegments;

    @Builder.Default
    private Map<String, Long> stageTimeMs = Collections.emptyMap();

    @Builder.Default
    private List<String> warnings = Collections.emptyList();
}
