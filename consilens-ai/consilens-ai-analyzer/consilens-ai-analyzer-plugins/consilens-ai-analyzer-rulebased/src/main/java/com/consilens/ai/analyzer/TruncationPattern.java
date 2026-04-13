package com.consilens.ai.analyzer;

import com.consilens.ai.model.PatternMatch;
import com.consilens.ai.spi.DiffPattern;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffRow;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Detects string truncation patterns where target values are shorter versions of source values.
 */
public class TruncationPattern implements DiffPattern {

    @Override
    public String getName() {
        return "TRUNCATION";
    }

    @Override
    public String getDescription() {
        return "Detects string values that are truncated in the target due to column length constraints";
    }

    @Override
    public Optional<PatternMatch> detect(List<DiffRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }

        long truncCount = rows.stream()
                .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                .filter(this::hasTruncation)
                .count();

        if (truncCount == 0) {
            return Optional.empty();
        }

        List<String> affectedCols = rows.stream()
                .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                .filter(this::hasTruncation)
                .flatMap(r -> r.getColumnNames1().stream())
                .distinct()
                .collect(Collectors.toList());

        double confidence = Math.min(0.85, 0.65 + (truncCount * 0.05));

        return Optional.of(PatternMatch.builder()
                .patternName(getName())
                .patternType("TRUNCATION")
                .description("Detected " + truncCount + " rows where string values appear truncated")
                .affectedRows((int) truncCount)
                .confidence(confidence)
                .affectedColumns(affectedCols)
                .repairHint("Increase column VARCHAR length in the target database to accommodate full source values")
                .build());
    }

    private boolean hasTruncation(DiffRow row) {
        if (!row.getSourceValues().isPresent() || !row.getTargetValues().isPresent()) {
            return false;
        }
        List<Object> sourceVals = row.getSourceValues().get();
        List<Object> targetVals = row.getTargetValues().get();
        for (int i = 0; i < Math.min(sourceVals.size(), targetVals.size()); i++) {
            Object sv = sourceVals.get(i);
            Object tv = targetVals.get(i);
            if (sv instanceof String && tv instanceof String) {
                String ss = (String) sv;
                String ts = (String) tv;
                if (ss.length() > ts.length() && ss.startsWith(ts) && ts.length() > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
