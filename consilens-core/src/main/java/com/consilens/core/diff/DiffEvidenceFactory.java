package com.consilens.core.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Derives execution evidence from the existing InfoTree and metadata.
 */
public final class DiffEvidenceFactory {

    private DiffEvidenceFactory() {
    }

    public static DiffEvidence from(DiffResult result) {
        if (result == null) {
            return null;
        }
        DiffEvidence.DiffEvidenceBuilder builder = DiffEvidence.builder();
        Map<String, Object> metadata = result.getMetadata();
        if (metadata != null && metadata.get("executedPlan") != null) {
            builder.planType(String.valueOf(metadata.get("executedPlan")));
        }

        Optional<DiffResult.InfoTree> tree = result.getInfoTree();
        if (tree != null && tree.isPresent()) {
            DiffResult.InfoTree info = tree.get();
            long queryCount = 0L;
            long bytesFetched = 0L;
            if (info.getNodes() != null) {
                for (DiffResult.InfoTreeNode node : info.getNodes()) {
                    if (node == null) {
                        continue;
                    }
                    queryCount += node.getQueryCount();
                    bytesFetched += node.getBytesFetched();
                }
            }
            builder.queryCount(queryCount)
                    .rowsScanned(info.getTotalRowsScanned())
                    .bytesFetched(bytesFetched)
                    .segmentCount(info.getTotalSegments())
                    .skippedSegments(info.getSkippedSegments())
                    .localCompareSegments(info.getLocalCompareSegments())
                    .stageTimeMs(info.getStageTimeMs() != null
                            ? info.getStageTimeMs() : Collections.emptyMap());
        }

        List<String> warnings = new ArrayList<>();
        SchemaCompatibility compatibility = result.getSchemaCompatibility();
        if (compatibility != null && compatibility.getWarnings() != null) {
            warnings.addAll(compatibility.getWarnings());
        }
        builder.warnings(warnings);
        return builder.build();
    }
}
