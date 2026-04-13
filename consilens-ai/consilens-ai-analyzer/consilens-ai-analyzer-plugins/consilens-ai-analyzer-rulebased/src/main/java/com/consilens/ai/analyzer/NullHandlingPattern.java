package com.consilens.ai.analyzer;

import com.consilens.ai.model.PatternMatch;
import com.consilens.ai.spi.DiffPattern;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffRow;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Detects NULL handling differences between source and target.
 */
public class NullHandlingPattern implements DiffPattern {

    @Override
    public String getName() {
        return "NULL_HANDLING";
    }

    @Override
    public String getDescription() {
        return "Detects differences where one side has NULL and the other has an empty string or default value";
    }

    @Override
    public Optional<PatternMatch> detect(List<DiffRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }

        long nullCount = rows.stream()
                .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                .filter(this::hasNullVsEmptyDifference)
                .count();

        if (nullCount == 0) {
            return Optional.empty();
        }

        List<String> affectedCols = rows.stream()
                .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                .filter(this::hasNullVsEmptyDifference)
                .flatMap(r -> r.getColumnNames1().stream())
                .distinct()
                .collect(Collectors.toList());

        double confidence = Math.min(0.9, 0.7 + (nullCount * 0.05));

        return Optional.of(PatternMatch.builder()
                .patternName(getName())
                .patternType("NULL_HANDLING")
                .description("Detected " + nullCount + " rows where NULL vs empty/default values differ")
                .affectedRows((int) nullCount)
                .confidence(confidence)
                .affectedColumns(affectedCols)
                .repairHint("Align NULL handling conventions: decide whether NULL and empty string should be treated as equivalent")
                .build());
    }

    private boolean hasNullVsEmptyDifference(DiffRow row) {
        if (!row.getSourceValues().isPresent() || !row.getTargetValues().isPresent()) {
            return false;
        }
        List<Object> sourceVals = row.getSourceValues().get();
        List<Object> targetVals = row.getTargetValues().get();
        for (int i = 0; i < Math.min(sourceVals.size(), targetVals.size()); i++) {
            Object sv = sourceVals.get(i);
            Object tv = targetVals.get(i);
            boolean svNull = sv == null || "".equals(sv);
            boolean tvNull = tv == null || "".equals(tv);
            if (svNull != tvNull) {
                return true;
            }
        }
        return false;
    }
}
