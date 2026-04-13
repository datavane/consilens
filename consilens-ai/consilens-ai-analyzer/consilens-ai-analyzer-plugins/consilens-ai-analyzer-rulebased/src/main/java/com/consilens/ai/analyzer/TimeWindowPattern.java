package com.consilens.ai.analyzer;

import com.consilens.ai.model.PatternMatch;
import com.consilens.ai.spi.DiffPattern;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffRow;

import java.util.List;
import java.util.Optional;

/**
 * Detects time window anomalies where records exist in source but not in target
 * due to replication lag or ETL delay.
 */
public class TimeWindowPattern implements DiffPattern {

    private static final double MISSING_ROW_RATIO_THRESHOLD = 0.3;

    @Override
    public String getName() {
        return "TIME_WINDOW";
    }

    @Override
    public String getDescription() {
        return "Detects missing rows in target that may be caused by replication lag or ETL time windows";
    }

    @Override
    public Optional<PatternMatch> detect(List<DiffRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }

        long sourceMissing = rows.stream().filter(r -> r.getOperation() == DiffOperation.TARGET_MISSING).count();
        long targetMissing = rows.stream().filter(r -> r.getOperation() == DiffOperation.SOURCE_MISSING).count();
        long total = rows.size();

        // Time window issues typically manifest as target missing rows
        if (sourceMissing == 0) {
            return Optional.empty();
        }

        double missingRatio = (double) sourceMissing / total;
        if (missingRatio < 0.05) {
            return Optional.empty();
        }

        double confidence = Math.min(0.8, 0.4 + missingRatio);

        return Optional.of(PatternMatch.builder()
                .patternName(getName())
                .patternType("TIME_WINDOW")
                .description(String.format(
                        "Detected %d rows present in source but missing from target (%.1f%% of total). May indicate replication lag.",
                        sourceMissing, missingRatio * 100))
                .affectedRows((int) sourceMissing)
                .confidence(confidence)
                .repairHint("Check replication lag or ETL schedule; consider re-running the diff after the next sync window")
                .build());
    }
}
