package com.consilens.ai.analyzer;

import com.consilens.ai.model.PatternMatch;
import com.consilens.ai.spi.DiffPattern;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffRow;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Detects precision loss patterns where numeric values differ due to floating-point rounding.
 */
public class PrecisionLossPattern implements DiffPattern {

    @Override
    public String getName() {
        return "PRECISION_LOSS";
    }

    @Override
    public String getDescription() {
        return "Detects numeric differences caused by floating-point precision or scale mismatch";
    }

    @Override
    public Optional<PatternMatch> detect(List<DiffRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }

        long precisionCount = rows.stream()
                .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                .filter(this::hasPrecisionDifference)
                .count();

        if (precisionCount == 0) {
            return Optional.empty();
        }

        List<String> affectedCols = rows.stream()
                .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                .filter(this::hasPrecisionDifference)
                .flatMap(r -> r.getColumnNames1().stream())
                .filter(c -> c.toLowerCase().contains("amount") || c.toLowerCase().contains("price")
                        || c.toLowerCase().contains("rate") || c.toLowerCase().contains("value"))
                .distinct()
                .collect(Collectors.toList());

        double confidence = Math.min(0.85, 0.6 + (precisionCount * 0.05));

        return Optional.of(PatternMatch.builder()
                .patternName(getName())
                .patternType("PRECISION_LOSS")
                .description("Detected " + precisionCount + " rows with numeric precision differences")
                .affectedRows((int) precisionCount)
                .confidence(confidence)
                .affectedColumns(affectedCols)
                .repairHint("Align numeric column precision/scale definitions between source and target databases")
                .build());
    }

    private boolean hasPrecisionDifference(DiffRow row) {
        if (!row.getSourceValues().isPresent() || !row.getTargetValues().isPresent()) {
            return false;
        }
        List<Object> sourceVals = row.getSourceValues().get();
        List<Object> targetVals = row.getTargetValues().get();
        for (int i = 0; i < Math.min(sourceVals.size(), targetVals.size()); i++) {
            Object sv = sourceVals.get(i);
            Object tv = targetVals.get(i);
            if (isNumeric(sv) && isNumeric(tv) && !sv.equals(tv)) {
                try {
                    BigDecimal bSv = new BigDecimal(sv.toString());
                    BigDecimal bTv = new BigDecimal(tv.toString());
                    if (bSv.compareTo(bTv) != 0 && bSv.subtract(bTv).abs().compareTo(BigDecimal.ONE) < 0) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    private boolean isNumeric(Object val) {
        return val instanceof Number || (val instanceof String && ((String) val).matches("-?\\d+(\\.\\d+)?"));
    }
}
