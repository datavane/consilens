package com.consilens.ai.analyzer;

import com.consilens.ai.model.PatternMatch;
import com.consilens.ai.spi.DiffPattern;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffRow;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Detects character encoding issues in string columns.
 */
public class EncodingPattern implements DiffPattern {

    @Override
    public String getName() {
        return "ENCODING_ISSUE";
    }

    @Override
    public String getDescription() {
        return "Detects character encoding mismatches in string columns (e.g., UTF-8 vs GBK)";
    }

    @Override
    public Optional<PatternMatch> detect(List<DiffRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }

        long encodingCount = rows.stream()
                .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                .filter(this::hasEncodingIssue)
                .count();

        if (encodingCount == 0) {
            return Optional.empty();
        }

        List<String> affectedCols = rows.stream()
                .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                .filter(this::hasEncodingIssue)
                .flatMap(r -> r.getColumnNames1().stream())
                .distinct()
                .collect(Collectors.toList());

        double confidence = Math.min(0.8, 0.55 + (encodingCount * 0.05));

        return Optional.of(PatternMatch.builder()
                .patternName(getName())
                .patternType("ENCODING_ISSUE")
                .description("Detected " + encodingCount + " rows with potential character encoding issues")
                .affectedRows((int) encodingCount)
                .confidence(confidence)
                .affectedColumns(affectedCols)
                .repairHint("Ensure consistent character encoding (preferably UTF-8) across source and target databases")
                .build());
    }

    private boolean hasEncodingIssue(DiffRow row) {
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
                if (!ss.equals(ts) && ss.length() != ts.length() && containsNonAscii(ss)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsNonAscii(String s) {
        return s.chars().anyMatch(c -> c > 127);
    }
}
